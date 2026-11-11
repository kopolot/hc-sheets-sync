package io.github.kopolot.hcsync.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Daily totals computed by Health Connect's aggregate API (deduplicated across apps). Null = no data. */
data class DayAggregate(
    val date: LocalDate,
    val kcalIn: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val kcalBurnedTotal: Double? = null,
    val kcalActive: Double? = null,
    val steps: Long? = null,
    val restingHr: Long? = null,
)

/** A point-in-time measurement. [offset] is the record's own zone offset if HC has one. */
data class Measurement(val time: Instant, val value: Double, val offset: ZoneOffset? = null)

data class TimeInterval(val start: Instant, val end: Instant)

/** Time span of a TotalCaloriesBurned or ActiveCaloriesBurned record (real data, not HC's BMR estimate). */
data class BurnRecord(
    val start: Instant,
    val end: Instant,
    val startOffset: ZoneOffset? = null,
    val endOffset: ZoneOffset? = null,
)

data class SleepSession(
    val start: Instant,
    val end: Instant,
    /** Stages where the user was not asleep (awake, awake in bed, out of bed). */
    val awakeStages: List<TimeInterval> = emptyList(),
    val endOffset: ZoneOffset? = null,
)

data class WorkoutSession(
    val id: String,
    val type: String,
    val title: String?,
    val start: Instant,
    val end: Instant,
    val startOffset: ZoneOffset? = null,
)

/** Everything read from Health Connect for a date range. */
data class HealthSnapshot(
    val aggregates: List<DayAggregate> = emptyList(),
    val weights: List<Measurement> = emptyList(),
    val bodyFat: List<Measurement> = emptyList(),
    val sleep: List<SleepSession> = emptyList(),
    val workouts: List<WorkoutSession> = emptyList(),
    /**
     * Calorie-burn records in the range. Null = unknown (no read permission for both
     * TotalCaloriesBurned and ActiveCaloriesBurned), so we cannot tell BMR-only days apart.
     */
    val burnRecords: List<BurnRecord>? = null,
    /** Human-readable notes about windows skipped because HC returned a malformed record. */
    val skipped: List<String> = emptyList(),
)

data class Workout(
    val id: String,
    val type: String,
    val title: String?,
    val start: Instant,
    val end: Instant,
    val minutes: Int,
)

data class DailySummary(
    val date: LocalDate,
    val kcalIn: Int? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val kcalBurnedTotal: Int? = null,
    /**
     * true = [kcalBurnedTotal] is only Health Connect's BMR estimate (no calorie records that day);
     * false = at least one real calorie record contributed; null = no total or unknown.
     */
    val kcalBurnedBmrOnly: Boolean? = null,
    val kcalActive: Int? = null,
    val steps: Long? = null,
    val restingHr: Long? = null,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val sleepMin: Int? = null,
    val workouts: List<Workout> = emptyList(),
)
