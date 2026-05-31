package com.sanhaengii.wearhealthsender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEvent
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class HealthServicesPayload(
    val measuredAt: String,
    val heartRate: Int?,
    val steps: Int?,
    val calories: Double?,
    val spo2: Double?,
    val bodyTemp: Double?,
    val bloodPressureSystolic: Int?,
    val bloodPressureDiastolic: Int?,
) {
    fun toJson(userId: Long): JSONObject {
        return JSONObject()
            .put("user_id", userId)
            .put("measured_at", measuredAt)
            .putNullable("heart_rate", heartRate)
            .putNullable("steps", steps)
            .putNullable("calories", calories)
            .putNullable("spo2", spo2)
            .putNullable("body_temp", bodyTemp)
            .putNullable("blood_pressure_systolic", bloodPressureSystolic)
            .putNullable("blood_pressure_diastolic", bloodPressureDiastolic)
    }

    fun toRequestBody(userId: Long): String {
        return toJson(userId).toString()
    }

    fun toDisplayText(): String {
        return """
            HR: ${heartRate.display("bpm")}
            BP: ${displayBloodPressure()}
            SpO2: ${spo2.display("%")}
            Steps: ${steps.display()}
            Calories: ${calories.display("kcal")}
            Temp: ${bodyTemp.display("C")}
            At: $measuredAt
        """.trimIndent()
    }

    fun mergeWith(update: HealthServicesPayload): HealthServicesPayload {
        return copy(
            measuredAt = update.measuredAt,
            heartRate = update.heartRate ?: heartRate,
            steps = update.steps ?: steps,
            calories = update.calories ?: calories,
            spo2 = update.spo2 ?: spo2,
            bodyTemp = update.bodyTemp ?: bodyTemp,
            bloodPressureSystolic = update.bloodPressureSystolic ?: bloodPressureSystolic,
            bloodPressureDiastolic = update.bloodPressureDiastolic ?: bloodPressureDiastolic,
        )
    }

    fun hasCollectedRequiredValues(): Boolean {
        return heartRate != null && steps != null && calories != null && spo2 != null
    }

    fun missingRequiredFields(): String {
        return listOfNotNull(
            "heart_rate".takeIf { heartRate == null },
            "steps".takeIf { steps == null },
            "calories".takeIf { calories == null },
            "spo2".takeIf { spo2 == null },
        ).joinToString()
    }

    private fun displayBloodPressure(): String {
        return if (bloodPressureSystolic == null || bloodPressureDiastolic == null) {
            "-"
        } else {
            "$bloodPressureSystolic/$bloodPressureDiastolic mmHg"
        }
    }

    companion object {
        fun empty(): HealthServicesPayload {
            return HealthServicesPayload(
                measuredAt = nowKstIsoString(),
                heartRate = null,
                steps = null,
                calories = null,
                spo2 = null,
                bodyTemp = null,
                bloodPressureSystolic = null,
                bloodPressureDiastolic = null,
            )
        }
    }
}

class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }

    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var etaInput: EditText
    private lateinit var distanceInput: EditText
    private lateinit var payloadText: TextView
    private lateinit var resultText: TextView
    private lateinit var startExerciseButton: Button
    private lateinit var stopExerciseButton: Button
    private lateinit var sendButton: Button
    private lateinit var syncWatchButton: Button
    private lateinit var startAndSendButton: Button
    private lateinit var measureSpo2Button: Button
    private lateinit var autoSendButton: Button
    private lateinit var finishRescueButton: Button
    private lateinit var rescueLayout: LinearLayout

    private var currentPayload = HealthServicesPayload.empty()
    private var isExerciseRunning = false
    private var isSending = false
    private var isRescueMode = false
    private var isHikingActive = false
    private var isPeriodicSendingEnabled = false
    private var sendOnNextUpdate = false
    private var pendingPermissionAction = PendingPermissionAction.NONE
    private var nextFakeSpo2 = 100
    private var lastSpo2: Double? = null
    private var isFakeSpo2Active = false
    private var spo2SourceText = "initializing"
    private var samsungSpo2Provider: SamsungSpo2Provider? = null
    private var samsungSkinTemperatureProvider: SamsungSkinTemperatureProvider? = null
    private var supportedDataTypes = DEFAULT_DATA_TYPES

    private val fakeSpo2Runnable = object : Runnable {
        override fun run() {
            updateFakeSpo2()
            mainHandler.postDelayed(this, SPO2_TICK_MS)
        }
    }

    private val periodicHealthSendRunnable = object : Runnable {
        override fun run() {
            if (!isHikingActive || !isPeriodicSendingEnabled) {
                return
            }
            sendCollectedPayload(requireHiking = true)
            mainHandler.postDelayed(this, HEALTH_SEND_INTERVAL_MS)
        }
    }

    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val payload = payloadFromMetrics(update.latestMetrics)
            mainHandler.post {
                currentPayload = currentPayload.mergeWith(payload)
                renderPayload()
                appendResult("Health Services update received.")
                
                syncDataToWatch()

                if (sendOnNextUpdate) {
                    val sent = sendCollectedPayload(requireHiking = true)
                    if (sendOnNextUpdate && sent) {
                        sendOnNextUpdate = false
                    }
                }
            }
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            mainHandler.post {
                appendResult("${dataType.name} availability: ${availability::class.simpleName}")
            }
        }

        override fun onExerciseEventReceived(event: ExerciseEvent) {
            mainHandler.post {
                appendResult("Exercise event: ${event::class.simpleName}")
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

        override fun onRegistered() {
            mainHandler.post {
                appendResult("Health Services callback registered.")
            }
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            mainHandler.post {
                appendResult("Health Services callback registration failed: ${throwable.message ?: throwable.javaClass.simpleName}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        renderPayload()
        updateExerciseButtons()
        refreshCapabilities()
        startSpo2Provider()
        startSkinTemperatureProvider()
        
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(fakeSpo2Runnable)
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
        samsungSpo2Provider?.stop()
        samsungSkinTemperatureProvider?.stop()
        if (isExerciseRunning) {
            exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback)
            exerciseClient.endExerciseAsync()
        }
        Wearable.getMessageClient(this).removeListener(this)
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/sos_triggered") {
            mainHandler.post {
                enterRescueMode()
            }
        }
    }

    private fun enterRescueMode() {
        isRescueMode = true
        rescueLayout.visibility = ViewGroup.VISIBLE
        appendResult("🚨 SOS TRIGGERED FROM WATCH! Rescue protocol initiated.")
    }

    private fun finishRescueProtocol() {
        isRescueMode = false
        rescueLayout.visibility = ViewGroup.GONE
        appendResult("✅ Rescue protocol finished. Notifying watch.")
        
        val putDataMapReq = PutDataMapRequest.create("/sos_status")
        putDataMapReq.dataMap.putString("status", "finished")
        putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        Wearable.getDataClient(this).putDataItem(putDataReq)
    }

    private fun syncDataToWatch() {
        val eta = etaInput.text.toString().ifBlank { "-" }
        val distance = distanceInput.text.toString().ifBlank { "-" }
        val bpm = currentPayload.heartRate ?: 0

        val putDataMapReq = PutDataMapRequest.create("/hiking_info")
        putDataMapReq.dataMap.putString("eta", eta)
        putDataMapReq.dataMap.putString("distance", distance)
        putDataMapReq.dataMap.putInt("bpm", bpm)
        putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
        
        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        Wearable.getDataClient(this).putDataItem(putDataReq)
    }

    private fun createContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp(), 16.dp(), 18.dp(), 24.dp())
            setBackgroundColor(Color.rgb(15, 23, 42))
        }

        rescueLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            setBackgroundColor(Color.RED)
            visibility = ViewGroup.GONE
            
            addView(text("🚨 구조 프로토콜 실행 중", 16f, bold = true))
            addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, 8.dp()))
            finishRescueButton = button("구조 완료 및 워치 해제", Color.WHITE) {
                finishRescueProtocol()
            }
            addView(finishRescueButton)
        }
        root.addView(rescueLayout, fullWidthParams())
        root.addSpace(12)

        root.addView(text("SanHaengii", 18f, bold = true))
        root.addView(text("Wear Health Services sender", 12f, color = Color.rgb(203, 213, 225)))
        root.addSpace(12)

        root.addView(label("Backend URL"))
        baseUrlInput = input(BuildConfig.HEALTH_API_BASE_URL)
        root.addView(baseUrlInput)

        root.addSpace(8)
        root.addView(label("JWT token"))
        tokenInput = input(BuildConfig.HEALTH_API_TOKEN)
        root.addView(tokenInput)

        root.addSpace(8)
        root.addView(label("User ID"))
        userIdInput = input(BuildConfig.HEALTH_API_USER_ID)
        root.addView(userIdInput)

        root.addSpace(12)
        
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val col1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(label("ETA (min)"))
            etaInput = input("30")
            addView(etaInput)
        }
        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8.dp(), 0, 0, 0)
            addView(label("Distance (km)"))
            distanceInput = input("2.5")
            addView(distanceInput)
        }
        row.addView(col1)
        row.addView(col2)
        root.addView(row)

        root.addSpace(12)
        payloadText = text("", 12f, color = Color.rgb(226, 232, 240)).apply {
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.rgb(30, 41, 59))
        }
        root.addView(payloadText, fullWidthParams())

        root.addSpace(10)
        
        syncWatchButton = button("Sync to Watch Now", Color.rgb(167, 139, 250)) {
            syncDataToWatch()
            appendResult("Manually synced hiking info to watch.")
        }
        root.addView(syncWatchButton)
        root.addSpace(8)

        startExerciseButton = button("Start hiking", Color.rgb(56, 189, 248)) {
            startExercise()
        }
        root.addView(startExerciseButton)

        root.addSpace(8)
        stopExerciseButton = button("Stop hiking", Color.rgb(148, 163, 184)) {
            stopExercise()
        }
        root.addView(stopExerciseButton)

        root.addSpace(8)
        measureSpo2Button = button("Measure SpO2", Color.rgb(125, 211, 252)) {
            requestSpo2Measurement()
        }
        root.addView(measureSpo2Button)

        root.addSpace(8)
        sendButton = button("Send once", Color.rgb(34, 197, 94)) {
            sendCollectedPayload(requireHiking = true)
        }
        root.addView(sendButton)

        root.addSpace(8)
        startAndSendButton = button("Start & send next", Color.rgb(250, 204, 21)) {
            sendOnNextUpdate = true
            startExercise()
            appendResult("Will send after the next Health Services update.")
        }
        root.addView(startAndSendButton)

        root.addSpace(8)
        autoSendButton = button("3s backend send: OFF", Color.rgb(148, 163, 184)) {
            togglePeriodicSending()
        }
        root.addView(autoSendButton)

        root.addSpace(8)
        val openDashboardButton = button("Open Dashboard (Compose)", Color.rgb(99, 102, 241)) {
            val intent = Intent(this@MainActivity, ComposeMainActivity::class.java)
            intent.putExtra("bpm", currentPayload.heartRate ?: 0)
            intent.putExtra("eta", etaInput.text.toString())
            intent.putExtra("distance", distanceInput.text.toString())
            startActivity(intent)
        }
        root.addView(openDashboardButton)

        root.addSpace(12)
        resultText = text(
            "Ready. Start a Health Services exercise to receive emulator/sensor updates.",
            11f,
            color = Color.rgb(226, 232, 240),
        ).apply {
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.rgb(2, 6, 23))
        }
        root.addView(resultText, fullWidthParams())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun refreshCapabilities() {
        activityScope.launch {
            val result = runCatching {
                val capabilities = exerciseClient.getCapabilitiesAsync().await()
                if (ExerciseType.WALKING in capabilities.supportedExerciseTypes) {
                    capabilities.getExerciseTypeCapabilities(ExerciseType.WALKING).supportedDataTypes
                } else {
                    emptySet()
                }
            }

            result
                .onSuccess { capabilities ->
                    supportedDataTypes = DEFAULT_DATA_TYPES.filterTo(mutableSetOf()) { it in capabilities }
                    appendResult("Supported HS data: ${supportedDataTypes.joinToString { it.name }}")
                }
                .onFailure { appendResult("Could not read Health Services capabilities: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun startExercise() {
        if (!hasRequiredPermissions()) {
            pendingPermissionAction = PendingPermissionAction.START_EXERCISE
            requestPermissions(requiredPermissions(), HEALTH_SERVICES_PERMISSION_REQUEST)
            return
        }

        if (isExerciseRunning) {
            appendResult("Health Services exercise is already running.")
            return
        }

        activityScope.launch {
            val result = runCatching {
                exerciseClient.setUpdateCallback(exerciseUpdateCallback)
                exerciseClient.startExerciseAsync(
                    ExerciseConfig(
                        exerciseType = ExerciseType.WALKING,
                        dataTypes = supportedDataTypes,
                        isAutoPauseAndResumeEnabled = false,
                        isGpsEnabled = false,
                    ),
                ).await()
            }

            result
                .onSuccess {
                    isExerciseRunning = true
                    isHikingActive = true
                    currentPayload = HealthServicesPayload.empty().copy(spo2 = lastSpo2)
                    startPeriodicSending()
                    updateExerciseButtons()
                    renderPayload()
                    appendResult("Started hiking. Health data will POST every 3 seconds.")
                }
                .onFailure {
                    appendResult("Could not start Health Services exercise: ${it.message ?: it.javaClass.simpleName}")
                    sendOnNextUpdate = false
                }
        }
    }

    private fun stopExercise() {
        if (!isExerciseRunning) {
            appendResult("Health Services exercise is not running.")
            return
        }

        activityScope.launch {
            val result = runCatching {
                exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback).await()
                exerciseClient.endExerciseAsync().await()
            }

            result
                .onSuccess {
                    isExerciseRunning = false
                    isHikingActive = false
                    sendOnNextUpdate = false
                    stopPeriodicSending()
                    updateExerciseButtons()
                    appendResult("Stopped hiking and 3-second health data sending.")
                }
                .onFailure { appendResult("Could not stop Health Services exercise: ${it.message ?: it.javaClass.simpleName}") }
        }
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
            measuredAt = nowKstIsoString(),
            heartRate = heartRate,
            steps = steps,
            calories = calories,
            spo2 = lastSpo2,
            bodyTemp = null,
            bloodPressureSystolic = null,
            bloodPressureDiastolic = null,
        )
    }

    private fun startSpo2Provider() {
        appendResult("SpO2 provider: trying Samsung Health Sensor SDK.")
        val provider = SamsungSpo2Provider(
            context = this,
            mainHandler = mainHandler,
            onReading = ::handleSamsungSpo2Reading,
            onStatus = ::appendResult,
            onFallbackNeeded = ::activateFakeSpo2Fallback,
        )
        samsungSpo2Provider = provider
        if (provider.start()) {
            isFakeSpo2Active = false
            spo2SourceText = "Samsung Health Sensor SDK"
            updateSpo2Button()
            renderPayload()
        }
    }

    private fun requestSpo2Measurement() {
        if (!hasRequiredPermissions()) {
            pendingPermissionAction = PendingPermissionAction.MEASURE_SPO2
            requestPermissions(requiredPermissions(), HEALTH_SERVICES_PERMISSION_REQUEST)
            return
        }

        if (isFakeSpo2Active) {
            updateFakeSpo2()
            appendResult("Fake SpO2 fallback updated.")
            return
        }

        val provider = samsungSpo2Provider
        if (provider == null) {
            activateFakeSpo2Fallback("Samsung SpO2 provider is not available.")
        } else {
            provider.requestMeasurement()
        }
    }

    private fun handleSamsungSpo2Reading(spo2: Double, heartRate: Int?) {
        lastSpo2 = spo2
        spo2SourceText = "Samsung Health Sensor SDK"
        currentPayload = currentPayload.copy(
            measuredAt = nowKstIsoString(),
            heartRate = heartRate ?: currentPayload.heartRate,
            spo2 = spo2,
        )
        renderPayload()
        appendResult("Samsung SpO2 measured: ${spo2.roundToInt()}%.")
    }

    private fun activateFakeSpo2Fallback(reason: String) {
        if (isFakeSpo2Active) {
            appendResult(reason)
            return
        }

        samsungSpo2Provider?.stop()
        samsungSpo2Provider = null
        isFakeSpo2Active = true
        spo2SourceText = "fake fallback"
        updateSpo2Button()
        appendResult("$reason Using fake SpO2 fallback.")
        updateFakeSpo2()
        mainHandler.postDelayed(fakeSpo2Runnable, SPO2_TICK_MS)
    }

    private fun updateFakeSpo2() {
        val spo2 = nextFakeSpo2.toDouble()
        lastSpo2 = spo2
        nextFakeSpo2 = if (nextFakeSpo2 <= MIN_FAKE_SPO2) {
            MAX_FAKE_SPO2
        } else {
            nextFakeSpo2 - 1
        }

        currentPayload = currentPayload.copy(
            measuredAt = nowKstIsoString(),
            spo2 = lastSpo2,
        )
        renderPayload()
    }

    private fun sendCollectedPayload(requireHiking: Boolean): Boolean {
        if (requireHiking && !isHikingActive) {
            appendResult("Start hiking before sending health data.")
            return false
        }

        if (isSending) {
            appendResult("Send skipped because a previous request is still running.")
            return false
        }

        if (!currentPayload.hasCollectedRequiredValues()) {
            appendResult("Waiting for collected payload. Missing: ${currentPayload.missingRequiredFields()}")
            return false
        }

        sendCurrentPayload()
        return true
    }

    private fun sendCurrentPayload() {
        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        val userId = userIdInput.text.toString().trim().toLongOrNull()

        if (baseUrl.isBlank()) {
            appendResult("Backend URL is empty.")
            return
        }

        if (userId == null || userId <= 0L) {
            appendResult("User ID must be a positive number.")
            return
        }

        setSending(true)

        val payloadForSend = currentPayload.copy(
            measuredAt = nowKstIsoString(),
            bodyTemp = currentPayload.bodyTemp ?: DEFAULT_BODY_TEMP,
        )
        currentPayload = payloadForSend
        renderPayload()

        val requestBody = payloadForSend.toRequestBody(userId)
        appendResult("POST $baseUrl/health/data")

        Thread {
            val result = runCatching {
                postHealthData(baseUrl, token, requestBody)
            }

            mainHandler.post {
                setSending(false)
                result
                    .onSuccess { appendResult(it) }
                    .onFailure { appendResult("ERROR: ${it.message ?: it.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun postHealthData(baseUrl: String, token: String, body: String): String {
        val connection = URL("$baseUrl/health/data").openConnection() as HttpURLConnection
        return try {
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
            val responseBody = readResponseBody(connection)
            "HTTP $code\n$responseBody"
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun renderPayload() {
        val sendState = if (isHikingActive && isPeriodicSendingEnabled) "ON" else "OFF"
        payloadText.text = """
            ${currentPayload.toDisplayText()}
            User ID: ${userIdInput.text.toString().ifBlank { "-" }}
            Hiking: ${if (isHikingActive) "active" else "inactive"}
            3s backend send: $sendState
            SpO2 source: $spo2SourceText
        """.trimIndent()
    }

    private fun setSending(isSending: Boolean) {
        this.isSending = isSending
        sendButton.isEnabled = !isSending
        startAndSendButton.isEnabled = !isSending
        sendButton.text = if (isSending) "Sending..." else "Send once"
    }

    private fun updateExerciseButtons() {
        startExerciseButton.isEnabled = !isExerciseRunning
        stopExerciseButton.isEnabled = isExerciseRunning
    }

    private fun updateSpo2Button() {
        if (!::measureSpo2Button.isInitialized) {
            return
        }
        measureSpo2Button.text = if (isFakeSpo2Active) {
            "Fake SpO2 next"
        } else {
            "Measure SpO2"
        }
    }

    private fun togglePeriodicSending() {
        if (!isHikingActive) {
            appendResult("Start hiking before enabling 3-second backend sending.")
            startExercise()
            return
        }

        if (isPeriodicSendingEnabled) {
            stopPeriodicSending()
        } else {
            startPeriodicSending()
        }
    }

    private fun startSkinTemperatureProvider() {
        appendResult("Body temp provider: trying Samsung skin temperature tracker.")
        val provider = SamsungSkinTemperatureProvider(
            context = this,
            mainHandler = mainHandler,
            onReading = { temperature ->
                currentPayload = currentPayload.copy(
                    measuredAt = nowKstIsoString(),
                    bodyTemp = temperature,
                )
                renderPayload()
                appendResult("Samsung skin temperature measured: $temperature C.")
            },
            onStatus = ::appendResult,
            onUnavailable = { message ->
                appendResult("$message Using body_temp fallback when sending.")
            },
        )
        samsungSkinTemperatureProvider = provider
        if (!provider.start()) {
            samsungSkinTemperatureProvider = null
        }
    }

    private fun updateAutoSendButton() {
        autoSendButton.text = if (isPeriodicSendingEnabled) {
            "3s backend send: ON"
        } else {
            "3s backend send: OFF"
        }
        autoSendButton.setBackgroundColor(
            if (isPeriodicSendingEnabled) Color.rgb(251, 146, 60) else Color.rgb(148, 163, 184),
        )
    }

    private fun startPeriodicSending() {
        if (!isHikingActive) {
            return
        }

        isPeriodicSendingEnabled = true
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
        mainHandler.postDelayed(periodicHealthSendRunnable, HEALTH_SEND_INTERVAL_MS)
        updateAutoSendButton()
        renderPayload()
    }

    private fun stopPeriodicSending() {
        isPeriodicSendingEnabled = false
        mainHandler.removeCallbacks(periodicHealthSendRunnable)
        updateAutoSendButton()
        renderPayload()
    }

    private fun appendResult(message: String) {
        val now = nowKstTimeText()
        resultText.text = "[$now] $message\n\n${resultText.text}"
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

    private fun text(
        value: String,
        size: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false,
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = true
            if (bold) {
                setTypeface(typeface, Typeface.BOLD)
            }
        }
    }

    private fun label(value: String): TextView {
        return text(value, 11f, Color.rgb(148, 163, 184)).apply {
            gravity = Gravity.START
        }
    }

    private fun input(value: String): EditText {
        return EditText(this).apply {
            setText(value)
            textSize = 11f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.rgb(15, 23, 42))
            setHintTextColor(Color.rgb(100, 116, 139))
            setBackgroundColor(Color.rgb(241, 245, 249))
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            layoutParams = fullWidthParams()
        }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.rgb(15, 23, 42))
            setBackgroundColor(color)
            setOnClickListener { onClick() }
            layoutParams = fullWidthParams()
        }
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun LinearLayout.addSpace(heightDp: Int) {
        addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, heightDp.dp()))
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == HEALTH_SERVICES_PERMISSION_REQUEST) {
            if (hasRequiredPermissions()) {
                val action = pendingPermissionAction
                pendingPermissionAction = PendingPermissionAction.NONE
                appendResult("Health sensor permissions granted.")
                when (action) {
                    PendingPermissionAction.START_EXERCISE -> startExercise()
                    PendingPermissionAction.MEASURE_SPO2 -> requestSpo2Measurement()
                    PendingPermissionAction.NONE -> Unit
                }
            } else {
                pendingPermissionAction = PendingPermissionAction.NONE
                appendResult("Health sensor permissions were not granted.")
            }
        }
    }

    companion object {
        private const val HEALTH_SERVICES_PERMISSION_REQUEST = 30
        private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val PERMISSION_READ_OXYGEN_SATURATION =
            "android.permission.health.READ_OXYGEN_SATURATION"
        private const val SPO2_TICK_MS = 1_000L
        private const val HEALTH_SEND_INTERVAL_MS = 3_000L
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

private enum class PendingPermissionAction {
    NONE,
    START_EXERCISE,
    MEASURE_SPO2,
}

private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

private fun nowKstIsoString(): String {
    return OffsetDateTime.now(KST_ZONE)
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()
}

private fun nowKstTimeText(): String {
    return OffsetDateTime.now(KST_ZONE)
        .toLocalTime()
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
    return put(key, value ?: JSONObject.NULL)
}

private fun Int?.display(suffix: String = ""): String {
    return this?.let { if (suffix.isBlank()) "$it" else "$it $suffix" } ?: "-"
}

private fun Double?.display(suffix: String = ""): String {
    return this?.let { if (suffix.isBlank()) "$it" else "$it $suffix" } ?: "-"
}

private fun Double.roundToOneDecimal(): Double {
    return (this * 10.0).roundToInt() / 10.0
}
