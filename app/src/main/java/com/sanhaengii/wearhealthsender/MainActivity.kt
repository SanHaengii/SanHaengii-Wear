package com.sanhaengii.wearhealthsender

import android.Manifest
import android.app.Activity
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
    fun toJson(): JSONObject {
        return JSONObject()
            .put("measured_at", measuredAt)
            .putNullable("heart_rate", heartRate)
            .putNullable("steps", steps)
            .putNullable("calories", calories)
            .putNullable("spo2", spo2)
            .putNullable("body_temp", bodyTemp)
            .putNullable("blood_pressure_systolic", bloodPressureSystolic)
            .putNullable("blood_pressure_diastolic", bloodPressureDiastolic)
    }

    fun toRequestBody(): String {
        return toJson().toString()
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

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }

    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var payloadText: TextView
    private lateinit var resultText: TextView
    private lateinit var startExerciseButton: Button
    private lateinit var stopExerciseButton: Button
    private lateinit var sendButton: Button
    private lateinit var startAndSendButton: Button
    private lateinit var autoSendButton: Button

    private var currentPayload = HealthServicesPayload.empty()
    private var isExerciseRunning = false
    private var isSending = false
    private var sendOnNextUpdate = false
    private var autoSendEachUpdate = false
    private var nextFakeSpo2 = 100
    private var lastFakeSpo2: Double? = null
    private var supportedDataTypes = DEFAULT_DATA_TYPES

    private val fakeSpo2Runnable = object : Runnable {
        override fun run() {
            updateFakeSpo2(sendAfterUpdate = autoSendEachUpdate)
            mainHandler.postDelayed(this, SPO2_TICK_MS)
        }
    }

    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val payload = payloadFromMetrics(update.latestMetrics)
            mainHandler.post {
                currentPayload = payload
                renderPayload()
                appendResult("Health Services update received.")
                val shouldSend = sendOnNextUpdate || autoSendEachUpdate
                if (sendOnNextUpdate) {
                    sendOnNextUpdate = false
                }
                if (shouldSend) {
                    if (isSending) {
                        appendResult("Auto-send skipped because a previous request is still running.")
                    } else {
                        sendCurrentPayload()
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
        updateFakeSpo2(sendAfterUpdate = false)
        renderPayload()
        updateExerciseButtons()
        refreshCapabilities()
        mainHandler.postDelayed(fakeSpo2Runnable, SPO2_TICK_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(fakeSpo2Runnable)
        if (isExerciseRunning) {
            exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback)
            exerciseClient.endExerciseAsync()
        }
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == HEALTH_SERVICES_PERMISSION_REQUEST) {
            if (hasRequiredPermissions()) {
                appendResult("Health Services permissions granted.")
                startExercise()
            } else {
                appendResult("Health Services permissions were not granted.")
            }
        }
    }

    private fun createContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp(), 16.dp(), 18.dp(), 24.dp())
            setBackgroundColor(Color.rgb(15, 23, 42))
        }

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

        root.addSpace(12)
        payloadText = text("", 12f, color = Color.rgb(226, 232, 240)).apply {
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.rgb(30, 41, 59))
        }
        root.addView(payloadText, fullWidthParams())

        root.addSpace(10)
        startExerciseButton = button("Start HS exercise", Color.rgb(56, 189, 248)) {
            startExercise()
        }
        root.addView(startExerciseButton)

        root.addSpace(8)
        stopExerciseButton = button("Stop HS exercise", Color.rgb(148, 163, 184)) {
            stopExercise()
        }
        root.addView(stopExerciseButton)

        root.addSpace(8)
        sendButton = button("Send to backend", Color.rgb(34, 197, 94)) {
            sendCurrentPayload()
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
        autoSendButton = button("Auto-send updates: OFF", Color.rgb(148, 163, 184)) {
            toggleAutoSendUpdates()
        }
        root.addView(autoSendButton)

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
                    updateExerciseButtons()
                    appendResult("Started Health Services walking exercise.")
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
                    sendOnNextUpdate = false
                    updateExerciseButtons()
                    appendResult("Stopped Health Services exercise.")
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
            spo2 = lastFakeSpo2,
            bodyTemp = null,
            bloodPressureSystolic = null,
            bloodPressureDiastolic = null,
        )
    }

    private fun updateFakeSpo2(sendAfterUpdate: Boolean) {
        val spo2 = nextFakeSpo2.toDouble()
        lastFakeSpo2 = spo2
        nextFakeSpo2 = if (nextFakeSpo2 <= MIN_FAKE_SPO2) {
            MAX_FAKE_SPO2
        } else {
            nextFakeSpo2 - 1
        }

        currentPayload = currentPayload.copy(
            measuredAt = nowKstIsoString(),
            spo2 = spo2,
        )
        renderPayload()

        if (!sendAfterUpdate) {
            return
        }

        if (isSending) {
            appendResult("Auto-send skipped because a previous request is still running.")
        } else {
            sendCurrentPayload()
        }
    }

    private fun sendCurrentPayload() {
        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()

        if (baseUrl.isBlank()) {
            appendResult("Backend URL is empty.")
            return
        }

        setSending(true)

        val requestBody = currentPayload.toRequestBody()
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
        payloadText.text = currentPayload.toDisplayText()
    }

    private fun setSending(isSending: Boolean) {
        this.isSending = isSending
        sendButton.isEnabled = !isSending
        startAndSendButton.isEnabled = !isSending
        sendButton.text = if (isSending) "Sending..." else "Send to backend"
    }

    private fun updateExerciseButtons() {
        startExerciseButton.isEnabled = !isExerciseRunning
        stopExerciseButton.isEnabled = isExerciseRunning
    }

    private fun toggleAutoSendUpdates() {
        autoSendEachUpdate = !autoSendEachUpdate
        updateAutoSendButton()
        appendResult("Auto-send updates: ${if (autoSendEachUpdate) "ON" else "OFF"}")
    }

    private fun updateAutoSendButton() {
        autoSendButton.text = if (autoSendEachUpdate) {
            "Auto-send updates: ON"
        } else {
            "Auto-send updates: OFF"
        }
        autoSendButton.setBackgroundColor(
            if (autoSendEachUpdate) Color.rgb(251, 146, 60) else Color.rgb(148, 163, 184),
        )
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
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
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

    companion object {
        private const val HEALTH_SERVICES_PERMISSION_REQUEST = 30
        private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val SPO2_TICK_MS = 1_000L
        private const val MAX_FAKE_SPO2 = 100
        private const val MIN_FAKE_SPO2 = 90

        private val DEFAULT_DATA_TYPES = setOf(
            DataType.HEART_RATE_BPM,
            DataType.STEPS_TOTAL,
            DataType.CALORIES_TOTAL,
        )
    }
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
