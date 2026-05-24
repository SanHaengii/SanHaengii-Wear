package com.sanhaengii.wearhealthsender

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.random.Random

data class FakeHealthPayload(
    val measuredAt: String,
    val heartRate: Int,
    val spo2: Double,
    val steps: Int,
    val calories: Double,
    val bodyTemp: Double,
    val bloodPressureSystolic: Int,
    val bloodPressureDiastolic: Int,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("measured_at", measuredAt)
            .put("heart_rate", heartRate)
            .put("steps", steps)
            .put("calories", calories)
            .put("spo2", spo2)
            .put("body_temp", bodyTemp)
            .put("blood_pressure_systolic", bloodPressureSystolic)
            .put("blood_pressure_diastolic", bloodPressureDiastolic)
    }

    fun toRequestBody(): String {
        return toJson().toString()
    }

    fun toDisplayText(): String {
        return """
            HR: $heartRate bpm
            BP: $bloodPressureSystolic/$bloodPressureDiastolic mmHg
            SpO2: $spo2 %
            Steps: $steps
            Calories: $calories kcal
            Temp: $bodyTemp C
            At: $measuredAt
        """.trimIndent()
    }

    companion object {
        fun generate(): FakeHealthPayload {
            return FakeHealthPayload(
                measuredAt = Instant.now().toString(),
                heartRate = Random.nextInt(72, 146),
                spo2 = Random.nextDouble(95.0, 99.1).roundToOneDecimal(),
                steps = Random.nextInt(500, 6001),
                calories = Random.nextDouble(20.0, 350.0).roundToOneDecimal(),
                bodyTemp = Random.nextDouble(36.1, 37.3).roundToOneDecimal(),
                bloodPressureSystolic = Random.nextInt(105, 146),
                bloodPressureDiastolic = Random.nextInt(65, 96),
            )
        }
    }
}

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var payloadText: TextView
    private lateinit var resultText: TextView
    private lateinit var sendButton: Button
    private lateinit var generateAndSendButton: Button

    private var currentPayload = FakeHealthPayload.generate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        renderPayload()
    }

    private fun createContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp(), 16.dp(), 18.dp(), 24.dp())
            setBackgroundColor(Color.rgb(15, 23, 42))
        }

        root.addView(text("SanHaengii", 18f, bold = true))
        root.addView(text("Wear health sender", 12f, color = Color.rgb(203, 213, 225)))
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
        root.addView(button("Generate fake data", Color.rgb(148, 163, 184)) {
            currentPayload = FakeHealthPayload.generate()
            renderPayload()
            appendResult("Generated fake payload.")
        })

        root.addSpace(8)
        sendButton = button("Send to backend", Color.rgb(34, 197, 94)) {
            sendCurrentPayload()
        }
        root.addView(sendButton)

        root.addSpace(8)
        generateAndSendButton = button("Generate & send", Color.rgb(250, 204, 21)) {
            currentPayload = FakeHealthPayload.generate()
            renderPayload()
            sendCurrentPayload()
        }
        root.addView(generateAndSendButton)

        root.addSpace(12)
        resultText = text("Ready.", 11f, color = Color.rgb(226, 232, 240)).apply {
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.rgb(2, 6, 23))
        }
        root.addView(resultText, fullWidthParams())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
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

        val payload = currentPayload
        val requestBody = payload.toRequestBody()
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
        sendButton.isEnabled = !isSending
        generateAndSendButton.isEnabled = !isSending
        sendButton.text = if (isSending) "Sending..." else "Send to backend"
    }

    private fun appendResult(message: String) {
        val now = Instant.now().toString().substring(11, 19)
        resultText.text = "[$now] $message\n\n${resultText.text}"
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
}

private fun Double.roundToOneDecimal(): Double {
    return (this * 10.0).roundToInt() / 10.0
}
