package io.github.kopolot.hcsync.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Turns a [HealthSnapshot] into one [DailySummary] per calendar day.
 *
 * Rules:
 * - sums come straight from HC aggregates (already split per local day by HC);
 * - weight / body fat: earliest measurement of the day (morning value);
 * - sleep: sessions assigned to the day they END on, minus awake stages;
 * - workouts: assigned to the day they START on;
 * - kcal_burned_bmr_only: true when no calorie-burn record overlaps the day, i.e. HC's total is pure BMR.
 *
 * A record's own zone offset is used to decide its day when present, otherwise [zone].
 */
object DailyAggregator {

    fun build(
        from: LocalDate,
        toInclusive: LocalDate,
        zone: ZoneId,
        snapshot: HealthSnapshot,
    ): List<DailySummary> {
        require(!toInclusive.isBefore(from)) { "Empty range $from..$toInclusive" }

        val aggByDay = snapshot.aggregates.associateBy { it.date }
        val weightByDay = earliestPerDay(snapshot.weights, zone)
        val fatByDay = earliestPerDay(snapshot.bodyFat, zone)
        val sleepByDay = snapshot.sleep
            .groupBy { localDate(it.end, it.endOffset, zone) }
            .mapValues { (_, sessions) -> sessions.sumOf { asleepDuration(it).toMinutes() } }
        val workoutsByDay = snapshot.workouts
            .groupBy { localDate(it.start, it.startOffset, zone) }
        val burnDays = snapshot.burnRecords?.flatMapTo(mutableSetOf()) { daysCovered(it, zone) }

        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(toInclusive) }
            .map { day ->
                val agg = aggByDay[day]
                DailySummary(
                    date = day,
                    kcalIn = agg?.kcalIn?.roundToInt(),
                    proteinG = agg?.proteinG?.round(1),
                    fatG = agg?.fatG?.round(1),
                    carbsG = agg?.carbsG?.round(1),
                    kcalBurnedTotal = agg?.kcalBurnedTotal?.roundToInt(),
                    kcalBurnedBmrOnly = if (agg?.kcalBurnedTotal == null || burnDays == null) null
                        else day !in burnDays,
                    kcalActive = agg?.kcalActive?.roundToInt(),
                    steps = agg?.steps,
                    restingHr = agg?.restingHr,
                    weightKg = weightByDay[day]?.round(2),
                    bodyFatPct = fatByDay[day]?.round(1),
                    sleepMin = sleepByDay[day]?.toInt(),
                    workouts = workoutsByDay[day].orEmpty()
                        .sortedBy { it.start }
                        .map { it.toWorkout() },
                )
            }
            .toList()
    }

    fun asleepDuration(session: SleepSession): Duration {
        val total = Duration.between(session.start, session.end)
        val awake = session.awakeStages.fold(Duration.ZERO) { acc, stage ->
            val s = maxOf(stage.start, session.start)
            val e = minOf(stage.end, session.end)
            if (e.isAfter(s)) acc + Duration.between(s, e) else acc
        }
        return (total - awake).coerceAtLeast(Duration.ZERO)
    }

    /** Every local day the record overlaps (HC's daily aggregate splits records at midnight the same way). */
    fun daysCovered(r: BurnRecord, zone: ZoneId): Sequence<LocalDate> {
        val first = localDate(r.start, r.startOffset, zone)
        val lastInstant = if (r.end.isAfter(r.start)) r.end.minusNanos(1) else r.start
        val last = maxOf(first, localDate(lastInstant, r.endOffset ?: r.startOffset, zone))
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
    }

    fun localDate(time: Instant, offset: ZoneOffset?, zone: ZoneId): LocalDate =
        if (offset != null) time.atOffset(offset).toLocalDate() else time.atZone(zone).toLocalDate()

    private fun earliestPerDay(samples: List<Measurement>, zone: ZoneId): Map<LocalDate, Double> =
        samples.groupBy { localDate(it.time, it.offset, zone) }
            .mapValues { (_, list) -> list.minBy { it.time }.value }

    private fun WorkoutSession.toWorkout() = Workout(
        id = id,
        type = type,
        title = title,
        start = start,
        end = end,
        minutes = Duration.between(start, end).seconds.let { (it / 60.0).roundToInt() },
    )

    private fun Double.round(decimals: Int): Double {
        var f = 1.0
        repeat(decimals) { f *= 10 }
        return Math.round(this * f) / f
    }

    private fun Duration.coerceAtLeast(min: Duration) = if (this < min) min else this
}
