package io.github.kopolot.hcsync.health

import io.github.kopolot.hcsync.domain.HealthSnapshot
import java.time.LocalDate

enum class HcAvailability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

data class HcStatus(
    val availability: HcAvailability,
    val granted: Set<String> = emptySet(),
    val backgroundFeature: Boolean = false,
    val historyFeature: Boolean = false,
) {
    val hasAnyData get() = granted.any { it in Permissions.data }
    val missingData get() = Permissions.data - granted
    val hasBackground get() = Permissions.BACKGROUND in granted
    val hasHistory get() = Permissions.HISTORY in granted
}

/** Per-metric comparison for one day: HC aggregate vs naive sum of raw records, by source app. */
data class MetricDiagnostics(
    val metric: String,
    val aggregate: Double?,
    val rawTotal: Double,
    val rawByOrigin: Map<String, Double>,
    val rawCount: Int,
)

data class DayDiagnostics(
    val date: LocalDate,
    val metrics: List<MetricDiagnostics>,
    val aggregateOrigins: Set<String>,
    val notes: List<String>,
)

interface HealthDataSource {
    suspend fun status(): HcStatus
    /** Reads everything for [from]..[toInclusive] (local days) using only granted permissions. */
    suspend fun read(from: LocalDate, toInclusive: LocalDate): HealthSnapshot
    suspend fun diagnose(date: LocalDate): DayDiagnostics
}
