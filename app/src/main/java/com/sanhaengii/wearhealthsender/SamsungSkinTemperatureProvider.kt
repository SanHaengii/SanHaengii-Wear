package com.sanhaengii.wearhealthsender

import android.content.Context
import android.os.Handler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.roundToInt

class SamsungSkinTemperatureProvider(
    private val context: Context,
    private val mainHandler: Handler,
    private val onReading: (temperatureCelsius: Double) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onUnavailable: (String) -> Unit,
) {
    private var sdk: SamsungSkinTemperatureSdk? = null
    private var healthTrackingService: Any? = null
    private var tracker: Any? = null
    private var trackerListener: Any? = null
    private var isConnected = false
    private var isTracking = false

    fun start(): Boolean {
        val loadedSdk = runCatching { SamsungSkinTemperatureSdk.load(context.classLoader) }
            .onFailure { onUnavailable("Samsung Health Sensor SDK was not found for skin temperature.") }
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
            onStatus("Samsung skin temperature SDK connecting.")
        }.onFailure {
            onUnavailable("Samsung skin temperature setup failed: ${it.shortMessage()}")
        }.isSuccess
    }

    fun stop() {
        isTracking = false
        unsetTrackerListener()
        runCatching {
            healthTrackingService?.javaClass
                ?.getMethod("disconnectService")
                ?.invoke(healthTrackingService)
        }
        tracker = null
        healthTrackingService = null
        trackerListener = null
        isConnected = false
    }

    private fun createConnectionListener(loadedSdk: SamsungSkinTemperatureSdk): Any {
        return Proxy.newProxyInstance(
            loadedSdk.classLoader,
            arrayOf(loadedSdk.connectionListenerClass),
        ) { _, method, args ->
            when (method.name) {
                "onConnectionSuccess" -> mainHandler.post { handleConnectionSuccess(loadedSdk) }
                "onConnectionEnded" -> mainHandler.post {
                    isConnected = false
                    isTracking = false
                    onStatus("Samsung skin temperature connection ended.")
                }
                "onConnectionFailed" -> {
                    val reason = args?.firstOrNull()?.shortMessage() ?: "unknown error"
                    mainHandler.post { onUnavailable("Samsung skin temperature connection failed: $reason") }
                }
            }
            proxyDefault(method)
        }
    }

    private fun handleConnectionSuccess(loadedSdk: SamsungSkinTemperatureSdk) {
        runCatching {
            val service = healthTrackingService ?: throw IllegalStateException("HealthTrackingService is null")
            val trackerType = loadedSdk.skinTemperatureTrackerType()
            val capability = service.javaClass
                .getMethod("getTrackingCapability")
                .invoke(service)
            val supportedTrackers = capability.javaClass
                .getMethod("getSupportHealthTrackerTypes")
                .invoke(capability) as? Collection<*>

            if (supportedTrackers?.any { it == trackerType || it.toString() == trackerType.toString() } != true) {
                throw IllegalStateException("SKIN_TEMPERATURE_CONTINUOUS is not supported on this watch.")
            }

            tracker = service.javaClass
                .getMethod("getHealthTracker", loadedSdk.healthTrackerTypeClass)
                .invoke(service, trackerType)
            trackerListener = createTrackerListener(loadedSdk)
            tracker?.javaClass
                ?.getMethod("setEventListener", loadedSdk.trackerEventListenerClass)
                ?.invoke(tracker, trackerListener)
            isConnected = true
            isTracking = true
            onStatus("Samsung skin temperature tracking started.")
        }.onFailure {
            onUnavailable("Samsung skin temperature is unavailable: ${it.shortMessage()}")
        }
    }

    private fun createTrackerListener(loadedSdk: SamsungSkinTemperatureSdk): Any {
        return Proxy.newProxyInstance(
            loadedSdk.classLoader,
            arrayOf(loadedSdk.trackerEventListenerClass),
        ) { _, method, args ->
            when (method.name) {
                "onDataReceived" -> handleDataReceived(loadedSdk, args?.firstOrNull())
                "onFlushCompleted" -> mainHandler.post { onStatus("Samsung skin temperature flush completed.") }
                "onError" -> {
                    val reason = args?.firstOrNull()?.toString() ?: "unknown error"
                    mainHandler.post { onUnavailable("Samsung skin temperature tracker error: $reason") }
                }
            }
            proxyDefault(method)
        }
    }

    private fun handleDataReceived(loadedSdk: SamsungSkinTemperatureSdk, data: Any?) {
        val points = data as? List<*> ?: return
        val statusKey = loadedSdk.skinTemperatureSetClass.getField("STATUS").get(null)
            ?: return
        val objectTemperatureKey = loadedSdk.skinTemperatureSetClass.getField("OBJECT_TEMPERATURE").get(null)
            ?: return

        points.filterNotNull().forEach { dataPoint ->
            val status = dataPoint.getValue(loadedSdk, statusKey) as? Int
            val temperature = dataPoint.getValue(loadedSdk, objectTemperatureKey)
                ?.toDoubleOrNull()

            if (status == SKIN_TEMPERATURE_STATUS_NORMAL && temperature != null) {
                mainHandler.post {
                    onReading(temperature.roundToOneDecimal())
                }
            } else if (status != null && status < 0) {
                mainHandler.post {
                    onUnavailable("Samsung skin temperature status: $status")
                }
            }
        }
    }

    private fun unsetTrackerListener() {
        runCatching {
            tracker?.javaClass
                ?.getMethod("unsetEventListener")
                ?.invoke(tracker)
        }
        trackerListener = null
    }

    private fun Any.getValue(loadedSdk: SamsungSkinTemperatureSdk, key: Any): Any? {
        return javaClass
            .getMethod("getValue", loadedSdk.valueKeyClass)
            .invoke(this, key)
    }

    private fun Any.toDoubleOrNull(): Double? {
        return when (this) {
            is Number -> toDouble()
            else -> toString().toDoubleOrNull()
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

    private data class SamsungSkinTemperatureSdk(
        val classLoader: ClassLoader,
        val connectionListenerClass: Class<*>,
        val healthTrackingServiceClass: Class<*>,
        val healthTrackerTypeClass: Class<*>,
        val trackerEventListenerClass: Class<*>,
        val valueKeyClass: Class<*>,
        val skinTemperatureSetClass: Class<*>,
    ) {
        fun skinTemperatureTrackerType(): Any {
            return runCatching { enumValue("SKIN_TEMPERATURE_CONTINUOUS") }
                .getOrElse { enumValue("SKIN_TEMPERATURE") }
        }

        private fun enumValue(name: String): Any {
            return healthTrackerTypeClass
                .getMethod("valueOf", String::class.java)
                .invoke(null, name)
        }

        companion object {
            fun load(classLoader: ClassLoader): SamsungSkinTemperatureSdk {
                return SamsungSkinTemperatureSdk(
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
                    skinTemperatureSetClass = Class.forName(
                        "com.samsung.android.service.health.tracking.data.ValueKey\$SkinTemperatureSet",
                        false,
                        classLoader,
                    ),
                )
            }
        }
    }

    companion object {
        private const val SKIN_TEMPERATURE_STATUS_NORMAL = 0
    }
}

private fun Double.roundToOneDecimal(): Double {
    return (this * 10.0).roundToInt() / 10.0
}
