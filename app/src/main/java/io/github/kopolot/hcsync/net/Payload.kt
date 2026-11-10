package io.github.kopolot.hcsync.net

import io.github.kopolot.hcsync.domain.DailySummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PAYLOAD_SCHEMA = 1

@Serializable
data class SyncPayload(
    val secret: String,
    val schema: Int = PAYLOAD_SCHEMA,
    @SerialName("app_version") val appVersion: String,
    val days: List<DayDto>,
)

@Serializable
data class DayDto(
    val date: String,
    @SerialName("kcal_in") val kcalIn: Int?,
    @SerialName("protein_g") val proteinG: Double?,
    @SerialName("fat_g") val fatG: Double?,
    @SerialName("carbs_g") val carbsG: Double?,
    @SerialName("kcal_burned_total") val kcalBurnedTotal: Int?,
    @SerialName("kcal_active") val kcalActive: Int?,
    val steps: Long?,
    @SerialName("resting_hr") val restingHr: Long?,
    @SerialName("weight_kg") val weightKg: Double?,
    @SerialName("body_fat_pct") val bodyFatPct: Double?,
    @SerialName("sleep_min") val sleepMin: Int?,
    val workouts: List<WorkoutDto>,
)

@Serializable
data class WorkoutDto(
    val id: String,
    val type: String,
    val title: String?,
    val start: String,
    val end: String,
    val min: Int,
)

@Serializable
data class SyncResponse(
    val ok: Boolean,
    val error: String? = null,
    val days: Int? = null,
    val workouts: Int? = null,
)

val PayloadJson = Json {
    explicitNulls = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun DailySummary.toDto() = DayDto(
    date = date.toString(),
    kcalIn = kcalIn,
    proteinG = proteinG,
    fatG = fatG,
    carbsG = carbsG,
    kcalBurnedTotal = kcalBurnedTotal,
    kcalActive = kcalActive,
    steps = steps,
    restingHr = restingHr,
    weightKg = weightKg,
    bodyFatPct = bodyFatPct,
    sleepMin = sleepMin,
    workouts = workouts.map {
        WorkoutDto(it.id, it.type, it.title, it.start.toString(), it.end.toString(), it.minutes)
    },
)
