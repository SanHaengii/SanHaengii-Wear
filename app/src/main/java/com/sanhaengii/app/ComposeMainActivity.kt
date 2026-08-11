@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.sanhaengii.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
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
import com.sanhaengii.app.ui.AlertScreen
import com.sanhaengii.app.ui.MainDashboard
import com.sanhaengii.app.ui.SosScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class ComposeMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var mainViewModel: MainViewModel
    private var currentPayload = HealthServicesPayload.empty()
    private var isExerciseRunning = false
    // 런타임 자격증명: 모바일 로그인 JWT+user_id를 Flask relay로 수신해 BuildConfig 값을 덮어씀 (재빌드 불필요)
    @Volatile private var runtimeToken: String? = null
    @Volatile private var runtimeUserId: String? = null
    // 이상징후 자동 구조 요청 후/취소 후 재트리거 억제 시각 (스팸 방지)
    private var anomalyCooldownUntilMs = 0L
    // 현재 워치가 따라가고 있는(동기화 중인) 산행 레코드 id. /hike/state payload에 레코드 id가
    // 없어 Data Layer 전환 이후 채워지지 않지만, abortHikingFromWatch()가 여전히 초기화한다.
    @Volatile private var syncedHikingRecordId: Long? = null
    // 모바일 앱에서 이상징후를 감지해 "anomaly" 신호를 보낸 경우 true
    // (취소 시 notifyHikeControl("sos_cancel")로 응답해야 함)
    @Volatile private var isMobileAnomalyAlert = false
    private var nextFakeSpo2 = 100
    private var lastSpo2: Double? = null
    private var lastHeartRate: Int? = null
    private var isFakeSpo2Active = false
    private var lastSamsungSpo2RequestAt = 0L
    private var nextSamsungSpo2RequestAt = 0L
    private var samsungSpo2Provider: SamsungSpo2Provider? = null
    private var stepCounterBaseline: Float? = null
    private var fallbackSteps = 0
    private var lastLoggedStepValue = -1
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val stepCounterSensor by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    private val stepCounterListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val totalSteps = event.values.firstOrNull() ?: return
            val baseline = stepCounterBaseline
            if (baseline == null) {
                stepCounterBaseline = totalSteps
                fallbackSteps = 0
            } else {
                fallbackSteps = (totalSteps - baseline).toInt().coerceAtLeast(0)
            }

            val stableSteps = maxOf(currentPayload.steps ?: 0, fallbackSteps)
            currentPayload = currentPayload.copy(
                measuredAt = freshMeasuredAt(),
                steps = stableSteps,
                calories = stableCalories(null, stableSteps),
            )
            logStepUpdate("Step counter fallback", fallbackSteps)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
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
            // 전송 게이트와 무관하게, 현재 수집된 생체값으로 이상징후를 항상 점검
            runLocalAnomalyCheck()
            mainHandler.postDelayed(this, HEALTH_SEND_INTERVAL_MS)
        }
    }

    private val anomalyCountdownRunnable = object : Runnable {
        override fun run() {
            val finished = mainViewModel.tickCountdown()
            if (finished) {
                autoSendEmergencyFromAnomaly()
            } else if (mainViewModel.isAnomalyDetected) {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val payload = payloadFromMetrics(update.latestMetrics)
            currentPayload = currentPayload.mergeWith(payload)
            currentPayload.heartRate?.let {
                lastHeartRate = it
                saveLastHeartRate(it)
                mainViewModel.updateHeartRate(it)
            }
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
                val pagerState = rememberPagerState(pageCount = { 2 })

                if (mainViewModel.isAnomalyDetected) {
                    val isSending = mainViewModel.emergencySendState == "sending"
                    AlertScreen(
                        message = mainViewModel.anomalyMessage,
                        isWarning = true,
                        countdown = if (isSending) null else mainViewModel.anomalyCountdown,
                        cancelLabel = if (mainViewModel.isMobileAnomalySource) "괜찮아요" else "취소",
                        emergencySendState = mainViewModel.emergencySendState,
                        // 전송 중엔 버튼 비활성 (중복 탭 방지)
                        onConfirm = if (isSending) null else {{ confirmAnomalyEmergency() }},
                        onCancel = if (isSending) null else {{ cancelAnomaly() }},
                    )
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
                                isPaused = mainViewModel.isPaused,
                                isSosReporting = mainViewModel.isSosReporting,
                                onHikingToggle = { toggleHikingFromWatch() },
                                onAbort = { abortHikingFromWatch() },
                                onSosClick = {
                                    mainViewModel.isSosReporting = true
                                    // Send manual emergency to backend /api/emergency
                                    sendEmergencyManual()
                                    // Also notify phone nodes for UI sync
                                    notifySosTriggered()
                                }
                            )
                        }
                    }
                }
            }
        }

        Wearable.getDataClient(this).addListener(this)

        // 저장된 자격증명/생체값 복원. 자격증명·산행상태·이상징후는 이제 모두
        // /auth, /hike/state, /sos/ack Data Layer 이벤트로 폰이 직접 밀어준다 (onDataChanged 참고).
        loadSavedToken()
        loadSavedHeartRate()
        loadSavedSpo2()
    }

    // SOS는 유실되면 안 되므로 (휘발성 메시지가 아닌) DataItem으로 발행한다.
    // 워치에 최근 GPS 고정값이 캐시돼 있지 않으므로 좌표는 null로 보낸다 —
    // 0.0 같은 가짜 좌표를 지어내면 구조대가 엉뚱한 곳으로 갈 수 있다.
    private fun notifySosTriggered() {
        scope.launch {
            try {
                val payload = buildSosRequestPayload(
                    id = java.util.UUID.randomUUID().toString(),
                    source = "watch_sos",
                    lat = null,
                    lng = null,
                )
                val req = payload.toPutDataMapRequest("/sos/request")
                    .asPutDataRequest()
                    .setUrgent()
                Wearable.getDataClient(this@ComposeMainActivity).putDataItem(req).await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // SOS 화면 5초 롱프레스 → 수동 긴급 신고
    private fun sendEmergencyManual() {
        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        val token = currentToken()
        if (baseUrl.isBlank()) return

        scope.launch {
            var lat = DEFAULT_EMERGENCY_LAT
            var lng = DEFAULT_EMERGENCY_LNG
            try {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val loc = getLocationWithTimeout(5000L)
                    if (loc != null) { lat = loc.latitude; lng = loc.longitude }
                } else {
                    requestPermissions(requiredPermissions(), HEALTH_PERMISSION_REQUEST)
                }
            } catch (_: Exception) {}

            val body = runCatching {
                buildEmergencyBody("수동_긴급_호출", lat, lng)
            }.getOrElse {
                println("[SOS] sendEmergencyManual 실패: ${it.message}")
                mainViewModel.resetSosReporting()
                return@launch
            }

            val success = withContext(Dispatchers.IO) { postEmergency(baseUrl, token, body) }
            if (success) {
                println("[SOS] 수동 긴급 신고 완료")
            } else {
                println("[SOS] 수동 긴급 신고 실패")
            }
            mainViewModel.resetSosReporting()
        }
    }

    // Suspend helper: request a single location update with timeout
    private suspend fun getLocationWithTimeout(timeoutMs: Long): android.location.Location? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                try {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    // Try last known first
                    val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                    for (p in providers) {
                        try {
                            val last = lm.getLastKnownLocation(p)
                            if (last != null) {
                                if (!cont.isCompleted) cont.resume(last)
                                return@suspendCancellableCoroutine
                            }
                        } catch (_: SecurityException) {
                        }
                    }

                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            if (!cont.isCompleted) {
                                cont.resume(location)
                            }
                        }

                        override fun onProviderDisabled(provider: String) {}
                        override fun onProviderEnabled(provider: String) {}
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    }

                    try {
                        lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                        lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                    } catch (se: SecurityException) {
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    cont.invokeOnCancellation {
                        try {
                            lm.removeUpdates(listener)
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    if (!cont.isCompleted) cont.resume(null)
                }
            }
        }
    }

    /**
     * 백엔드 /api/emergency 필수 필드를 항상 포함하는 JSON body 생성.
     *  - userId : 필수. 비어있으면 null이 아니라 예외를 던져 호출부에서 "failed" 처리.
     *  - eventType, location, timestamp : 항상 포함.
     *  - 추가 필드(triggeredBy, reason)는 옵션.
     */
    private fun buildEmergencyBody(
        eventType: String,
        lat: Double,
        lng: Double,
        triggeredBy: String? = null,
        reason: String? = null,
    ): String {
        val userIdVal = currentUserId().trim()
        check(userIdVal.isNotBlank()) {
            "userId가 비어있습니다. npm run sync-watch 실행 후 워치 앱을 재빌드하세요."
        }
        val timestamp = java.time.Instant.now().toString()
        return buildString {
            append("{")
            append("\"userId\":$userIdVal,")
            append("\"eventType\":\"$eventType\",")
            if (triggeredBy != null) append("\"triggered_by\":\"$triggeredBy\",")
            if (reason != null) append("\"reason\":${escapeJson(reason)},")
            append("\"location\":{\"lat\":$lat,\"lng\":$lng},")
            append("\"timestamp\":\"$timestamp\"")
            append("}")
        }
    }

    /** JSON 문자열 값의 제어문자·특수문자를 이스케이프합니다. */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun postEmergency(baseUrl: String, token: String, body: String): Boolean {
        println("[SOS] POST $baseUrl/api/emergency body=$body")
        val connection = URL("$baseUrl/api/emergency").openConnection() as HttpURLConnection
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
            val code = connection.responseCode
            // 성공·실패 무관하게 응답 바디 로깅 (422 원인 파악)
            val responseBody = try {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            println("[SOS] response $code: $responseBody")
            return code in 200..299
        } catch (e: Exception) {
            println("[SOS] exception: ${e.message}")
            return false
        } finally {
            connection.disconnect()
        }
    }

    override fun onDataChanged(dataEvents: com.google.android.gms.wearable.DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap.toPlainMap()
            when (event.dataItem.uri.path) {
                "/auth" -> applyAuthPayload(parseAuthPayload(map))
                "/hike/state" -> applyHikeStatePayload(parseHikeStatePayload(map))
                "/sos/ack" -> applySosAckPayload(parseSosAckPayload(map))
            }
        }
    }

    // /auth: 모바일 로그인 자격증명 수신. fetchWatchCredentials()가 하던 영구 저장을 그대로 수행해
    // SOS HTTP 폴백(postEmergency)이 폰 없이도 최신 토큰/user_id로 계속 동작하게 한다.
    private fun applyAuthPayload(payload: AuthPayload) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (payload.token.isNotBlank() && payload.token != runtimeToken) {
            runtimeToken = payload.token
            prefs.edit().putString(PREF_KEY_TOKEN, payload.token).apply()
            println("[AUTH] 모바일 토큰 수신·갱신 (…${payload.token.takeLast(6)})")
        }
        if (payload.userId > 0) {
            val userIdStr = payload.userId.toString()
            if (userIdStr != runtimeUserId) {
                runtimeUserId = userIdStr
                prefs.edit().putString(PREF_KEY_USER_ID, userIdStr).apply()
                println("[AUTH] 모바일 user_id 수신·갱신 ($userIdStr)")
            }
        }
    }

    // /hike/state: 모바일이 소유한 산행 상태를 워치에 반영. syncHikingStateFromServer()가 폴링해
    // 구동하던 것과 같은 전이(시작/일시정지/재개/종료)를 그대로 따르되, 상태를 GET하는 대신
    // 폰이 push한 값을 직접 사용한다. "anomaly" 상태 전이는 없앴다 — 이상징후 감지는 이제
    // 워치가 직접 수행해 /anomaly로 발행하므로, 폰이 산행 상태를 통해 알릴 필요가 없다.
    private fun applyHikeStatePayload(payload: HikeStatePayload) {
        mainViewModel.updateEta("${payload.etaMin}분")
        mainViewModel.updateDistance(String.format("%.2f", payload.remainKm) + "km")

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

    // /sos/ack: 폰이 SOS 처리 결과를 알려오면 전송 상태 UI(AlertScreen/SosScreen)에 반영한다.
    // 워치 어휘는 idle/sending/success/failed뿐이고 폰은 추가로 queued(모바일도 오프라인이라
    // 로컬 대기 중)를 보낼 수 있다. 알 수 없는 값은 무시 — UI 어떤 분기도 못 받는 상태로
    // 전이시키면 안 된다. queued는 아직 최종 상태가 아니므로 sending과 함께 SOS 리포팅 진행
    // 중으로 취급한다.
    private fun applySosAckPayload(payload: SosAckPayload) {
        val state = when (payload.state) {
            "sending", "success", "failed", "queued" -> payload.state
            else -> return
        }
        mainViewModel.emergencySendState = state
        if (state != "sending" && state != "queued") {
            mainViewModel.resetSosReporting()
        }
    }

    // 시작 / 정지(일시정지) / 재개 토글
    private fun toggleHikingFromWatch() {
        if (!mainViewModel.isHikingActive) {
            startHikingFromWatch()
            // 모바일에 산행 활성 상태를 알림(모바일에 레코드가 없으면 모바일 쪽에서 무시)
            notifyHikeControl("resume")
        } else if (!mainViewModel.isPaused) {
            pauseHikingFromWatch()
        } else {
            resumeHikingFromWatch()
        }
    }

    // 정지 = 일시정지 (세션 유지, 양쪽 동기화)
    private fun pauseHikingFromWatch() {
        applyPauseLocal()
        notifyHikeControl("pause")
    }

    private fun resumeHikingFromWatch() {
        applyResumeLocal()
        notifyHikeControl("resume")
    }

    // 일시정지 로컬 적용(전송 중단). 서버 PUT은 호출측에서 결정
    private fun applyPauseLocal() {
        mainViewModel.updatePaused(true)
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
    }

    private fun applyResumeLocal() {
        mainViewModel.updatePaused(false)
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
        mainHandler.postDelayed(periodicHealthSendRunnable, HEALTH_SEND_INTERVAL_MS)
    }

    // 중단하기 = 완전 종료(기록 저장 status=completed). 양쪽 종료
    private fun abortHikingFromWatch() {
        notifyHikeControl("abort")
        syncedHikingRecordId = null
        stopHikingFromWatch()
    }

    // /hike/control: 워치가 시작한 산행 제어(pause/resume/abort/sos_cancel)를 모바일에 알린다.
    // SOS 요청과 마찬가지로 유실되면 안 되므로 (휘발성 메시지가 아닌) DataItem으로 발행한다.
    private fun notifyHikeControl(action: String) {
        scope.launch {
            try {
                val payload = buildHikeControlPayload(
                    action = action,
                    id = java.util.UUID.randomUUID().toString(),
                )
                val req = payload.toPutDataMapRequest("/hike/control")
                    .asPutDataRequest()
                    .setUrgent()
                Wearable.getDataClient(this@ComposeMainActivity).putDataItem(req).await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("RestrictedApi", "WrongConstant")
    private fun startHikingFromWatch() {
        if (!hasRequiredPermissions()) {
            requestPermissions(requiredPermissions(), HEALTH_PERMISSION_REQUEST)
            return
        }

        loadSavedSpo2()
        loadSavedHeartRate()
        currentPayload = HealthServicesPayload.empty().copy(
            heartRate = lastHeartRate,
            spo2 = lastSpo2,
            steps = 0,
            bodyTemp = currentPayload.bodyTemp ?: DEFAULT_BODY_TEMP,
        )
        startStepCounterFallback()
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
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainViewModel.resetAnomaly()
        anomalyCooldownUntilMs = 0L
        mainViewModel.updateEta("-")
        mainViewModel.updateDistance("-")
        samsungSpo2Provider?.stop()
        samsungSpo2Provider = null
        isFakeSpo2Active = false
        lastSamsungSpo2RequestAt = 0L
        nextSamsungSpo2RequestAt = 0L
        stopStepCounterFallback()

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
        currentPayload = currentPayload.copy(bodyTemp = currentPayload.bodyTemp ?: DEFAULT_BODY_TEMP)
        logSensorStatus("Using default body_temp=$DEFAULT_BODY_TEMP C.")
    }

    private fun startSpo2Provider() {
        if (samsungSpo2Provider != null || isFakeSpo2Active) {
            return
        }

        val provider = SamsungSpo2Provider(
            context = this,
            mainHandler = mainHandler,
            onReading = { spo2, heartRate ->
                val stableHeartRate = heartRate ?: currentPayload.heartRate ?: lastHeartRate
                if (heartRate != null) {
                    lastHeartRate = heartRate
                    saveLastHeartRate(heartRate)
                }
                lastSpo2 = spo2
                currentPayload = currentPayload.copy(
                    measuredAt = freshMeasuredAt(),
                    heartRate = stableHeartRate,
                    spo2 = spo2,
                )
                currentPayload.heartRate?.let { mainViewModel.updateHeartRate(it) }
                saveLastSpo2(spo2)
                nextSamsungSpo2RequestAt = System.currentTimeMillis() + SPO2_REAL_REQUEST_INTERVAL_MS
                logSensorStatus("Samsung SpO2 measured: ${spo2.roundToInt()}%.")
            },
            onStatus = ::logSensorStatus,
            onFallbackNeeded = { message ->
                keepPreviousSpo2AfterFailure(message)
            },
        )
        samsungSpo2Provider = provider
        if (!provider.start()) {
            samsungSpo2Provider = null
            logSensorStatus("Samsung SpO2 provider could not start. Continuing without SpO2.")
        }
    }

    private fun keepPreviousSpo2AfterFailure(message: String) {
        val previousSpo2 = lastSpo2
        nextSamsungSpo2RequestAt = System.currentTimeMillis() + SPO2_RETRY_AFTER_FAILURE_MS
        if (previousSpo2 == null) {
            logSensorStatus("$message No previous SpO2 yet; retrying later.")
            return
        }

        currentPayload = currentPayload.copy(spo2 = previousSpo2)
        logSensorStatus("$message Keeping previous SpO2=${previousSpo2.roundToInt()}% and retrying later.")
    }

    private fun requestSamsungSpo2IfDue(force: Boolean = false) {
        if (isFakeSpo2Active) {
            return
        }

        if (samsungSpo2Provider == null) {
            startSpo2Provider()
        }

        val now = System.currentTimeMillis()
        if (!force && now < nextSamsungSpo2RequestAt) {
            return
        }

        val requested = samsungSpo2Provider?.requestMeasurement() == true
        lastSamsungSpo2RequestAt = now
        if (requested) {
            nextSamsungSpo2RequestAt = now + SPO2_REAL_REQUEST_INTERVAL_MS
        } else {
            nextSamsungSpo2RequestAt = now + SPO2_NOT_READY_RETRY_MS
        }
    }

    private fun startStepCounterFallback() {
        fallbackSteps = 0
        stepCounterBaseline = null
        lastLoggedStepValue = -1
        currentPayload = currentPayload.copy(steps = currentPayload.steps ?: 0)
        val sensor = stepCounterSensor
        if (sensor == null) {
            logSensorStatus("Step counter sensor is unavailable; using Health Services steps only.")
            return
        }

        sensorManager?.registerListener(stepCounterListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        logSensorStatus("Step counter fallback started.")
    }

    private fun stopStepCounterFallback() {
        sensorManager?.unregisterListener(stepCounterListener)
        stepCounterBaseline = null
        fallbackSteps = 0
        lastLoggedStepValue = -1
    }

    private fun logStepUpdate(source: String, steps: Int) {
        if (steps == lastLoggedStepValue) {
            return
        }
        if (steps <= 5 || steps % 10 == 0 || steps - lastLoggedStepValue >= 10) {
            lastLoggedStepValue = steps
            logSensorStatus("$source: steps=$steps")
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

    private fun logSensorStatus(message: String) {
        Log.d(SENSOR_LOG_TAG, message)
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
        if (heartRate != null) {
            lastHeartRate = heartRate
            saveLastHeartRate(heartRate)
        }
        val healthServicesSteps = metrics.getData(DataType.STEPS_TOTAL)
            ?.total
            ?.toInt()
        if (healthServicesSteps != null) {
            logStepUpdate("Health Services steps", healthServicesSteps)
        }
        val steps = maxOf(currentPayload.steps ?: 0, fallbackSteps, healthServicesSteps ?: 0)
        val healthServicesCalories = metrics.getData(DataType.CALORIES_TOTAL)
            ?.total
            ?.roundToOneDecimal()
        val calories = stableCalories(healthServicesCalories, steps)

        return HealthServicesPayload(
            measuredAt = freshMeasuredAt(),
            heartRate = heartRate ?: currentPayload.heartRate ?: lastHeartRate,
            steps = steps,
            calories = calories,
            spo2 = lastSpo2,
            bodyTemp = currentPayload.bodyTemp ?: DEFAULT_BODY_TEMP,
            bloodPressureSystolic = null,
            bloodPressureDiastolic = null,
        )
    }

    // 건강 샘플을 폰에 있는 모든 노드로 전송한다 (fire-and-forget: 표본 하나 유실은 허용).
    // 백엔드로의 직접 전송은 더 이상 워치 책임이 아니다 — 폰이 게이트웨이 역할을 한다.
    private fun sendCollectedPayload() {
        val payloadForSend = stablePayloadForSend()
        if (!payloadForSend.hasCollectedRequiredValues()) {
            return
        }

        currentPayload = payloadForSend
        val payload = buildHealthLivePayload(
            hr = payloadForSend.heartRate ?: 0,
            spo2 = payloadForSend.spo2 ?: 0.0,
            temp = payloadForSend.bodyTemp ?: DEFAULT_BODY_TEMP,
            steps = payloadForSend.steps ?: 0,
        )

        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@ComposeMainActivity).connectedNodes.await()
                nodes.forEach { node ->
                    Wearable.getMessageClient(this@ComposeMainActivity)
                        .sendMessage(node.id, "/health/live", payload)
                        .await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stablePayloadForSend(): HealthServicesPayload {
        val stableSteps = maxOf(currentPayload.steps ?: 0, fallbackSteps)
        return currentPayload.copy(
            measuredAt = freshMeasuredAt(),
            heartRate = currentPayload.heartRate ?: lastHeartRate,
            steps = stableSteps,
            calories = stableCalories(null, stableSteps),
            bodyTemp = currentPayload.bodyTemp ?: DEFAULT_BODY_TEMP,
        )
    }

    private fun stableCalories(healthServicesCalories: Double?, steps: Int): Double? {
        return listOfNotNull(
            currentPayload.calories,
            healthServicesCalories,
            estimateCaloriesFromSteps(steps).takeIf { steps > 0 },
        ).maxOrNull()?.roundToOneDecimal()
    }

    private fun estimateCaloriesFromSteps(steps: Int): Double {
        return (steps * CALORIES_PER_STEP).roundToOneDecimal()
    }

    // 전송 게이트와 무관하게 현재 수집된 생체값으로 이상징후를 점검한다.
    // 감지되면 (a) 기존 온워치 UI(진동+카운트다운)를 그대로 띄우고,
    // (b) 워치가 이상징후 감지의 단일 원천이므로 /anomaly DataItem을 폰에 발행한다.
    private fun runLocalAnomalyCheck() {
        if (mainViewModel.isAnomalyDetected) return
        if (System.currentTimeMillis() < anomalyCooldownUntilMs) return
        val message = detectAnomalyLocally(currentPayload) ?: return
        if (handleAnomalyDetected(message, null)) {
            publishAnomalyDataItem(message)
        }
    }

    // 이상징후 메시지 → /anomaly payload용 대략적인 타입 태그로 분류
    private fun anomalyTypeFor(message: String): String = when {
        message.contains("심박수 과부하") -> "hr_high"
        message.contains("심박수 이상 저하") -> "hr_low"
        message.contains("산소포화도") -> "spo2_low"
        message.contains("고열") -> "temp_high"
        message.contains("저체온") -> "temp_low"
        else -> "unknown"
    }

    // /anomaly DataItem 발행 (urgent). 유실보다 지연이 나으므로 DataClient 사용.
    private fun publishAnomalyDataItem(message: String) {
        scope.launch {
            try {
                val req = buildAnomalyPayload(anomalyTypeFor(message), message)
                    .toPutDataMapRequest("/anomaly")
                    .asPutDataRequest()
                    .setUrgent()
                Wearable.getDataClient(this@ComposeMainActivity).putDataItem(req).await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 이상징후를 트리거한다. 이미 감지 중이거나 쿨다운 중이면 아무 것도 하지 않고 false 반환.
    // 반환값으로 "실제로 새로 트리거됐는지"를 호출부(예: /anomaly 발행)가 판단할 수 있게 한다.
    private fun handleAnomalyDetected(message: String, sosRequestId: Int?): Boolean {
        if (mainViewModel.isAnomalyDetected) return false
        // 직전 신고/취소 직후 동일 이상값으로 즉시 재트리거 방지
        // (모바일 연동 이상징후는 isMobileAnomalyAlert 분기에서 쿨다운을 이미 0으로 리셋)
        if (System.currentTimeMillis() < anomalyCooldownUntilMs) return false
        mainViewModel.triggerAnomaly(message, sosRequestId)
        startAnomalyCountdown()
        // 진동으로 사용자 주의 환기 (화면을 보고 있지 않아도 감지 가능)
        vibrateAlert()
        return true
    }

    private fun startAnomalyCountdown() {
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainHandler.postDelayed(anomalyCountdownRunnable, 1_000L)
    }

    /**
     * 이상징후 알림 진동 패턴:
     *   짧게×3회(사용자 주의 환기) → 1초 대기 → 다시 짧게×3회
     * 총 약 2초간 진동.
     */
    @Suppress("DEPRECATION")
    private fun vibrateAlert() {
        val pattern = longArrayOf(0, 300, 150, 300, 150, 300, 800, 300, 150, 300, 150, 300)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            // 진동 기능 없는 기기에서 예외 무시
        }
    }

    // 사용자가 "신고" 버튼 → AlertScreen에 전송 상태 표시하며 구조 신고
    private fun confirmAnomalyEmergency() {
        if (mainViewModel.emergencySendState == "sending") return // 중복 탭 방지
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        val message = mainViewModel.anomalyMessage

        if (isMobileAnomalyAlert) {
            isMobileAnomalyAlert = false
            notifyHikeControl("sos_cancel") // 모바일 중복 신고 방지
        }

        // 즉시 "전송 중" 표시 (resetAnomaly 호출 안 함 → AlertScreen 유지)
        mainViewModel.emergencySendState = "sending"
        anomalyCooldownUntilMs = System.currentTimeMillis() + ANOMALY_COOLDOWN_MS

        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        val token = currentToken()

        scope.launch {
            if (baseUrl.isBlank()) {
                mainViewModel.emergencySendState = "failed"
                return@launch
            }

            var lat = DEFAULT_EMERGENCY_LAT
            var lng = DEFAULT_EMERGENCY_LNG
            try {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val loc = getLocationWithTimeout(5000L)
                    if (loc != null) { lat = loc.latitude; lng = loc.longitude }
                }
            } catch (_: Exception) {}

            val body = runCatching {
                buildEmergencyBody("이상_징후", lat, lng, "user_confirm", message)
            }.getOrElse {
                println("[SOS] buildEmergencyBody 실패: ${it.message}")
                mainViewModel.emergencySendState = "failed"
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                runCatching { postEmergency(baseUrl, token, body) }.getOrDefault(false)
            }

            mainViewModel.emergencySendState = if (success) "success" else "failed"
            if (success) {
                mainHandler.postDelayed({ mainViewModel.resetAnomaly() }, 3000L)
            }
        }
    }

    // 사용자가 "취소(괜찮아요)" 버튼 → 신고하지 않고 종료
    // 모바일 연동 이상징후인 경우: PUT "active" → 모바일이 감지해 긴급신고 취소
    private fun cancelAnomaly() {
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
        mainViewModel.resetAnomaly()
        // 취소 직후 동일 이상값으로 즉시 재알림되지 않도록 일정 시간 억제
        anomalyCooldownUntilMs = System.currentTimeMillis() + ANOMALY_COOLDOWN_MS
        if (isMobileAnomalyAlert) {
            isMobileAnomalyAlert = false
            // 모바일이 sos_cancel을 수신 → dismissAnomaly() → 긴급신고 취소
            notifyHikeControl("sos_cancel")
        }
    }

    // 카운트다운 0 → 자동 구조 프로토콜 실행
    // 모바일 연동 이상징후인 경우: 플래그만 초기화 (모바일 30초도 만료→동시 신고 허용)
    private fun autoSendEmergencyFromAnomaly() {
        val message = mainViewModel.anomalyMessage
        mainViewModel.resetAnomaly()
        if (isMobileAnomalyAlert) {
            isMobileAnomalyAlert = false
        }
        sendEmergencyForAnomaly(message, "auto_timeout")
    }

    // /api/emergency 직접 POST (30초 자동 타임아웃 / auto_timeout)
    private fun sendEmergencyForAnomaly(reason: String, triggeredBy: String) {
        anomalyCooldownUntilMs = System.currentTimeMillis() + ANOMALY_COOLDOWN_MS
        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        val token = currentToken()
        if (baseUrl.isBlank()) return

        scope.launch {
            var lat = DEFAULT_EMERGENCY_LAT
            var lng = DEFAULT_EMERGENCY_LNG
            try {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val loc = getLocationWithTimeout(5000L)
                    if (loc != null) { lat = loc.latitude; lng = loc.longitude }
                }
            } catch (_: Exception) {}

            val body = runCatching {
                buildEmergencyBody("이상_징후", lat, lng, triggeredBy, reason)
            }.getOrElse {
                println("[SOS] sendEmergencyForAnomaly 실패: ${it.message}")
                return@launch
            }

            withContext(Dispatchers.IO) {
                runCatching { postEmergency(baseUrl, token, body) }
                    .onFailure { println("[SOS] 전송 실패: ${it.message}") }
            }
        }
    }

    // 네트워크 호출에 사용할 유효 토큰: 런타임(모바일 수신) 우선, 없으면 빌드 시 주입된 토큰
    private fun currentToken(): String {
        val rt = runtimeToken?.trim()
        return if (!rt.isNullOrBlank()) rt else BuildConfig.HEALTH_API_TOKEN.trim()
    }

    // 생체데이터 적재/구조요청에 사용할 user_id: 런타임(모바일 로그인) 우선, 없으면 BuildConfig
    private fun currentUserId(): String {
        val ru = runtimeUserId?.trim()
        return if (!ru.isNullOrBlank()) ru else BuildConfig.HEALTH_API_USER_ID.trim()
    }

    // 앱 재시작 시 마지막으로 받은 자격증명을 복원
    private fun loadSavedToken() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(PREF_KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { runtimeToken = it }
        prefs.getString(PREF_KEY_USER_ID, null)?.takeIf { it.isNotBlank() }?.let { runtimeUserId = it }
    }

    private fun loadSavedSpo2() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PREF_KEY_LAST_SPO2)) {
            return
        }

        val savedSpo2 = prefs.getFloat(PREF_KEY_LAST_SPO2, Float.NaN)
        if (!savedSpo2.isNaN()) {
            lastSpo2 = savedSpo2.toDouble()
            currentPayload = currentPayload.copy(spo2 = lastSpo2)
            logSensorStatus("Restored previous SpO2=${savedSpo2.roundToInt()}%.")
        }
    }

    private fun loadSavedHeartRate() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHeartRate = prefs.getInt(PREF_KEY_LAST_HEART_RATE, -1)
        if (savedHeartRate > 0) {
            lastHeartRate = savedHeartRate
            currentPayload = currentPayload.copy(heartRate = currentPayload.heartRate ?: savedHeartRate)
            if (::mainViewModel.isInitialized) {
                mainViewModel.updateHeartRate(savedHeartRate)
            }
            logSensorStatus("Restored previous heart_rate=$savedHeartRate bpm.")
        }
    }

    private fun saveLastSpo2(spo2: Double) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PREF_KEY_LAST_SPO2, spo2.toFloat())
            .apply()
    }

    private fun saveLastHeartRate(heartRate: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_KEY_LAST_HEART_RATE, heartRate)
            .apply()
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        // Include location so we can attach best-effort last-known-location to manual SOS
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
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
        mainHandler.removeCallbacks(anomalyCountdownRunnable)
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
        private const val PREFS_NAME = "sanhaengii_watch_prefs"
        private const val PREF_KEY_TOKEN = "health_api_token"
        private const val PREF_KEY_USER_ID = "health_api_user_id"
        private const val PREF_KEY_LAST_SPO2 = "last_spo2"
        private const val PREF_KEY_LAST_HEART_RATE = "last_heart_rate"
        private const val ANOMALY_COOLDOWN_MS = 60_000L
        // 백엔드 /api/emergency는 location 필수. GPS 실패 시 폴백 좌표(모바일 앱과 동일)
        private const val DEFAULT_EMERGENCY_LAT = 37.557999
        private const val DEFAULT_EMERGENCY_LNG = 127.007993
        private const val SPO2_TICK_MS = 1_000L
        private const val SPO2_REAL_REQUEST_INTERVAL_MS = 60_000L
        private const val SPO2_NOT_READY_RETRY_MS = 15_000L
        private const val SPO2_RETRY_AFTER_FAILURE_MS = 180_000L
        private const val DEFAULT_BODY_TEMP = 36.7
        private const val CALORIES_PER_STEP = 0.04
        private const val MAX_FAKE_SPO2 = 100
        private const val MIN_FAKE_SPO2 = 90
        private const val SENSOR_LOG_TAG = "WearHealthSender"

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

