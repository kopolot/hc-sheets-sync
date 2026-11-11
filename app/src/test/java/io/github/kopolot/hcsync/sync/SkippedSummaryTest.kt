package io.github.kopolot.hcsync.sync

import io.github.kopolot.hcsync.domain.SkippedRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SkippedSummaryTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    private fun w(type: String, start: String) =
        Instant.parse(start).let { SkippedRead(type, it, it.plusSeconds(3000)) }

    @Test
    fun emptyGivesNull() = assertNull(skippedSummary(emptyList(), warsaw))

    @Test
    fun oneLineForManyWindows() {
        val s = skippedSummary(
            listOf(
                w("ActiveCaloriesBurnedRecord", "2026-11-03T05:10:00Z"),
                w("ActiveCaloriesBurnedRecord", "2026-11-05T10:00:00Z"),
                w("SleepSessionRecord", "2026-11-04T02:00:00Z"),
                // 23:30 UTC on the 5th is already the 6th in Warsaw.
                w("ActiveCaloriesBurnedRecord", "2026-11-05T23:30:00Z"),
            ),
            warsaw,
        )
        assertEquals(
            "Okna pominięte przez uszkodzone rekordy HC: 4 (ActiveCaloriesBurnedRecord: 3, SleepSessionRecord: 1); " +
                "dni 2026-11-03 – 2026-11-06 (4)",
            s,
        )
    }

    @Test
    fun singleDay() {
        val s = skippedSummary(listOf(w("WeightRecord", "2026-11-03T08:00:00Z")), warsaw)
        assertEquals("Okna pominięte przez uszkodzone rekordy HC: 1 (WeightRecord: 1); dzień 2026-11-03", s)
    }
}
