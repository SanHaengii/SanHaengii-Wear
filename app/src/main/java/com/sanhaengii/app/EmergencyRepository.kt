package com.sanhaengii.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class ApiResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

enum class EmergencyResponseStatus { SUCCESS, QUEUED, FAILED }

fun buildEmergencyRequestBody(
    userId: Long,
    eventType: String,
    latitude: Double?,
    longitude: Double?,
    triggeredBy: String,
    reason: String,
    requestId: String,
    timestamp: String = Instant.now().toString(),
): String {
    require(userId > 0)
    return JSONObject()
        .put("userId", userId)
        .put("eventType", eventType)
        .put("triggered_by", triggeredBy)
        .put("reason", reason)
        .put("requestId", requestId)
        .put("location", JSONObject().put("lat", latitude ?: JSONObject.NULL).put("lng", longitude ?: JSONObject.NULL))
        .put("timestamp", timestamp)
        .toString()
}

fun parseEmergencyResponse(response: ApiResponse): EmergencyResponseStatus {
    if (!response.isSuccessful) return EmergencyResponseStatus.FAILED
    val state = runCatching { JSONObject(response.body).optString("state", "success") }.getOrDefault("success")
    return when (state.lowercase()) {
        "queued", "pending" -> EmergencyResponseStatus.QUEUED
        "failed", "error" -> EmergencyResponseStatus.FAILED
        else -> EmergencyResponseStatus.SUCCESS
    }
}

class EmergencyRepository(
    private val apiBaseUrl: String,
    private val allowCleartext: Boolean,
) {
    fun postEmergency(body: String, token: String): ApiResponse = request("/api/emergency", token, body)

    private fun request(path: String, token: String, body: String): ApiResponse {
        val normalizedBase = apiBaseUrl.trim().trimEnd('/')
        require(normalizedBase.isNotBlank()) { "Backend URL is empty" }
        val url = URL("$normalizedBase$path")
        require(url.protocol == "https" || allowCleartext) { "Cleartext HTTP is disabled for this build" }
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            ApiResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
