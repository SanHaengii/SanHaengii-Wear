@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.sanhaengii.wearhealthsender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import androidx.health.services.client.HealthServices
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEvent
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseTrackedStatus
import com.sanhaengii.wearhealthsender.ui.AlertScreen
import com.sanhaengii.wearhealthsender.ui.BackendTestEntryScreen
import com.sanhaengii.wearhealthsender.ui.MainDashboard
import com.sanhaengii.wearhealthsender.ui.SosScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class ComposeMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var mainViewModel: MainViewModel
    private var currentPayload = HealthServicesPayload.empty()
    private var isSending = false
    private var isExerciseRunning = false
    private var nextFakeSpo2 = 100
    private var lastSpo2: Double? = null
    private var isFakeSpo2Active = false
    private var lastSamsungSpo2RequestAt = 0L
    private var samsungSpo2Provider: SamsungSpo2Provider? = null
    private var samsungSkinTemperatureProvider: SamsungSkinTemperatureProvider? = null

    private val fakeSpo2Runnable = object : Runnable {
        override fun run() {
            updateFakeSpo2()
            mainHandler.postDelayed(this, SPO2_TICK_MS)
        }
    }

    private val periodicHealthSendRunnable = object : Runnable {
        override fun run() {
            if (!mainViewModel.isHikingActive) {
                return
            }
            requestSamsungSpo2IfDue()
            sendCollectedPayload()
            mainHandler.postDelayed(this, HEALTH_SEND_INTERVAL_MS)
        }
    }

    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val payload = payloadFromMetrics(update.latestMetrics)
            currentPayload = currentPayload.mergeWith(payload)
            currentPayload.heartRate?.let { mainViewModel.updateHeartRate(it) }
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
        override fun onExerciseEventReceived(event: ExerciseEvent) = Unit
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) = Unit
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            mainViewModel = viewModel()
            
            // 초기값 설정 및 Wearable 데이터 로드
            LaunchedEffect(Unit) {
                // ... (existing logic)
            }

            MaterialTheme {
                var isAnomalyDetected by remember { mutableStateOf(false) }
                var alertMessage by remember { mutableStateOf("심박수 과부하! 휴식이 필요합니다.") }

                val pagerState = rememberPagerState(pageCount = { 3 })

                if (isAnomalyDetected) {
                    AlertScreen(message = alertMessage, isWarning = true)
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> MainDashboard(
                                bpm = mainViewModel.bpm,
                                eta = mainViewModel.eta,
                                distance = mainViewModel.distance
                            )
                            1 -> SosScreen(
                                isHikingActive = mainViewModel.isHikingActive,
                                isSosReporting = mainViewModel.isSosReporting,
                                onHikingToggle = { toggleHikingFromWatch() },
                                onSosClick = { 
                                    mainViewModel.isSosReporting = true
                                    notifySosTriggered()
                                }
                            )
                            2 -> BackendTestEntryScreen(
                                onOpenBackendTest = {
                                    startActivity(
                                        Intent(this@ComposeMainActivity, MainActivity::class.java)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        Wearable.getDataClient(this).addListener(this)
    }

    private fun notifySosTriggered() {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@ComposeMainActivity).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(this@ComposeMainActivity)
                        .sendMessage(node.id, "/sos_triggered", "SOS".toByteArray())
                        .await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDataChanged(dataEvents: com.google.android.gms.wearable.DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                if (path == "/hiking_info") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val eta = dataMap.getString("eta", "-")
                    val distance = dataMap.getString("distance", "-")
                    val bpm = dataMap.getInt("bpm", 0)
                    mainViewModel.updateEta(eta)
                    mainViewModel.updateDistance(distance)
                    if (bpm > 0) mainViewModel.updateHeartRate(bpm)
                } else if (path == "/sos_status") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val status = dataMap.getString("status")
                    if (status == "finished") {
                        mainViewModel.resetSosReporting()
                    }
                }
            }
        }
    }

    private fun toggleHikingFromWatch() {
        if (mainViewModel.isHikingActive) {
            stopHikingFromWatch()
        } else {
            startHikingFromWatch()
        }
    }

    private fun startHikingFromWatch() {
        if (!hasRequiredPermissions()) {
            requestPermissions(requiredPermissions(), HEALTH_PERMISSION_REQUEST)
            return
        }

        currentPayload = HealthServicesPayload.empty().copy(
            spo2 = lastSpo2,
            bodyTemp = currentPayload.bodyTemp,
        )
        startSensorProviders()

        scope.launch {
            val result = runCatching {
                exerciseClient.setUpdateCallback(exerciseUpdateCallback)
                val exerciseInfo = exerciseClient.getCurrentExerciseInfoAsync().await()
                if (exerciseInfo.exerciseTrackedStatus != ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) {
                    exerciseClient.startExerciseAsync(
                        ExerciseConfig(
                            exerciseType = ExerciseType.WALKING,
                            dataTypes = DEFAULT_DATA_TYPES,
                            isAutoPauseAndResumeEnabled = false,
                            isGpsEnabled = false,
                        )
                    ).await()
                }
            }

            result
                .onSuccess {
                    isExerciseRunning = true
                    mainViewModel.updateHikingActive(true)
                    mainHandler.removeCallbacks(periodicHealthSendRunnable)
                    mainHandler.postDelayed(periodicHealthSendRunnable, HEALTH_SEND_INTERVAL_MS)
                    requestSamsungSpo2IfDue(force = true)
                }
                .onFailure {
                    mainViewModel.updateHikingActive(false)
                    it.printStackTrace()
                }
        }
    }

    private fun stopHikingFromWatch() {
        mainViewModel.updateHikingActive(false)
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
        mainHandler.removeCallbacks(fakeSpo2Runnable)
        samsungSpo2Provider?.stop()
        samsungSpo2Provider = null
        samsungSkinTemperatureProvider?.stop()
        samsungSkinTemperatureProvider = null
        isFakeSpo2Active = false

        scope.launch {
            runCatching {
                exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback).await()
                if (isExerciseRunning) {
                    exerciseClient.endExerciseAsync().await()
                }
            }
            isExerciseRunning = false
        }
    }

    private fun startSensorProviders() {
        startSpo2Provider()
        startSkinTemperatureProvider()
    }

    private fun startSpo2Provider() {
        if (samsungSpo2Provider != null || isFakeSpo2Active) {
            return
        }

        val provider = SamsungSpo2Provider(
            context = this,
            mainHandler = mainHandler,
            onReading = { spo2, heartRate ->
                lastSpo2 = spo2
                currentPayload = currentPayload.copy(
                    measuredAt = freshMeasuredAt(),
                    heartRate = heartRate ?: currentPayload.heartRate,
                    spo2 = spo2,
                )
                currentPayload.heartRate?.let { mainViewModel.updateHeartRate(it) }
            },
            onStatus = { },
            onFallbackNeeded = { activateFakeSpo2Fallback() },
        )
        samsungSpo2Provider = provider
        if (!provider.start()) {
            activateFakeSpo2Fallback()
        }
    }

    private fun startSkinTemperatureProvider() {
        if (samsungSkinTemperatureProvider != null) {
            return
        }

        val provider = SamsungSkinTemperatureProvider(
            context = this,
            mainHandler = mainHandler,
            onReading = { temperature ->
                currentPayload = currentPayload.copy(
                    measuredAt = freshMeasuredAt(),
                    bodyTemp = temperature,
                )
            },
            onStatus = { },
            onUnavailable = { },
        )
        samsungSkinTemperatureProvider = provider
        if (!provider.start()) {
            samsungSkinTemperatureProvider = null
        }
    }

    private fun requestSamsungSpo2IfDue(force: Boolean = false) {
        if (isFakeSpo2Active) {
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastSamsungSpo2RequestAt < SPO2_REAL_REQUEST_INTERVAL_MS) {
            return
        }

        val requested = samsungSpo2Provider?.requestMeasurement() == true
        if (requested) {
            lastSamsungSpo2RequestAt = now
        }
    }

    private fun activateFakeSpo2Fallback() {
        if (isFakeSpo2Active) {
            return
        }

        samsungSpo2Provider?.stop()
        samsungSpo2Provider = null
        isFakeSpo2Active = true
        updateFakeSpo2()
        mainHandler.removeCallbacks(fakeSpo2Runnable)
        mainHandler.postDelayed(fakeSpo2Runnable, SPO2_TICK_MS)
    }

    private fun updateFakeSpo2() {
        val spo2 = nextFakeSpo2.toDouble()
        lastSpo2 = spo2
        nextFakeSpo2 = if (nextFakeSpo2 <= MIN_FAKE_SPO2) MAX_FAKE_SPO2 else nextFakeSpo2 - 1
        currentPayload = currentPayload.copy(
            measuredAt = freshMeasuredAt(),
            spo2 = spo2,
        )
    }

    private fun payloadFromMetrics(metrics: DataPointContainer): HealthServicesPayload {
        val heartRate = metrics.getData(DataType.HEART_RATE_BPM)
            .lastOrNull()
            ?.value
            ?.roundToInt()
        val steps = metrics.getData(DataType.STEPS_TOTAL)
            ?.total
            ?.toInt()
        val calories = metrics.getData(DataType.CALORIES_TOTAL)
            ?.total
            ?.roundToOneDecimal()

        return HealthServicesPayload(
            measuredAt = freshMeasuredAt(),
            heartRate = heartRate,
            steps = steps,
            calories = calories,
            spo2 = lastSpo2,
            bodyTemp = currentPayload.bodyTemp,
            bloodPressureSystolic = null,
            bloodPressureDiastolic = null,
        )
    }

    private fun sendCollectedPayload() {
        if (isSending || !currentPayload.hasCollectedRequiredValues()) {
            return
        }

        val userId = BuildConfig.HEALTH_API_USER_ID.toLongOrNull()
        if (userId == null || userId <= 0L) {
            return
        }

        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return
        }

        val payloadForSend = currentPayload.copy(
            measuredAt = freshMeasuredAt(),
            bodyTemp = currentPayload.bodyTemp ?: DEFAULT_BODY_TEMP,
        )
        currentPayload = payloadForSend
        val requestBody = payloadForSend.toRequestBody(userId)
        val token = BuildConfig.HEALTH_API_TOKEN.trim()
        isSending = true

        Thread {
            runCatching {
                postHealthData(baseUrl, token, requestBody)
            }.onFailure {
                it.printStackTrace()
            }
            mainHandler.post { isSending = false }
        }.start()
    }

    private fun postHealthData(baseUrl: String, token: String, body: String) {
        val connection = URL("$baseUrl/health/data").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 36) {
            permissions += PERMISSION_READ_HEART_RATE
            permissions += PERMISSION_READ_OXYGEN_SATURATION
        } else {
            permissions += Manifest.permission.BODY_SENSORS
        }
        return permissions.toTypedArray()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == HEALTH_PERMISSION_REQUEST && hasRequiredPermissions()) {
            startHikingFromWatch()
        }
    }

    override fun onDestroy() {
        Wearable.getDataClient(this).removeListener(this)
        stopHikingFromWatch()
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
        mainHandler.removeCallbacks(fakeSpo2Runnable)
        exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback)
        scope.cancel()
        super.onDestroy()
    }

    private fun freshMeasuredAt(): String {
        return HealthServicesPayload.empty().measuredAt
    }

    companion object {
        private const val HEALTH_PERMISSION_REQUEST = 41
        private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val PERMISSION_READ_OXYGEN_SATURATION =
            "android.permission.health.READ_OXYGEN_SATURATION"
        private const val HEALTH_SEND_INTERVAL_MS = 3_000L
        private const val SPO2_TICK_MS = 1_000L
        private const val SPO2_REAL_REQUEST_INTERVAL_MS = 60_000L
        private const val DEFAULT_BODY_TEMP = 36.7
        private const val MAX_FAKE_SPO2 = 100
        private const val MIN_FAKE_SPO2 = 90

        private val DEFAULT_DATA_TYPES = setOf(
            DataType.HEART_RATE_BPM,
            DataType.STEPS_TOTAL,
            DataType.CALORIES_TOTAL,
        )
    }
}

private fun Double.roundToOneDecimal(): Double {
    return (this * 10.0).roundToInt() / 10.0
}

