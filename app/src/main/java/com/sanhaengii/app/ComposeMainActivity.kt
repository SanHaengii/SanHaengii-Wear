package com.sanhaengii.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.sanhaengii.app.ui.AlertScreen
import com.sanhaengii.app.ui.MainDashboard
import com.sanhaengii.app.ui.SosScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class ComposeMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val credentialStore by lazy { SecureCredentialStore(this) }
    private val locationProvider by lazy { OneShotLocationProvider(this) }
    private val emergencyRepository by lazy {
        EmergencyRepository(BuildConfig.HEALTH_API_BASE_URL, allowCleartext = BuildConfig.DEBUG)
    }
    private lateinit var mainViewModel: MainViewModel
    private var trackingService: HealthTrackingService? = null
    private var isTrackingServiceBound = false
    private var trackingStateJob: Job? = null
    private var trackingEventJob: Job? = null

    private val trackingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? HealthTrackingService.LocalBinder)?.service ?: return
            trackingService = service
            isTrackingServiceBound = true
            trackingStateJob?.cancel()
            trackingEventJob?.cancel()
            trackingStateJob = scope.launch {
                service.state.collect { state ->
                    if (!::mainViewModel.isInitialized) return@collect
                    mainViewModel.updateHikingActive(state.isActive)
                    mainViewModel.updatePaused(state.isPaused)
                    state.payload.heartRate?.let(mainViewModel::updateHeartRate)
                    state.pendingAnomaly?.let { anomaly ->
                        handleDetectedAnomaly(anomaly)
                        service.acknowledgeAnomaly(anomaly)
                    }
                }
            }
            trackingEventJob = scope.launch {
                service.events.collect { event ->
                    if (event is HealthTrackingEvent.Error) Log.w(TAG, event.message)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isTrackingServiceBound = false
            trackingService = null
            trackingStateJob?.cancel()
            trackingEventJob?.cancel()
        }
    }

    private val anomalyCountdownRunnable = object : Runnable {
        override fun run() {
            val finished = mainViewModel.tickCountdown()
            if (finished) {
                beginEmergencySend(EmergencyTrigger.AUTO_TIMEOUT)
            } else if (mainViewModel.emergencyState.phase == EmergencyPhase.COUNTDOWN) {
                mainHandler.postDelayed(this, COUNTDOWN_TICK_MS)
            }
        }
    }

    private val nodeCountPollRunnable = object : Runnable {
        override fun run() {
            pollConnectedNodeCount()
            mainHandler.postDelayed(this, NODE_COUNT_POLL_INTERVAL_MS)
        }
    }

    private val emergencyAckTimeoutRunnable = Runnable {
        if (mainViewModel.emergencyState.phase == EmergencyPhase.SENDING) {
            mainViewModel.markEmergencyFailed("구조 요청 확인 시간이 초과되었습니다")
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            mainViewModel = viewModel()
            MaterialTheme {
                val pagerState = rememberPagerState(pageCount = { 2 })
                if (mainViewModel.isEmergencyVisible) {
                    val phase = mainViewModel.emergencyState.phase
                    val canAct = phase == EmergencyPhase.COUNTDOWN || phase == EmergencyPhase.FAILED
                    AlertScreen(
                        message = mainViewModel.anomalyMessage,
                        isWarning = true,
                        countdown = mainViewModel.anomalyCountdown,
                        emergencySendState = mainViewModel.emergencySendState,
                        onConfirm = if (canAct) {{ confirmEmergency() }} else null,
                        onCancel = if (canAct) {{ cancelEmergency() }} else null,
                    )
                } else {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            0 -> MainDashboard(
                                bpm = mainViewModel.bpm,
                                eta = mainViewModel.eta,
                                distance = mainViewModel.distance,
                                connectedNodeCount = mainViewModel.connectedNodeCount,
                                lastReceivedAtMs = mainViewModel.lastReceivedAtMs,
                            )
                            1 -> SosScreen(
                                isHikingActive = mainViewModel.isHikingActive,
                                isPaused = mainViewModel.isPaused,
                                isSosReporting = mainViewModel.isSosReporting,
                                onHikingToggle = ::toggleHikingFromWatch,
                                onAbort = ::abortHikingFromWatch,
                                onSosClick = ::sendEmergencyManual,
                            )
                        }
                    }
                }
            }
        }

        Wearable.getDataClient(this).addListener(this)
        bindService(Intent(this, HealthTrackingService::class.java), trackingServiceConnection, Context.BIND_AUTO_CREATE)
        sweepExistingDataItems()
        mainHandler.post(nodeCountPollRunnable)
    }

    private fun sweepExistingDataItems() {
        scope.launch {
            runCatching {
                val dataItems = Wearable.getDataClient(this@ComposeMainActivity).dataItems.await()
                try {
                    for (item in dataItems) applyInboundData(item.uri.path, DataMapItem.fromDataItem(item).dataMap.toPlainMap())
                } finally {
                    dataItems.release()
                }
            }.onFailure { Log.w(TAG, "기존 DataItem 로드 실패", it) }
        }
    }

    override fun onDataChanged(dataEvents: com.google.android.gms.wearable.DataEventBuffer) {
        if (!::mainViewModel.isInitialized) return
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            applyInboundData(event.dataItem.uri.path, DataMapItem.fromDataItem(event.dataItem).dataMap.toPlainMap())
            mainViewModel.markDataReceived()
        }
    }

    private fun applyInboundData(path: String?, map: Map<String, Any?>) {
        when (path) {
            AUTH_PATH -> applyAuthPayload(parseAuthPayload(map))
            HIKE_STATE_PATH -> applyHikeStatePayload(parseHikeStatePayload(map))
            SOS_ACK_PATH -> applySosAckPayload(parseSosAckPayload(map))
        }
    }

    private fun applyAuthPayload(payload: AuthPayload) {
        if (payload.userId > 0 && payload.token.isNotBlank()) {
            credentialStore.save(WatchCredentials(payload.token, payload.userId.toLong()))
        } else {
            credentialStore.clear()
        }
    }

    private fun applyHikeStatePayload(payload: HikeStatePayload) {
        if (!::mainViewModel.isInitialized) return
        mainViewModel.updateEta(if (payload.etaMin > 0) "${payload.etaMin}분" else "-")
        mainViewModel.updateDistance(if (payload.remainKm > 0) String.format(Locale.ROOT, "%.2fkm", payload.remainKm) else "-")
        if (payload.active) {
            when {
                !mainViewModel.isHikingActive -> startHikingFromWatch()
                mainViewModel.isPaused && !payload.paused -> applyResumeLocal()
                !mainViewModel.isPaused && payload.paused -> applyPauseLocal()
            }
        } else if (mainViewModel.isHikingActive) {
            stopHikingFromWatch()
        }
    }

    private fun applySosAckPayload(payload: SosAckPayload) {
        if (!::mainViewModel.isInitialized) return
        val currentId = mainViewModel.emergencyState.alert?.requestId
        if (payload.id.isNotBlank() && currentId != null && payload.id != currentId) return
        if (mainViewModel.applyRemoteEmergencyStatus(payload.state)) {
            mainHandler.removeCallbacks(emergencyAckTimeoutRunnable)
            if (mainViewModel.emergencyState.phase == EmergencyPhase.SUCCESS) scheduleEmergencyReset()
        }
    }

    private fun toggleHikingFromWatch() {
        when {
            !mainViewModel.isHikingActive -> {
                startHikingFromWatch()
                publishHikeControl("resume")
            }
            !mainViewModel.isPaused -> {
                applyPauseLocal()
                publishHikeControl("pause")
            }
            else -> {
                applyResumeLocal()
                publishHikeControl("resume")
            }
        }
    }

    private fun abortHikingFromWatch() {
        publishHikeControl("abort")
        stopHikingFromWatch()
    }

    private fun startHikingFromWatch() {
        if (!hasHealthPermissions()) {
            requestPermissions(healthPermissions(), HEALTH_PERMISSION_REQUEST)
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, HealthTrackingService::class.java).setAction(HealthTrackingService.ACTION_START),
        )
        requestNotificationPermissionIfNeeded()
        requestLocationPermissionIfNeeded()
    }

    private fun applyPauseLocal() = sendTrackingAction(HealthTrackingService.ACTION_PAUSE)
    private fun applyResumeLocal() = sendTrackingAction(HealthTrackingService.ACTION_RESUME)
    private fun stopHikingFromWatch() = sendTrackingAction(HealthTrackingService.ACTION_STOP)

    private fun sendTrackingAction(action: String) {
        startService(Intent(this, HealthTrackingService::class.java).setAction(action))
    }

    private fun publishHikeControl(action: String) {
        scope.launch {
            runCatching {
                val request = buildHikeControlPayload(action, UUID.randomUUID().toString())
                    .toPutDataMapRequest(HIKE_CONTROL_PATH).asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@ComposeMainActivity).putDataItem(request).await()
            }.onFailure { Log.w(TAG, "산행 제어 전송 실패", it) }
        }
    }

    private fun handleDetectedAnomaly(anomaly: DetectedAnomaly) {
        val alert = EmergencyAlert(
            anomalyType = anomaly.type,
            message = anomaly.message,
            source = EmergencySource.LOCAL_SENSOR,
            requestId = UUID.randomUUID().toString(),
        )
        if (!mainViewModel.startEmergencyAlert(alert)) return
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainHandler.postDelayed(anomalyCountdownRunnable, COUNTDOWN_TICK_MS)
        vibrateAlert()
    }

    private fun sendEmergencyManual() {
        val alert = EmergencyAlert(
            anomalyType = AnomalyType.MANUAL_SOS,
            message = "사용자가 긴급 구조를 요청했습니다",
            source = EmergencySource.MANUAL_SOS,
            requestId = UUID.randomUUID().toString(),
        )
        if (!mainViewModel.startEmergencyAlert(alert, countdownSeconds = 0)) return
        beginEmergencySend(EmergencyTrigger.MANUAL_LONG_PRESS)
    }

    private fun confirmEmergency() {
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        beginEmergencySend(EmergencyTrigger.USER_CONFIRM)
    }

    private fun beginEmergencySend(trigger: EmergencyTrigger) {
        val alert = mainViewModel.beginEmergencySend(trigger) ?: return
        sendEmergencyRequest(alert, trigger)
    }

    private fun cancelEmergency() {
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainHandler.removeCallbacks(emergencyAckTimeoutRunnable)
        if (mainViewModel.cancelEmergency() != null) publishHikeControl("sos_cancel")
    }

    private fun sendEmergencyRequest(alert: EmergencyAlert, trigger: EmergencyTrigger) {
        val requestId = alert.requestId ?: UUID.randomUUID().toString()
        mainHandler.removeCallbacks(emergencyAckTimeoutRunnable)
        mainHandler.postDelayed(emergencyAckTimeoutRunnable, EMERGENCY_ACK_TIMEOUT_MS)
        scope.launch {
            val location = locationProvider.getLocation(LOCATION_TIMEOUT_MS)
            publishSosRequest(
                requestId,
                if (alert.source == EmergencySource.MANUAL_SOS) "watch_sos" else "watch_anomaly",
                location?.latitude,
                location?.longitude,
            )

            val credentials = credentialStore.load() ?: return@launch
            val body = runCatching {
                buildEmergencyRequestBody(
                    userId = credentials.userId,
                    eventType = if (alert.source == EmergencySource.MANUAL_SOS) "수동_긴급_호출" else "이상_징후",
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    triggeredBy = trigger.apiValue,
                    reason = alert.message,
                    requestId = requestId,
                )
            }.getOrElse {
                Log.w(TAG, "긴급 요청 payload 생성 실패", it)
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching { parseEmergencyResponse(emergencyRepository.postEmergency(body, credentials.token)) }.getOrNull()
            }
            when (result) {
                EmergencyResponseStatus.SUCCESS -> {
                    mainHandler.removeCallbacks(emergencyAckTimeoutRunnable)
                    if (mainViewModel.markEmergencySucceeded()) scheduleEmergencyReset()
                }
                EmergencyResponseStatus.FAILED -> Log.w(TAG, "직접 HTTP 긴급 요청 실패; 모바일 ACK 대기")
                EmergencyResponseStatus.QUEUED, null -> Unit
            }
        }
    }

    private suspend fun publishSosRequest(id: String, source: String, lat: Double?, lng: Double?) {
        runCatching {
            val request = buildSosRequestPayload(id, source, lat, lng)
                .toPutDataMapRequest(SOS_REQUEST_PATH).asPutDataRequest().setUrgent()
            Wearable.getDataClient(this@ComposeMainActivity).putDataItem(request).await()
        }.onFailure { Log.w(TAG, "SOS DataItem 전송 실패", it) }
    }

    private fun scheduleEmergencyReset() {
        val expectedAlert = mainViewModel.emergencyState.alert
        mainHandler.postDelayed({
            if (mainViewModel.emergencyState.phase == EmergencyPhase.SUCCESS &&
                mainViewModel.emergencyState.alert == expectedAlert
            ) mainViewModel.resetEmergency()
        }, EMERGENCY_SUCCESS_DISPLAY_MS)
    }

    private fun pollConnectedNodeCount() {
        if (!::mainViewModel.isInitialized) return
        scope.launch {
            runCatching { Wearable.getNodeClient(this@ComposeMainActivity).connectedNodes.await().size }
                .onSuccess(mainViewModel::updateConnectedNodeCount)
                .onFailure { Log.w(TAG, "노드 조회 실패", it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateAlert() {
        val pattern = longArrayOf(0, 300, 150, 300, 150, 300, 800, 300, 150, 300, 150, 300)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        }
    }

    private fun hasHealthPermissions(): Boolean = healthPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun healthPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 36) {
            permissions += PERMISSION_READ_HEART_RATE
            permissions += PERMISSION_READ_OXYGEN_SATURATION
        } else permissions += Manifest.permission.BODY_SENSORS
        return permissions.toTypedArray()
    }

    private fun requestLocationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == HEALTH_PERMISSION_REQUEST && hasHealthPermissions()) startHikingFromWatch()
    }

    override fun onDestroy() {
        Wearable.getDataClient(this).removeListener(this)
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainHandler.removeCallbacks(nodeCountPollRunnable)
        mainHandler.removeCallbacks(emergencyAckTimeoutRunnable)
        trackingStateJob?.cancel()
        trackingEventJob?.cancel()
        if (isTrackingServiceBound) {
            unbindService(trackingServiceConnection)
            isTrackingServiceBound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SanHaengiiWear"
        private const val AUTH_PATH = "/auth"
        private const val HIKE_STATE_PATH = "/hike/state"
        private const val SOS_ACK_PATH = "/sos/ack"
        private const val HIKE_CONTROL_PATH = "/hike/control"
        private const val SOS_REQUEST_PATH = "/sos/request"
        private const val HEALTH_PERMISSION_REQUEST = 41
        private const val LOCATION_PERMISSION_REQUEST = 42
        private const val NOTIFICATION_PERMISSION_REQUEST = 43
        private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val PERMISSION_READ_OXYGEN_SATURATION = "android.permission.health.READ_OXYGEN_SATURATION"
        private const val COUNTDOWN_TICK_MS = 1_000L
        private const val NODE_COUNT_POLL_INTERVAL_MS = 5_000L
        private const val LOCATION_TIMEOUT_MS = 5_000L
        private const val EMERGENCY_ACK_TIMEOUT_MS = 20_000L
        private const val EMERGENCY_SUCCESS_DISPLAY_MS = 3_000L
    }
}
