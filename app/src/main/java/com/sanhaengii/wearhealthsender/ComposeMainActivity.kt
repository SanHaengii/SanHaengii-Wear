@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.sanhaengii.wearhealthsender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
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
    // 런타임 자격증명: 모바일 로그인 JWT+user_id를 Flask relay로 수신해 BuildConfig 값을 덮어씀 (재빌드 불필요)
    @Volatile private var runtimeToken: String? = null
    @Volatile private var runtimeUserId: String? = null
    // 이상징후 자동 구조 요청 후/취소 후 재트리거 억제 시각 (스팸 방지)
    private var anomalyCooldownUntilMs = 0L
    // 모바일이 소유한 산행 레코드 id (상태 PUT 대상). 서버 폴링으로 채워짐
    @Volatile private var activeHikingRecordId: Long? = null
    // 현재 워치가 따라가고 있는(동기화 중인) 산행 레코드 id
    @Volatile private var syncedHikingRecordId: Long? = null
    // 모바일 앱에서 이상징후를 감지해 "anomaly" 신호를 보낸 경우 true
    // (취소 시 putHikingStatus("active")로 응답해야 함)
    @Volatile private var isMobileAnomalyAlert = false
    private var hikingStartedAtMs = 0L
    private var totalHikingMinutes = 0
    private var totalHikingDistanceKm = 0.0

    private val hikingStatusRunnable = object : Runnable {
        override fun run() {
            if (mainViewModel.isHikingActive) {
                // 1) 마지막 동기화 기준점으로 분 단위 카운트다운 (polling 사이에도 줄어듦)
                refreshEtaFromElapsed()
                // 2) 모바일 ETA 알고리즘 최신값으로 재동기화
                fetchRelayStatus()
                mainHandler.postDelayed(this, HIKING_STATUS_INTERVAL_MS)
            }
        }
    }
    // 모바일 로그인 토큰을 주기적으로 Flask relay에서 받아옴 (산행 여부와 무관하게 상시 동작)
    private val credentialSyncRunnable = object : Runnable {
        override fun run() {
            fetchWatchCredentials()
            mainHandler.postDelayed(this, CREDENTIAL_SYNC_INTERVAL_MS)
        }
    }
    // 모바일 산행 상태(active/paused/completed)를 상시 폴링해 워치에 반영 (시작/정지/중단 동기화)
    private val mobileHikingSyncRunnable = object : Runnable {
        override fun run() {
            syncHikingStateFromServer()
            mainHandler.postDelayed(this, MOBILE_SYNC_INTERVAL_MS)
        }
    }
    // DB 최신 건강 데이터를 상시 폴링해 이상징후 감지 → 워치에 AlertScreen 표시
    // 산행 여부와 무관하게 항상 동작 (모바일 WatchHealthContext 역할을 워치에서 직접 수행)
    private val healthAnomalyPollingRunnable = object : Runnable {
        override fun run() {
            pollHealthDataForAnomaly()
            mainHandler.postDelayed(this, HEALTH_ANOMALY_POLL_INTERVAL_MS)
        }
    }
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
                val pagerState = rememberPagerState(pageCount = { 3 })

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

        // 저장된 토큰 복원 후, 모바일 로그인 토큰 상시 동기화 시작
        loadSavedToken()
        mainHandler.post(credentialSyncRunnable)
        // 모바일 산행 상태 상시 동기화 시작 (모바일 시작/정지/중단 → 워치 반영)
        mainHandler.post(mobileHikingSyncRunnable)
        // DB 건강 데이터 상시 폴링 → 이상징후 워치 알림 (산행 여부 무관)
        mainHandler.postDelayed(healthAnomalyPollingRunnable, 5_000L)
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

    // 시작 / 정지(일시정지) / 재개 토글
    private fun toggleHikingFromWatch() {
        if (!mainViewModel.isHikingActive) {
            startHikingFromWatch()
            // 모바일 레코드가 이미 있으면 active로 동기화(없으면 id 없어 no-op)
            putHikingStatus("active")
        } else if (!mainViewModel.isPaused) {
            pauseHikingFromWatch()
        } else {
            resumeHikingFromWatch()
        }
    }

    // 정지 = 일시정지 (세션 유지, 양쪽 동기화)
    private fun pauseHikingFromWatch() {
        applyPauseLocal()
        putHikingStatus("paused")
    }

    private fun resumeHikingFromWatch() {
        applyResumeLocal()
        putHikingStatus("active")
    }

    // 일시정지 로컬 적용(전송 중단·ETA 카운트다운 정지). 서버 PUT은 호출측에서 결정
    private fun applyPauseLocal() {
        mainViewModel.updatePaused(true)
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
        mainHandler.removeCallbacks(hikingStatusRunnable)
    }

    private fun applyResumeLocal() {
        mainViewModel.updatePaused(false)
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
        mainHandler.postDelayed(periodicHealthSendRunnable, HEALTH_SEND_INTERVAL_MS)
        mainHandler.removeCallbacks(hikingStatusRunnable)
        mainHandler.postDelayed(hikingStatusRunnable, HIKING_STATUS_INTERVAL_MS)
    }

    // 중단하기 = 완전 종료(기록 저장 status=completed). 양쪽 종료
    private fun abortHikingFromWatch() {
        putHikingStatus("completed")
        syncedHikingRecordId = null
        stopHikingFromWatch()
    }

    // 산행 레코드 status를 백엔드에 PUT (active/paused/completed). recordId 없으면 무시
    private fun putHikingStatus(status: String) {
        val recordId = activeHikingRecordId ?: return
        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) return
        val token = currentToken()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = URL("$baseUrl/data/hiking_records/$recordId")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write("""{"status":"$status"}""".toByteArray(Charsets.UTF_8)) }
                connection.responseCode
                connection.disconnect()
            }
        }
    }

    // 모바일 산행 상태를 폴링해 워치에 반영 (시작/정지/중단 동기화)
    /**
     * DB의 최신 건강 데이터(/health/data/latest)를 10초마다 폴링해 이상징후를 감지합니다.
     * 산행 여부와 무관하게 항상 동작. 모바일 WatchHealthContext와 동일한 임계값 사용.
     * 감지 시 → 진동 + AlertScreen 표시 + 30초 카운트다운 → /api/emergency 자동 신고.
     */
    private fun pollHealthDataForAnomaly() {
        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        val token = currentToken()
        if (baseUrl.isBlank() || token.isBlank()) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = URL("$baseUrl/health/data/latest")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                val code = connection.responseCode
                if (code !in 200..299) { connection.disconnect(); return@runCatching }
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()

                // 응답 파싱 → HealthServicesPayload로 변환
                val json = org.json.JSONObject(text)
                val data = if (json.has("data") && !json.isNull("data")) json.getJSONObject("data") else json
                val hr   = if (data.has("heart_rate") && !data.isNull("heart_rate"))
                    data.getDouble("heart_rate").toInt() else null
                val spo2 = if (data.has("spo2") && !data.isNull("spo2"))
                    data.getDouble("spo2") else null
                val temp = if (data.has("body_temp") && !data.isNull("body_temp"))
                    data.getDouble("body_temp") else null

                val payload = HealthServicesPayload(
                    measuredAt = "",
                    heartRate = hr,
                    spo2 = spo2,
                    bodyTemp = temp,
                    steps = null,
                    calories = null,
                    bloodPressureSystolic = null,
                    bloodPressureDiastolic = null,
                )
                val message = detectAnomalyLocally(payload) ?: return@runCatching

                println("[Health] 이상징후 감지: $message (HR=$hr, SpO2=$spo2, Temp=$temp)")
                mainHandler.post { handleAnomalyDetected(message, null) }
            }.onFailure {
                // 네트워크 실패는 조용히 무시 (폴러가 계속 실행되도록)
            }
        }
    }

    private fun syncHikingStateFromServer() {
        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        val userId = currentUserId().toLongOrNull() ?: return
        if (baseUrl.isBlank()) return
        val token = currentToken()

        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = URL("$baseUrl/data/hiking_records/filter?select=*&limit=20")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write("""{"user_id":$userId}""".toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) { connection.disconnect(); return@runCatching }
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()

                val records = parseHikingRecords(text) ?: return@runCatching
                // 진행 중(active/paused/anomaly) 레코드 중 가장 최근 1건
                val ongoing = records
                    .filter {
                        val s = it.optString("status")
                        s == "active" || s == "paused" || s == "anomaly"
                    }
                    .maxByOrNull { it.optString("started_at", "") }

                mainHandler.post {
                    if (ongoing != null) {
                        val recordId = ongoing.optLong("id").takeIf { it > 0 }
                        if (recordId != null) {
                            activeHikingRecordId = recordId
                            syncedHikingRecordId = recordId
                        }
                        when (ongoing.optString("status")) {
                            "active" -> {
                                if (!mainViewModel.isHikingActive) {
                                    // 모바일에서 산행 시작 → 워치 자동 반영
                                    startHikingFromWatch()
                                } else if (mainViewModel.isPaused) {
                                    // 모바일에서 재개됨
                                    applyResumeLocal()
                                }
                            }
                            "paused" -> {
                                if (mainViewModel.isHikingActive && !mainViewModel.isPaused) {
                                    // 모바일에서 일시정지됨
                                    applyPauseLocal()
                                }
                            }
                            "anomaly" -> {
                                // 모바일에서 이상징후 감지 → 워치에 알림 표시
                                if (!mainViewModel.isAnomalyDetected) {
                                    isMobileAnomalyAlert = true
                                    mainViewModel.isMobileAnomalySource = true
                                    // 모바일 연동 알림은 쿨다운 무시 (모바일이 이미 독립적으로 관리)
                                    anomalyCooldownUntilMs = 0L
                                    handleAnomalyDetected(
                                        "⚠️ 이상징후 감지됨\n괜찮으시면 취소를 눌러주세요",
                                        null
                                    )
                                }
                            }
                        }
                    } else {
                        // 진행 중 레코드 없음: 따라가던 산행이 종료(중단/완료)된 것으로 보고 워치도 종료
                        if (syncedHikingRecordId != null && mainViewModel.isHikingActive) {
                            stopHikingFromWatch()
                        }
                        syncedHikingRecordId = null
                        activeHikingRecordId = null
                    }
                }
            }.onFailure { /* 네트워크 실패 시 무시 */ }
        }
    }

    @SuppressLint("RestrictedApi", "WrongConstant")
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
                    fetchHikingStatusAndStartCountdown()
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
        mainHandler.removeCallbacks(hikingStatusRunnable)
        mainViewModel.resetAnomaly()
        anomalyCooldownUntilMs = 0L
        hikingStartedAtMs = 0L
        totalHikingMinutes = 0
        totalHikingDistanceKm = 0.0
        mainViewModel.updateEta("-")
        mainViewModel.updateDistance("-")
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

        val userId = currentUserId().toLongOrNull()
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
        val token = currentToken()
        isSending = true

        Thread {
            val responseBody = runCatching {
                postHealthDataWithResponse(baseUrl, token, requestBody)
            }.getOrNull()

            mainHandler.post {
                isSending = false
                // 백엔드 응답의 is_anomaly 기반 감지 (토큰 유효 시).
                // 로컬 폴백 감지는 runLocalAnomalyCheck()가 게이트 없이 매 주기 수행한다.
                if (!mainViewModel.isAnomalyDetected && responseBody != null) {
                    handleHealthDataResponse(responseBody, payloadForSend)
                }
            }
        }.start()
    }

    private fun postHealthDataWithResponse(baseUrl: String, token: String, body: String): String {
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
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    // 백엔드 /health/data 응답 파싱 → 이상 감지 시 구조 프로토콜 트리거
    private fun handleHealthDataResponse(responseBody: String, payload: HealthServicesPayload) {
        val (isAnomaly, sosRequestId) = parseHealthDataResponse(responseBody)
        if (isAnomaly) {
            val message = detectAnomalyLocally(payload) ?: "생체 이상 징후 감지"
            handleAnomalyDetected(message, sosRequestId)
        }
    }

    // 전송 게이트와 무관하게 현재 수집된 생체값으로 이상징후를 점검한다.
    // 감지되면 자동으로 구조 프로토콜(30초 카운트다운 → /api/emergency)을 트리거한다.
    private fun runLocalAnomalyCheck() {
        if (mainViewModel.isAnomalyDetected) return
        if (System.currentTimeMillis() < anomalyCooldownUntilMs) return
        val message = detectAnomalyLocally(currentPayload) ?: return
        handleAnomalyDetected(message, null)
    }

    private fun handleAnomalyDetected(message: String, sosRequestId: Int?) {
        if (mainViewModel.isAnomalyDetected) return
        // 직전 신고/취소 직후 동일 이상값으로 즉시 재트리거 방지
        // (모바일 연동 이상징후는 isMobileAnomalyAlert 분기에서 쿨다운을 이미 0으로 리셋)
        if (System.currentTimeMillis() < anomalyCooldownUntilMs) return
        mainViewModel.triggerAnomaly(message, sosRequestId)
        startAnomalyCountdown()
        // 진동으로 사용자 주의 환기 (화면을 보고 있지 않아도 감지 가능)
        vibrateAlert()
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
            putHikingStatus("active") // 모바일 중복 신고 방지
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
            // 모바일 폴러가 "active"를 감지 → dismissAnomaly() → 긴급신고 취소
            putHikingStatus("active")
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

    private fun refreshEtaFromElapsed() {
        if (totalHikingMinutes > 0 && hikingStartedAtMs > 0) {
            val elapsedMinutes = (System.currentTimeMillis() - hikingStartedAtMs) / 60_000L
            val remaining = (totalHikingMinutes - elapsedMinutes).coerceAtLeast(0L)
            mainViewModel.updateEta("${remaining}분")
        }
    }

    // 모바일 내비게이션의 실시간 남은 ETA/거리를 Flask 중계 서버에서 조회합니다.
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

    // Flask relay에서 모바일이 push한 최신 user_id+JWT를 받아 런타임/영구 저장에 반영
    private fun fetchWatchCredentials() {
        val trailUrl = BuildConfig.TRAIL_API_BASE_URL.trim().trimEnd('/')
        if (trailUrl.isBlank()) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = URL("$trailUrl/api/watch-credentials/latest")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code !in 200..299) { connection.disconnect(); return@runCatching }
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()

                val obj = JSONObject(text)
                val token = obj.optString("token", "")
                val userId = if (obj.isNull("user_id")) "" else obj.opt("user_id").toString()
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (token.isNotBlank() && token != runtimeToken) {
                    runtimeToken = token
                    prefs.edit().putString(PREF_KEY_TOKEN, token).apply()
                    println("[CRED] 모바일 토큰 수신·갱신 (…${token.takeLast(6)})")
                }
                if (userId.isNotBlank() && userId != "null" && userId != runtimeUserId) {
                    runtimeUserId = userId
                    prefs.edit().putString(PREF_KEY_USER_ID, userId).apply()
                    println("[CRED] 모바일 user_id 수신·갱신 ($userId)")
                }
            }.onFailure { /* Flask 미실행 시 무시, 기존 자격증명 유지 */ }
        }
    }

    // 배포된 Railway에서 모바일이 갱신한 active hiking_record의 '잔여 ETA/거리'를 읽어 워치에 반영.
    // (모바일 LiveMapScreen이 updateHikingProgress로 duration_minutes=잔여분, distance_km=잔여km를 10초마다 갱신)
    private fun fetchRelayStatus() {
        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        val token = currentToken()
        val userId = currentUserId().toLongOrNull() ?: return
        if (baseUrl.isBlank()) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = URL("$baseUrl/data/hiking_records/filter?select=*&limit=20")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write("""{"user_id":$userId}""".toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) { connection.disconnect(); return@runCatching }
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()

                val records = parseHikingRecords(text) ?: return@runCatching
                // active 중 가장 최근 started_at 1건 선택
                val active = records.filter { it.optString("status") == "active" }
                    .maxByOrNull { it.optString("started_at", "") } ?: return@runCatching

                // 상태 PUT 대상 레코드 id 캡처
                active.optLong("id").takeIf { it > 0 }?.let { activeHikingRecordId = it }

                val etaMin = active.optDouble("duration_minutes", Double.NaN)
                val remainKm = active.optDouble("distance_km", Double.NaN)

                mainHandler.post {
                    if (!etaMin.isNaN() && etaMin >= 0) {
                        // 모바일 알고리즘의 '남은 시간'을 새 카운트다운 기준점으로 재설정
                        totalHikingMinutes = etaMin.toInt()
                        hikingStartedAtMs = System.currentTimeMillis()
                        mainViewModel.updateEta("${etaMin.toLong()}분")
                    }
                    if (!remainKm.isNaN() && remainKm >= 0) {
                        totalHikingDistanceKm = remainKm
                        mainViewModel.updateDistance(String.format("%.2f", remainKm) + "km")
                    }
                }
            }.onFailure { /* 네트워크 실패 시 무시, 로컬 카운트다운 유지 */ }
        }
    }

    // /data/hiking_records[/filter] 응답(배열 또는 {data|rows:[...]})을 JSONObject 리스트로 파싱
    private fun parseHikingRecords(text: String): List<JSONObject>? {
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) {
            try {
                val obj = JSONObject(text)
                val arr = obj.optJSONArray("data") ?: obj.optJSONArray("rows") ?: return null
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } catch (_: Exception) { null }
        }
    }

    private fun fetchHikingStatusAndStartCountdown() {
        val baseUrl = BuildConfig.HEALTH_API_BASE_URL.trim().trimEnd('/')
        val token = currentToken()
        val userId = currentUserId().toLongOrNull() ?: return
        if (baseUrl.isBlank()) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = URL("$baseUrl/data/hiking_records/filter?select=*&limit=20")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write("""{"user_id":$userId}""".toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) { connection.disconnect(); return@runCatching }
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()

                val records = parseHikingRecords(text) ?: return@runCatching
                val active = records.filter { it.optString("status") == "active" }
                    .maxByOrNull { it.optString("started_at", "") }
                    ?: return@runCatching

                val distanceKm = active.optDouble("distance_km", 0.0)
                val durationMin = active.optInt("duration_minutes", 0)
                val startedAt = active.optString("started_at", "")

                mainHandler.post {
                    if (distanceKm > 0) {
                        totalHikingDistanceKm = distanceKm
                        mainViewModel.updateDistance(String.format("%.1f", distanceKm) + "km")
                    }
                    if (durationMin > 0) totalHikingMinutes = durationMin
                    if (startedAt.isNotEmpty()) {
                        hikingStartedAtMs = try {
                            java.time.Instant.parse(startedAt).toEpochMilli()
                        } catch (_: Exception) { System.currentTimeMillis() }
                    }
                    refreshEtaFromElapsed()
                    fetchRelayStatus() // 모바일 실시간 값으로 즉시 덮어씌우기 시도
                    mainHandler.removeCallbacks(hikingStatusRunnable)
                    mainHandler.postDelayed(hikingStatusRunnable, HIKING_STATUS_INTERVAL_MS)
                }
            }.onFailure { it.printStackTrace() }
        }
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
        mainHandler.removeCallbacks(hikingStatusRunnable)
        mainHandler.removeCallbacks(credentialSyncRunnable)
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
        private const val HIKING_STATUS_INTERVAL_MS = 10_000L
        private const val CREDENTIAL_SYNC_INTERVAL_MS = 15_000L
        private const val MOBILE_SYNC_INTERVAL_MS = 7_000L
        private const val HEALTH_ANOMALY_POLL_INTERVAL_MS = 10_000L
        private const val PREFS_NAME = "sanhaengii_watch_prefs"
        private const val PREF_KEY_TOKEN = "health_api_token"
        private const val PREF_KEY_USER_ID = "health_api_user_id"
        private const val ANOMALY_COOLDOWN_MS = 60_000L
        // 백엔드 /api/emergency는 location 필수. GPS 실패 시 폴백 좌표(모바일 앱과 동일)
        private const val DEFAULT_EMERGENCY_LAT = 37.557999
        private const val DEFAULT_EMERGENCY_LNG = 127.007993
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

