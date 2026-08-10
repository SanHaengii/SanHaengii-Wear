package com.sanhaengii.app

import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest

data class AuthPayload(val userId: Int, val token: String)
data class HikeStatePayload(
    val active: Boolean,
    val paused: Boolean,
    val courseName: String,
    val etaMin: Int,
    val remainKm: Double,
)
data class SosAckPayload(val id: String, val state: String)

// ---- Pure, unit-tested core. Plain Kotlin types only — no android.* here. ----
// Every payload carries an "at" timestamp because the Data Layer silently drops
// a putDataItem whose bytes are identical to the existing item.

fun buildHealthLivePayload(hr: Int, spo2: Double, temp: Double, steps: Int): ByteArray {
    val at = System.currentTimeMillis()
    return "$hr|$spo2|$temp|$steps|$at".toByteArray()
}

fun buildAnomalyPayload(type: String, message: String): Map<String, Any> = mapOf(
    "type" to type,
    "message" to message,
    "at" to System.currentTimeMillis(),
)

fun buildSosRequestPayload(id: String, source: String, lat: Double?, lng: Double?): Map<String, Any> {
    val map = mutableMapOf<String, Any>(
        "id" to id,
        "source" to source,
        "at" to System.currentTimeMillis(),
    )
    if (lat != null) map["lat"] = lat
    if (lng != null) map["lng"] = lng
    return map
}

fun parseAuthPayload(map: Map<String, Any?>): AuthPayload =
    AuthPayload(
        userId = (map["userId"] as? Int) ?: 0,
        token = (map["token"] as? String) ?: "",
    )

fun parseHikeStatePayload(map: Map<String, Any?>): HikeStatePayload =
    HikeStatePayload(
        active = (map["active"] as? Boolean) ?: false,
        paused = (map["paused"] as? Boolean) ?: false,
        courseName = (map["courseName"] as? String) ?: "",
        etaMin = (map["etaMin"] as? Int) ?: 0,
        remainKm = (map["remainKm"] as? Double) ?: 0.0,
    )

fun parseSosAckPayload(map: Map<String, Any?>): SosAckPayload =
    SosAckPayload(
        id = (map["id"] as? String) ?: "",
        state = (map["state"] as? String) ?: "",
    )

// ---- Thin adapters. NOT unit tested — android.* types only appear here. ----
// Task 6 wires these to the real Wearable clients.

fun Map<String, Any>.toPutDataMapRequest(path: String): PutDataMapRequest {
    val req = PutDataMapRequest.create(path)
    for ((key, value) in this) {
        when (value) {
            is String -> req.dataMap.putString(key, value)
            is Int -> req.dataMap.putInt(key, value)
            is Long -> req.dataMap.putLong(key, value)
            is Double -> req.dataMap.putDouble(key, value)
            is Boolean -> req.dataMap.putBoolean(key, value)
            else -> throw IllegalArgumentException("Unsupported value type for key $key: ${value::class}")
        }
    }
    return req
}

fun DataMap.toPlainMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    for (key in keySet()) {
        result[key] = get(key)
    }
    return result
}
