package io.github.kopolot.hcsync.health

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SplittingReaderTest {
    private val start = Instant.parse("2026-11-01T00:00:00Z")
    private val end = start.plus(Duration.ofDays(4))

    /** Fake HC: "records" are instants; the page throws if it contains the bad instant, like the HC library does. */
    private fun fakeRead(records: List<Instant>, bad: Set<Instant>): suspend (Instant, Instant) -> List<Instant> =
        { from, to ->
            val page = records.filter { !it.isBefore(from) && it.isBefore(to) }
            if (page.any { it in bad }) throw IllegalArgumentException("startTime must be before endTime.")
            page
        }

    @Test
    fun noErrorReadsWholeWindowOnce() = runTest {
        var calls = 0
        val records = listOf(start.plusSeconds(10), start.plus(Duration.ofDays(2)))
        val read = fakeRead(records, emptySet())
        val res = readSplitting(start, end) { a, b -> calls++; read(a, b) }
        assertEquals(records, res.items)
        assertTrue(res.skipped.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun badRecordSkipsOnlyItsHour() = runTest {
        val bad = start.plus(Duration.ofHours(30)).plusSeconds(1234)
        val good = (0 until 96).map { start.plus(Duration.ofHours(it.toLong())).plusSeconds(60) }
        val res = readSplitting(start, end, read = fakeRead(good + bad, setOf(bad)))

        assertEquals(1, res.skipped.size)
        val w = res.skipped.single()
        assertTrue(!bad.isBefore(w.start) && bad.isBefore(w.end))
        assertTrue(Duration.between(w.start, w.end) <= Duration.ofHours(1))
        // Every good record outside the skipped window survives.
        val expected = good.filter { it.isBefore(w.start) || !it.isBefore(w.end) }
        assertEquals(expected.sorted(), res.items.sorted())
        assertTrue(res.items.size >= 95)
    }

    @Test
    fun persistentErrorIsRethrown() = runTest {
        try {
            readSplitting<Instant>(start, end, maxSkipped = 3) { _, _ -> throw IllegalArgumentException("broken") }
            fail("expected rethrow")
        } catch (e: IllegalArgumentException) {
            assertEquals("broken", e.message)
        }
    }

    @Test
    fun otherExceptionsAreNotSwallowed() = runTest {
        try {
            readSplitting<Instant>(start, end) { _, _ -> throw IllegalStateException("rate limit") }
            fail("expected rethrow")
        } catch (e: IllegalStateException) {
            assertEquals("rate limit", e.message)
        }
    }
}
