package com.sanhaengii.app

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
            steps = maxNullable(steps, update.steps),
            calories = maxNullable(calories, update.calories),
            spo2 = update.spo2 ?: spo2,
            bodyTemp = update.bodyTemp ?: bodyTemp,
            bloodPressureSystolic = update.bloodPressureSystolic ?: bloodPressureSystolic,
            bloodPressureDiastolic = update.bloodPressureDiastolic ?: bloodPressureDiastolic,
        )
    }

    fun hasCollectedRequiredValues(): Boolean {
        return steps != null && calories != null
    }

    fun missingRequiredFields(): String {
        return listOfNotNull(
            "steps".takeIf { steps == null },
            "calories".takeIf { calories == null },
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

private fun maxNullable(first: Int?, second: Int?): Int? {
    return listOfNotNull(first, second).maxOrNull()
}

private fun maxNullable(first: Double?, second: Double?): Double? {
    return listOfNotNull(first, second).maxOrNull()
}

private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

private fun nowKstIsoString(): String {
    return OffsetDateTime.now(KST_ZONE)
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()
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
