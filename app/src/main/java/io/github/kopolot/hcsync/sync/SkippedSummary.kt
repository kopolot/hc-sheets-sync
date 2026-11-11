package io.github.kopolot.hcsync.sync

import io.github.kopolot.hcsync.domain.SkippedRead
import java.time.ZoneId

/**
 * One log line for all windows skipped in a sync run, so persistent bad records in HC do not
 * flood the 50-entry log with a WARN per hour on every run. Null when nothing was skipped.
 */
fun skippedSummary(skipped: List<SkippedRead>, zone: ZoneId): String? {
    if (skipped.isEmpty()) return null
    val perType = skipped.groupingBy { it.recordType }.eachCount().entries
        .sortedBy { it.key }
        .joinToString { "${it.key}: ${it.value}" }
    val days = skipped.map { it.start.atZone(zone).toLocalDate() }.distinct().sorted()
    val range = if (days.size == 1) "dzień ${days.first()}" else "dni ${days.first()} – ${days.last()} (${days.size})"
    return "Okna pominięte przez uszkodzone rekordy HC: ${skipped.size} ($perType); $range"
}
