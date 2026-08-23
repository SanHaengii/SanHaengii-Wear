package com.sanhaengii.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEvent
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

data class HealthTrackingState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val payload: HealthServicesPayload = HealthServicesPayload.empty(),
    val sensorStatus: String = "대기 중",
    val pendingAnomaly: DetectedAnomaly? = null,
)

sealed interface HealthTrackingEvent {
    data class Error(val message: String) : HealthTrackingEvent
}

class HealthTrackingService : Service() {
    inner class LocalBinder : Binder() { val service: HealthTrackingService get() = this@HealthTrackingService }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val stepCounterSensor by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var currentState = HealthTrackingState()
    private val _state = MutableStateFlow(currentState)
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<HealthTrackingEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private val heartRateGate = AnomalyEpisodeGate()
    private val spo2Gate = AnomalyEpisodeGate()
    private var periodicSendJob: Job? = null
    private var isExerciseRunning = false
    private var stepCounterBaseline: Float? = null
    private var fallbackSteps = 0
    private var samsungSpo2Provider: SamsungSpo2Provider? = null
    private var lastSpo2: Double? = null
    private var lastSpo2MeasuredAtMs = 0L
    private var nextSpo2RequestAtMs = 0L

    private val stepCounterListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val total = event.values.firstOrNull() ?: return
            val baseline = stepCounterBaseline
            if (baseline == null) {
                stepCounterBaseline = total
                fallbackSteps = 0
            } else {
                fallbackSteps = (total - baseline).toInt().coerceAtLeast(0)
            }
            updatePayload { current ->
                val steps = maxOf(current.steps ?: 0, fallbackSteps)
                current.copy(
                    measuredAt = freshMeasuredAt(),
                    steps = steps,
                    calories = stableCalories(current.calories, null, steps),
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            serviceScope.launch {
                val metrics = update.latestMetrics
                val heartRate = metrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.roundToInt()
                val next = payloadFromMetrics(metrics, heartRate)
                updatePayload { it.mergeWith(next) }
                if (heartRate != null) evaluateHeartRate(heartRate, System.currentTimeMillis())
            }
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
        override fun onExerciseEventReceived(event: ExerciseEvent) = Unit
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) {
            _events.tryEmit(HealthTrackingEvent.Error("Health Services callback failed"))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restoreFreshSpo2()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_PAUSE -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("RestrictedApi", "WrongConstant")
    private fun startTracking() {
        if (currentState.isActive) return
        startForeground(NOTIFICATION_ID, createNotification("센서를 준비하고 있습니다"))
        publishState(
            HealthTrackingState(
                payload = HealthServicesPayload.empty().copy(steps = 0, spo2 = freshLastSpo2(), bodyTemp = null),
                sensorStatus = "센서 연결 중",
            )
        )
        startStepCounter()
        startSpo2Provider()

        serviceScope.launch {
            runCatching {
                val capabilities = exerciseClient.getCapabilitiesAsync().await()
                val dataTypes = if (ExerciseType.WALKING in capabilities.supportedExerciseTypes) {
                    DEFAULT_DATA_TYPES.filterTo(mutableSetOf()) {
                        it in capabilities.getExerciseTypeCapabilities(ExerciseType.WALKING).supportedDataTypes
                    }
                } else emptySet()
                exerciseClient.setUpdateCallback(exerciseUpdateCallback)
                val current = exerciseClient.getCurrentExerciseInfoAsync().await()
                if (current.exerciseTrackedStatus != ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) {
                    exerciseClient.startExerciseAsync(
                        ExerciseConfig(ExerciseType.WALKING, dataTypes, false, false)
                    ).await()
                }
            }.onSuccess {
                isExerciseRunning = true
                publishState(currentState.copy(isActive = true, isPaused = false, sensorStatus = sensorStatus()))
                updateNotification("산행 건강 데이터를 기록 중입니다")
                startPeriodicSending()
                requestSpo2IfDue(force = true)
            }.onFailure {
                _events.tryEmit(HealthTrackingEvent.Error(it.message ?: "운동 센서를 시작하지 못했습니다"))
                cleanupSensors()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun pauseTracking() {
        if (!currentState.isActive || currentState.isPaused) return
        periodicSendJob?.cancel()
        periodicSendJob = null
        publishState(currentState.copy(isPaused = true))
        updateNotification("산행 기록이 일시정지되었습니다")
    }

    private fun resumeTracking() {
        if (!currentState.isActive || !currentState.isPaused) return
        publishState(currentState.copy(isPaused = false))
        updateNotification("산행 건강 데이터를 기록 중입니다")
        startPeriodicSending()
    }

    private fun stopTracking() {
        periodicSendJob?.cancel()
        periodicSendJob = null
        publishState(currentState.copy(isActive = false, isPaused = false, sensorStatus = "중지됨", pendingAnomaly = null))
        heartRateGate.reset()
        spo2Gate.reset()
        serviceScope.launch {
            runCatching { exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback).await() }
            if (isExerciseRunning) runCatching { exerciseClient.endExerciseAsync().await() }
            isExerciseRunning = false
            cleanupSensors()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startPeriodicSending() {
        periodicSendJob?.cancel()
        periodicSendJob = serviceScope.launch {
            while (isActive && currentState.isActive && !currentState.isPaused) {
                delay(HEALTH_SEND_INTERVAL_MS)
                requestSpo2IfDue()
                sendCurrentPayload()
            }
        }
    }

    private suspend fun sendCurrentPayload() {
        val payload = stablePayloadForSend()
        if (!payload.hasCollectedRequiredValues()) return
        val bytes = buildHealthLivePayload(payload.heartRate ?: 0, payload.spo2, payload.bodyTemp, payload.steps ?: 0)
        runCatching {
            Wearable.getNodeClient(this).connectedNodes.await().forEach { node ->
                Wearable.getMessageClient(this).sendMessage(node.id, HEALTH_LIVE_PATH, bytes).await()
            }
        }.onFailure { _events.emit(HealthTrackingEvent.Error("건강 데이터 전송 실패: ${it.message}")) }
    }

    private fun startStepCounter() {
        stepCounterBaseline = null
        fallbackSteps = 0
        stepCounterSensor?.let { sensorManager?.registerListener(stepCounterListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    private fun startSpo2Provider() {
        if (samsungSpo2Provider != null) return
        val provider = SamsungSpo2Provider(
            context = this,
            mainHandler = mainHandler,
            onReading = { spo2, heartRate ->
                val now = System.currentTimeMillis()
                lastSpo2 = spo2
                lastSpo2MeasuredAtMs = now
                saveSpo2(spo2, now)
                nextSpo2RequestAtMs = now + SPO2_REQUEST_INTERVAL_MS
                updatePayload { current ->
                    current.copy(measuredAt = freshMeasuredAt(), heartRate = heartRate ?: current.heartRate, spo2 = spo2)
                }
                evaluateSpo2(spo2, now)
                if (heartRate != null) evaluateHeartRate(heartRate, now)
            },
            onStatus = { publishState(currentState.copy(sensorStatus = it)) },
            onFallbackNeeded = {
                // 가짜 수치 생성 없이 신선한 마지막 실측값 또는 null만 유지한다.
                nextSpo2RequestAtMs = System.currentTimeMillis() + SPO2_RETRY_INTERVAL_MS
                publishState(currentState.copy(sensorStatus = it))
            },
        )
        samsungSpo2Provider = provider
        if (!provider.start()) samsungSpo2Provider = null
    }

    private fun requestSpo2IfDue(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now < nextSpo2RequestAtMs) return
        val requested = samsungSpo2Provider?.requestMeasurement() == true
        nextSpo2RequestAtMs = now + if (requested) SPO2_REQUEST_INTERVAL_MS else SPO2_NOT_READY_RETRY_MS
    }

    fun requestSpo2Measurement(): Boolean {
        if (!currentState.isActive) return false
        startSpo2Provider()
        val requested = samsungSpo2Provider?.requestMeasurement() == true
        if (requested) nextSpo2RequestAtMs = System.currentTimeMillis() + SPO2_REQUEST_INTERVAL_MS
        return requested
    }

    fun acknowledgeAnomaly(anomaly: DetectedAnomaly) {
        if (currentState.pendingAnomaly == anomaly) publishState(currentState.copy(pendingAnomaly = null))
    }

    private fun evaluateHeartRate(value: Int, measuredAtMs: Long) {
        val anomaly = detectAnomaly(HealthServicesPayload.empty().copy(heartRate = value))
        handleAnomalyDecision(heartRateGate.evaluate(anomaly, measuredAtMs))
    }

    private fun evaluateSpo2(value: Double, measuredAtMs: Long) {
        val anomaly = detectAnomaly(HealthServicesPayload.empty().copy(spo2 = value))
        handleAnomalyDecision(spo2Gate.evaluate(anomaly, measuredAtMs))
    }

    private fun handleAnomalyDecision(decision: AnomalySampleDecision) {
        val anomaly = (decision as? AnomalySampleDecision.Accepted)?.anomaly ?: return
        if (currentState.pendingAnomaly != null) return
        publishState(currentState.copy(pendingAnomaly = anomaly))
        serviceScope.launch {
            runCatching {
                val request = buildAnomalyPayload(anomaly.type.wireValue, anomaly.message)
                    .toPutDataMapRequest(ANOMALY_PATH).asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@HealthTrackingService).putDataItem(request).await()
            }.onFailure { _events.emit(HealthTrackingEvent.Error("이상징후 전송 실패: ${it.message}")) }
        }
    }

    private fun payloadFromMetrics(metrics: DataPointContainer, heartRate: Int?): HealthServicesPayload {
        val current = currentState.payload
        val healthSteps = metrics.getData(DataType.STEPS_TOTAL)?.total?.toInt()
        val steps = maxOf(current.steps ?: 0, fallbackSteps, healthSteps ?: 0)
        val healthCalories = metrics.getData(DataType.CALORIES_TOTAL)?.total?.roundToOneDecimalLocal()
        return HealthServicesPayload(
            measuredAt = freshMeasuredAt(),
            heartRate = heartRate ?: current.heartRate,
            steps = steps,
            calories = stableCalories(current.calories, healthCalories, steps),
            spo2 = freshLastSpo2(),
            bodyTemp = null,
            bloodPressureSystolic = null,
            bloodPressureDiastolic = null,
        )
    }

    private fun stablePayloadForSend(): HealthServicesPayload {
        val current = currentState.payload
        val steps = maxOf(current.steps ?: 0, fallbackSteps)
        return current.copy(
            measuredAt = freshMeasuredAt(),
            steps = steps,
            calories = stableCalories(current.calories, null, steps),
            spo2 = freshLastSpo2(),
            bodyTemp = null,
        ).also { publishState(currentState.copy(payload = it)) }
    }

    private fun stableCalories(current: Double?, healthServices: Double?, steps: Int): Double? =
        listOfNotNull(current, healthServices, (steps * CALORIES_PER_STEP).takeIf { steps > 0 })
            .maxOrNull()?.roundToOneDecimalLocal()

    private fun updatePayload(update: (HealthServicesPayload) -> HealthServicesPayload) {
        publishState(currentState.copy(payload = update(currentState.payload)))
    }

    private fun publishState(next: HealthTrackingState) { currentState = next; _state.tryEmit(next) }
    private fun freshMeasuredAt(): String = HealthServicesPayload.empty().measuredAt
    private fun sensorStatus(): String = if (samsungSpo2Provider == null) {
        "SpO₂ 센서 미지원 · 체온 미측정"
    } else "Health Services · Samsung SpO₂ · 체온 미측정"

    private fun cleanupSensors() {
        sensorManager?.unregisterListener(stepCounterListener)
        stepCounterBaseline = null
        fallbackSteps = 0
        samsungSpo2Provider?.stop()
        samsungSpo2Provider = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun restoreFreshSpo2() {
        val measuredAt = preferences.getLong(PREF_LAST_SPO2_AT, 0L)
        if (System.currentTimeMillis() - measuredAt <= SPO2_MAX_AGE_MS) {
            preferences.getFloat(PREF_LAST_SPO2, Float.NaN).takeUnless { it.isNaN() }?.let {
                lastSpo2 = it.toDouble(); lastSpo2MeasuredAtMs = measuredAt
            }
        }
    }

    private fun freshLastSpo2(nowMs: Long = System.currentTimeMillis()): Double? =
        lastSpo2?.takeIf { nowMs - lastSpo2MeasuredAtMs <= SPO2_MAX_AGE_MS }

    private fun saveSpo2(spo2: Double, measuredAtMs: Long) {
        preferences.edit().putFloat(PREF_LAST_SPO2, spo2.toFloat()).putLong(PREF_LAST_SPO2_AT, measuredAtMs).apply()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "산행 건강 기록", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun createNotification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("SanHaengii 산행 기록")
        .setContentText(content)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, ComposeMainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(
            0, "중단",
            PendingIntent.getService(
                this, 1, Intent(this, HealthTrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        ).build()

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        periodicSendJob?.cancel()
        cleanupSensors()
        if (isExerciseRunning) {
            exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback)
            exerciseClient.endExerciseAsync()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.sanhaengii.app.action.START_TRACKING"
        const val ACTION_PAUSE = "com.sanhaengii.app.action.PAUSE_TRACKING"
        const val ACTION_RESUME = "com.sanhaengii.app.action.RESUME_TRACKING"
        const val ACTION_STOP = "com.sanhaengii.app.action.STOP_TRACKING"
        private const val HEALTH_LIVE_PATH = "/health/live"
        private const val ANOMALY_PATH = "/anomaly"
        private const val CHANNEL_ID = "hiking_health_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val HEALTH_SEND_INTERVAL_MS = 3_000L
        private const val SPO2_REQUEST_INTERVAL_MS = 60_000L
        private const val SPO2_NOT_READY_RETRY_MS = 15_000L
        private const val SPO2_RETRY_INTERVAL_MS = 180_000L
        private const val SPO2_MAX_AGE_MS = 15 * 60_000L
        private const val CALORIES_PER_STEP = 0.04
        private const val PREFS_NAME = "sanhaengii_sensor_cache"
        private const val PREF_LAST_SPO2 = "last_spo2"
        private const val PREF_LAST_SPO2_AT = "last_spo2_at"
        private val DEFAULT_DATA_TYPES = setOf(DataType.HEART_RATE_BPM, DataType.STEPS_TOTAL, DataType.CALORIES_TOTAL)
    }
}

private fun Double.roundToOneDecimalLocal(): Double = (this * 10.0).roundToInt() / 10.0
