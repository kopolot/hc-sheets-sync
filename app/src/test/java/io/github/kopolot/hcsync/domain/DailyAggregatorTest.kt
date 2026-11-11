package io.github.kopolot.hcsync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DailyAggregatorTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(warsaw).toInstant()
    private fun d(s: String) = LocalDate.parse(s)

    private fun build(from: String, to: String, snapshot: HealthSnapshot) =
        DailyAggregator.build(d(from), d(to), warsaw, snapshot)

    @Test
    fun `one summary per day in range, inclusive`() {
        val out = build("2026-11-04", "2026-11-10", HealthSnapshot())
        assertEquals(7, out.size)
        assertEquals(d("2026-11-04"), out.first().date)
        assertEquals(d("2026-11-10"), out.last().date)
    }

    @Test
    fun `no data gives nulls not zeros`() {
        val day = build("2026-11-10", "2026-11-10", HealthSnapshot()).single()
        assertNull(day.kcalIn)
        assertNull(day.proteinG)
        assertNull(day.kcalBurnedTotal)
        assertNull(day.steps)
        assertNull(day.weightKg)
        assertNull(day.bodyFatPct)
        assertNull(day.sleepMin)
        assertNull(day.restingHr)
        assertTrue(day.workouts.isEmpty())
    }

    @Test
    fun `aggregates are merged by date and rounded`() {
        val snap = HealthSnapshot(
            aggregates = listOf(
                DayAggregate(d("2026-11-09"), kcalIn = 2349.6, proteinG = 160.04, fatG = 70.06, carbsG = 250.0,
                    kcalBurnedTotal = 2100.4, kcalActive = 450.5, steps = 9500, restingHr = 54),
                DayAggregate(d("2026-11-10"), kcalIn = 500.0),
            ),
        )
        val out = build("2026-11-08", "2026-11-10", snap).associateBy { it.date }
        assertNull(out.getValue(d("2026-11-08")).kcalIn)
        with(out.getValue(d("2026-11-09"))) {
            assertEquals(2350, kcalIn)
            assertEquals(160.0, proteinG!!, 0.0)
            assertEquals(70.1, fatG!!, 0.0)
            assertEquals(2100, kcalBurnedTotal)
            assertEquals(451, kcalActive)
            assertEquals(9500L, steps)
            assertEquals(54L, restingHr)
        }
        assertEquals(500, out.getValue(d("2026-11-10")).kcalIn)
        assertNull(out.getValue(d("2026-11-10")).steps)
    }

    @Test
    fun `bmr-only flag marks days without calorie records`() {
        val snap = HealthSnapshot(
            aggregates = listOf(
                DayAggregate(d("2026-11-08"), kcalBurnedTotal = 1565.0),
                DayAggregate(d("2026-11-09"), kcalBurnedTotal = 1965.0, kcalActive = 400.0),
                DayAggregate(d("2026-11-10"), kcalBurnedTotal = 1800.0),
                DayAggregate(d("2026-11-11"), kcalBurnedTotal = 1700.0),
            ),
            burnRecords = listOf(
                BurnRecord(at("2026-11-09T10:00"), at("2026-11-09T11:00")),
                // crosses midnight: counts for both days, like HC's daily slicing
                BurnRecord(at("2026-11-10T23:30"), at("2026-11-11T00:30")),
            ),
        )
        val out = build("2026-11-07", "2026-11-11", snap).associateBy { it.date }
        assertNull("no total -> no flag", out.getValue(d("2026-11-07")).kcalBurnedBmrOnly)
        assertEquals(true, out.getValue(d("2026-11-08")).kcalBurnedBmrOnly)
        assertEquals(false, out.getValue(d("2026-11-09")).kcalBurnedBmrOnly)
        assertEquals(false, out.getValue(d("2026-11-10")).kcalBurnedBmrOnly)
        assertEquals(false, out.getValue(d("2026-11-11")).kcalBurnedBmrOnly)
    }

    @Test
    fun `bmr-only flag is null when records could not be read`() {
        val snap = HealthSnapshot(aggregates = listOf(DayAggregate(d("2026-11-10"), kcalBurnedTotal = 1565.0)))
        assertNull(build("2026-11-10", "2026-11-10", snap).single().kcalBurnedBmrOnly)
    }

    @Test
    fun `record ending exactly at midnight does not touch next day`() {
        val r = BurnRecord(at("2026-11-09T23:00"), at("2026-11-10T00:00"))
        assertEquals(listOf(d("2026-11-09")), DailyAggregator.daysCovered(r, warsaw).toList())
    }

    @Test
    fun `aggregates outside range are ignored`() {
        val snap = HealthSnapshot(aggregates = listOf(DayAggregate(d("2026-11-01"), kcalIn = 1.0)))
        val out = build("2026-11-09", "2026-11-10", snap)
        assertTrue(out.all { it.kcalIn == null })
    }

    @Test
    fun `earliest weight of the day wins`() {
        val snap = HealthSnapshot(
            weights = listOf(
                Measurement(at("2026-11-10T20:00"), 83.9),
                Measurement(at("2026-11-10T06:30"), 82.456),
                Measurement(at("2026-11-10T12:00"), 83.1),
                Measurement(at("2026-11-09T07:00"), 82.8),
            ),
            bodyFat = listOf(
                Measurement(at("2026-11-10T21:00"), 18.0),
                Measurement(at("2026-11-10T06:31"), 17.14),
            ),
        )
        val out = build("2026-11-09", "2026-11-10", snap).associateBy { it.date }
        assertEquals(82.8, out.getValue(d("2026-11-09")).weightKg!!, 0.0)
        assertEquals(82.46, out.getValue(d("2026-11-10")).weightKg!!, 0.0)
        assertEquals(17.1, out.getValue(d("2026-11-10")).bodyFatPct!!, 0.0)
        assertNull(out.getValue(d("2026-11-09")).bodyFatPct)
    }

    @Test
    fun `late evening weight in UTC still lands on local day`() {
        // 23:30 Warsaw = 22:30 UTC same day; 00:30 Warsaw = previous day in UTC.
        val snap = HealthSnapshot(weights = listOf(Measurement(at("2026-11-11T00:30"), 80.0)))
        val out = build("2026-11-10", "2026-11-11", snap).associateBy { it.date }
        assertNull(out.getValue(d("2026-11-10")).weightKg)
        assertEquals(80.0, out.getValue(d("2026-11-11")).weightKg!!, 0.0)
    }

    @Test
    fun `record offset overrides device zone`() {
        // Measured in New York at 21:00 local on Nov 9 = 03:00 Nov 10 in Warsaw.
        val ny = ZoneOffset.ofHours(-5)
        val t = LocalDateTime.parse("2026-11-09T21:00").toInstant(ny)
        val snap = HealthSnapshot(weights = listOf(Measurement(t, 80.0, offset = ny)))
        val out = build("2026-11-09", "2026-11-10", snap).associateBy { it.date }
        assertEquals(80.0, out.getValue(d("2026-11-09")).weightKg!!, 0.0)
        assertNull(out.getValue(d("2026-11-10")).weightKg)
    }

    @Test
    fun `workout crossing midnight belongs to start day`() {
        val snap = HealthSnapshot(
            workouts = listOf(
                WorkoutSession("w1", "walking", null, at("2026-11-09T23:30"), at("2026-11-10T00:20")),
            ),
        )
        val out = build("2026-11-09", "2026-11-10", snap).associateBy { it.date }
        val w = out.getValue(d("2026-11-09")).workouts.single()
        assertEquals("w1", w.id)
        assertEquals(50, w.minutes)
        assertTrue(out.getValue(d("2026-11-10")).workouts.isEmpty())
    }

    @Test
    fun `multiple workouts sorted by start`() {
        val snap = HealthSnapshot(
            workouts = listOf(
                WorkoutSession("b", "walking", null, at("2026-11-10T18:00"), at("2026-11-10T18:30")),
                WorkoutSession("a", "strength_training", "Push", at("2026-11-10T07:00"), at("2026-11-10T08:02")),
            ),
        )
        val ws = build("2026-11-10", "2026-11-10", snap).single().workouts
        assertEquals(listOf("a", "b"), ws.map { it.id })
        assertEquals(62, ws[0].minutes)
        assertEquals("Push", ws[0].title)
    }

    @Test
    fun `sleep goes to the day it ends on, minus awake stages`() {
        val snap = HealthSnapshot(
            sleep = listOf(
                SleepSession(
                    start = at("2026-11-09T23:00"),
                    end = at("2026-11-10T07:00"),
                    awakeStages = listOf(
                        TimeInterval(at("2026-11-10T03:00"), at("2026-11-10T03:20")),
                        // partly outside the session: only 10 min count
                        TimeInterval(at("2026-11-10T06:50"), at("2026-11-10T07:30")),
                    ),
                ),
            ),
        )
        val out = build("2026-11-09", "2026-11-10", snap).associateBy { it.date }
        assertNull(out.getValue(d("2026-11-09")).sleepMin)
        assertEquals(8 * 60 - 30, out.getValue(d("2026-11-10")).sleepMin)
    }

    @Test
    fun `nap and night on the same day are summed`() {
        val snap = HealthSnapshot(
            sleep = listOf(
                SleepSession(at("2026-11-09T23:00"), at("2026-11-10T06:00")),
                SleepSession(at("2026-11-10T14:00"), at("2026-11-10T14:30")),
            ),
        )
        assertEquals(7 * 60 + 30, build("2026-11-10", "2026-11-10", snap).single().sleepMin)
    }

    @Test
    fun `asleep duration never negative`() {
        val s = SleepSession(
            at("2026-11-10T01:00"), at("2026-11-10T02:00"),
            awakeStages = listOf(TimeInterval(at("2026-11-10T00:00"), at("2026-11-10T03:00"))),
        )
        assertEquals(Duration.ZERO, DailyAggregator.asleepDuration(s))
    }

    @Test
    fun `DST spring forward - night is one hour shorter in wall time`() {
        // 2026-03-29 02:00 CET -> 03:00 CEST. 23:00 -> 07:00 wall clock = 7 real hours.
        val snap = HealthSnapshot(
            sleep = listOf(SleepSession(at("2026-03-28T23:00"), at("2026-03-29T07:00"))),
            workouts = listOf(
                WorkoutSession("w", "running", null, at("2026-03-29T01:30"), at("2026-03-29T03:30")),
            ),
        )
        val day = build("2026-03-29", "2026-03-29", snap).single()
        assertEquals(7 * 60, day.sleepMin)
        assertEquals(60, day.workouts.single().minutes)
    }

    @Test
    fun `DST fall back - night is one hour longer, late events stay on same day`() {
        // 2026-10-25 03:00 CEST -> 02:00 CET.
        val snap = HealthSnapshot(
            sleep = listOf(SleepSession(at("2026-10-24T23:00"), at("2026-10-25T07:00"))),
            weights = listOf(Measurement(at("2026-10-25T23:59"), 81.0)),
        )
        val out = build("2026-10-25", "2026-10-26", snap).associateBy { it.date }
        assertEquals(9 * 60, out.getValue(d("2026-10-25")).sleepMin)
        assertEquals(81.0, out.getValue(d("2026-10-25")).weightKg!!, 0.0)
        assertNull(out.getValue(d("2026-10-26")).weightKg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reversed range is rejected`() {
        build("2026-11-10", "2026-11-09", HealthSnapshot())
    }
}
