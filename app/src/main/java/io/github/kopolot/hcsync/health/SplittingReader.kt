package io.github.kopolot.hcsync.health

import java.time.Duration
import java.time.Instant

/** A time window that could not be read and was left out. */
data class SkippedWindow(val start: Instant, val end: Instant)

data class SplitReadResult<T>(val items: List<T>, val skipped: List<SkippedWindow>)

/**
 * Reads [start, end) and, when the read throws [IllegalArgumentException], splits the window in half
 * and retries each half, down to [minWindow]. Windows at that size that still fail are skipped.
 *
 * Why: the HC Jetpack library converts a whole page of platform records at once and throws on a single
 * malformed record (e.g. Samsung writes ActiveCaloriesBurned with startTime == endTime, which the library
 * rejects with "startTime must be before endTime"). Without this, one bad record kills the whole sync.
 *
 * If more than [maxSkipped] windows fail, the error is not a single bad record, so it is rethrown.
 */
suspend fun <T> readSplitting(
    start: Instant,
    end: Instant,
    minWindow: Duration = Duration.ofHours(1),
    maxSkipped: Int = 24,
    read: suspend (Instant, Instant) -> List<T>,
): SplitReadResult<T> {
    val items = mutableListOf<T>()
    val skipped = mutableListOf<SkippedWindow>()

    suspend fun go(from: Instant, to: Instant) {
        val chunk = try {
            read(from, to)
        } catch (e: IllegalArgumentException) {
            if (Duration.between(from, to) <= minWindow) {
                skipped += SkippedWindow(from, to)
                if (skipped.size > maxSkipped) throw e
                return
            }
            val mid = from.plus(Duration.between(from, to).dividedBy(2))
            go(from, mid)
            go(mid, to)
            return
        }
        items += chunk
    }

    go(start, end)
    return SplitReadResult(items, skipped)
}
