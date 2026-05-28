package com.sanhaengii.wearhealthsender

import android.content.Context
import android.os.Handler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class SamsungSpo2Provider(
    private val context: Context,
    private val mainHandler: Handler,
    private val onReading: (spo2: Double, heartRate: Int?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFallbackNeeded: (String) -> Unit,
) {
    private var sdk: SamsungSpo2Sdk? = null
    private var healthTrackingService: Any? = null
    private var spo2Tracker: Any? = null
    private var trackerListener: Any? = null
    private var isConnected = false
    private var isMeasuring = false

    private val measurementTimeout = Runnable {
        if (!isMeasuring) {
            return@Runnable
        }
        isMeasuring = false
        unsetTrackerListener()
        onFallbackNeeded("Samsung SpO2 measurement timed out.")
    }

    fun start(): Boolean {
        val loadedSdk = runCatching { SamsungSpo2Sdk.load(context.classLoader) }
            .onFailure { onFallbackNeeded("Samsung Health Sensor SDK was not found in the app.") }
            .getOrNull()
            ?: return false

        sdk = loadedSdk
        return runCatching {
            val connectionListener = createConnectionListener(loadedSdk)
            healthTrackingService = loadedSdk.healthTrackingServiceClass
                .getConstructor(loadedSdk.connectionListenerClass, Context::class.java)
                .newInstance(connectionListener, context.applicationContext)
            healthTrackingService?.javaClass
                ?.getMethod("connectService")
                ?.invoke(healthTrackingService)
            onStatus("Samsung Health Sensor SDK connecting.")
        }.onFailure {
            onFallbackNeeded("Samsung Health Sensor SDK connection setup failed: ${it.shortMessage()}")
        }.isSuccess
    }

    fun requestMeasurement(): Boolean {
        val loadedSdk = sdk
        val tracker = spo2Tracker
        if (loadedSdk == null || tracker == null || !isConnected) {
            onStatus("Samsung SpO2 is not ready yet.")
            return false
        }

        if (isMeasuring) {
            onStatus("Samsung SpO2 measurement is already running.")
            return true
        }

        return runCatching {
            trackerListener = createTrackerListener(loadedSdk)
            tracker.javaClass
                .getMethod("setEventListener", loadedSdk.trackerEventListenerClass)
                .invoke(tracker, trackerListener)
            isMeasuring = true
            mainHandler.postDelayed(measurementTimeout, SAMSUNG_SPO2_TIMEOUT_MS)
            onStatus("Samsung SpO2 measurement started. Keep the watch still.")
        }.onFailure {
            isMeasuring = false
            unsetTrackerListener()
            onFallbackNeeded("Samsung SpO2 measurement could not start: ${it.shortMessage()}")
        }.isSuccess
    }

    fun stop() {
        isMeasuring = false
        mainHandler.removeCallbacks(measurementTimeout)
        unsetTrackerListener()
        runCatching {
            healthTrackingService?.javaClass
                ?.getMethod("disconnectService")
                ?.invoke(healthTrackingService)
        }
        healthTrackingService = null
        spo2Tracker = null
        trackerListener = null
        isConnected = false
    }

    private fun createConnectionListener(loadedSdk: SamsungSpo2Sdk): Any {
        return Proxy.newProxyInstance(
            loadedSdk.classLoader,
            arrayOf(loadedSdk.connectionListenerClass),
        ) { _, method, args ->
            when (method.name) {
                "onConnectionSuccess" -> mainHandler.post { handleConnectionSuccess(loadedSdk) }
                "onConnectionEnded" -> mainHandler.post {
                    isConnected = false
                    onStatus("Samsung Health Sensor SDK connection ended.")
                }
                "onConnectionFailed" -> {
                    val reason = args?.firstOrNull()?.shortMessage() ?: "unknown error"
                    mainHandler.post { onFallbackNeeded("Samsung Health Platform connection failed: $reason") }
                }
            }
            proxyDefault(method)
        }
    }

    private fun handleConnectionSuccess(loadedSdk: SamsungSpo2Sdk) {
        runCatching {
            val service = healthTrackingService ?: throw IllegalStateException("HealthTrackingService is null")
            val trackerType = loadedSdk.spo2TrackerType()
            val capability = service.javaClass
                .getMethod("getTrackingCapability")
                .invoke(service)
            val supportedTrackers = capability.javaClass
                .getMethod("getSupportHealthTrackerTypes")
                .invoke(capability) as? Collection<*>

            if (supportedTrackers?.any { it == trackerType || it.toString() == trackerType.toString() } != true) {
                throw IllegalStateException("SPO2_ON_DEMAND is not supported on this watch.")
            }

            spo2Tracker = service.javaClass
                .getMethod("getHealthTracker", loadedSdk.healthTrackerTypeClass)
                .invoke(service, trackerType)
            isConnected = true
            onStatus("Samsung SpO2 ready. Use Measure SpO2 for a real reading.")
        }.onFailure {
            onFallbackNeeded("Samsung SpO2 is unavailable: ${it.shortMessage()}")
        }
    }

    private fun createTrackerListener(loadedSdk: SamsungSpo2Sdk): Any {
        return Proxy.newProxyInstance(
            loadedSdk.classLoader,
            arrayOf(loadedSdk.trackerEventListenerClass),
        ) { _, method, args ->
            when (method.name) {
                "onDataReceived" -> handleDataReceived(loadedSdk, args?.firstOrNull())
                "onFlushCompleted" -> mainHandler.post { onStatus("Samsung SpO2 flush completed.") }
                "onError" -> {
                    val reason = args?.firstOrNull()?.toString() ?: "unknown error"
                    mainHandler.post {
                        isMeasuring = false
                        unsetTrackerListener()
                        onFallbackNeeded("Samsung SpO2 tracker error: $reason")
                    }
                }
            }
            proxyDefault(method)
        }
    }

    private fun handleDataReceived(loadedSdk: SamsungSpo2Sdk, data: Any?) {
        val points = data as? List<*> ?: return
        val statusKey = loadedSdk.spO2SetClass.getField("STATUS").get(null)
            ?: return
        val spo2Key = loadedSdk.spO2SetClass.getField("SPO2").get(null)
            ?: return
        val heartRateKey = loadedSdk.spO2SetClass.getField("HEART_RATE").get(null)
            ?: return

        points.filterNotNull().forEach { dataPoint ->
            val status = dataPoint.getValue(loadedSdk, statusKey) as? Int
            val spo2 = dataPoint.getValue(loadedSdk, spo2Key) as? Int
            val heartRate = dataPoint.getValue(loadedSdk, heartRateKey) as? Int

            when {
                status == SPO2_STATUS_COMPLETE && spo2 != null -> mainHandler.post {
                    if (!isMeasuring) {
                        return@post
                    }
                    isMeasuring = false
                    unsetTrackerListener()
                    onReading(spo2.toDouble(), heartRate)
                }
                status != null && status < 0 -> mainHandler.post {
                    if (!isMeasuring) {
                        return@post
                    }
                    isMeasuring = false
                    unsetTrackerListener()
                    onFallbackNeeded("Samsung SpO2 measurement failed: ${describeSpo2Status(status)}")
                }
                status != null -> mainHandler.post {
                    onStatus("Samsung SpO2 status: ${describeSpo2Status(status)}")
                }
            }
        }
    }

    private fun unsetTrackerListener() {
        mainHandler.removeCallbacks(measurementTimeout)
        runCatching {
            spo2Tracker?.javaClass
                ?.getMethod("unsetEventListener")
                ?.invoke(spo2Tracker)
        }
        trackerListener = null
    }

    private fun Any.getValue(loadedSdk: SamsungSpo2Sdk, key: Any): Any? {
        return javaClass
            .getMethod("getValue", loadedSdk.valueKeyClass)
            .invoke(this, key)
    }

    private fun describeSpo2Status(status: Int): String {
        return when (status) {
            -6 -> "timeout"
            -5 -> "low signal quality"
            -4 -> "device moved"
            0 -> "calculating"
            SPO2_STATUS_COMPLETE -> "completed"
            else -> "status $status"
        }
    }

    private fun Any.shortMessage(): String {
        val code = runCatching { javaClass.getMethod("getErrorCode").invoke(this) }.getOrNull()
        val base = when (this) {
            is Throwable -> message ?: javaClass.simpleName
            else -> toString()
        }
        return if (code == null) base else "$base (code=$code)"
    }

    private fun proxyDefault(method: Method): Any? {
        return when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Void.TYPE -> null
            else -> null
        }
    }

    private data class SamsungSpo2Sdk(
        val classLoader: ClassLoader,
        val connectionListenerClass: Class<*>,
        val healthTrackingServiceClass: Class<*>,
        val healthTrackerTypeClass: Class<*>,
        val trackerEventListenerClass: Class<*>,
        val valueKeyClass: Class<*>,
        val spO2SetClass: Class<*>,
    ) {
        fun spo2TrackerType(): Any {
            return runCatching { enumValue("SPO2_ON_DEMAND") }
                .getOrElse { enumValue("SPO2") }
        }

        private fun enumValue(name: String): Any {
            return healthTrackerTypeClass
                .getMethod("valueOf", String::class.java)
                .invoke(null, name)
        }

        companion object {
            fun load(classLoader: ClassLoader): SamsungSpo2Sdk {
                return SamsungSpo2Sdk(
                    classLoader = classLoader,
                    connectionListenerClass = Class.forName(
                        "com.samsung.android.service.health.tracking.ConnectionListener",
                        false,
                        classLoader,
                    ),
                    healthTrackingServiceClass = Class.forName(
                        "com.samsung.android.service.health.tracking.HealthTrackingService",
                        false,
                        classLoader,
                    ),
                    healthTrackerTypeClass = Class.forName(
                        "com.samsung.android.service.health.tracking.data.HealthTrackerType",
                        false,
                        classLoader,
                    ),
                    trackerEventListenerClass = Class.forName(
                        "com.samsung.android.service.health.tracking.HealthTracker\$TrackerEventListener",
                        false,
                        classLoader,
                    ),
                    valueKeyClass = Class.forName(
                        "com.samsung.android.service.health.tracking.data.ValueKey",
                        false,
                        classLoader,
                    ),
                    spO2SetClass = Class.forName(
                        "com.samsung.android.service.health.tracking.data.ValueKey\$SpO2Set",
                        false,
                        classLoader,
                    ),
                )
            }
        }
    }

    companion object {
        private const val SPO2_STATUS_COMPLETE = 2
        private const val SAMSUNG_SPO2_TIMEOUT_MS = 31_000L
    }
}
