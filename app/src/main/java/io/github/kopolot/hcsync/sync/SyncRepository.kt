package io.github.kopolot.hcsync.sync

import android.os.RemoteException
import io.github.kopolot.hcsync.data.LogLevel
import io.github.kopolot.hcsync.data.StatusStore
import io.github.kopolot.hcsync.domain.DailyAggregator
import io.github.kopolot.hcsync.domain.SkippedRead
import io.github.kopolot.hcsync.health.HcAvailability
import io.github.kopolot.hcsync.health.HealthDataSource
import io.github.kopolot.hcsync.health.Permissions
import io.github.kopolot.hcsync.net.PermanentSyncException
import io.github.kopolot.hcsync.net.SheetsClient
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import java.time.Clock
import java.time.LocalDate

enum class SyncTrigger { PERIODIC, MANUAL }

sealed interface SyncOutcome {
    data class Success(val message: String) : SyncOutcome
    data class Retry(val message: String) : SyncOutcome
    data class Failure(val message: String) : SyncOutcome
}

class SyncRepository(
    private val source: HealthDataSource,
    private val client: SheetsClient,
    private val store: StatusStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun sync(trigger: SyncTrigger): SyncOutcome {
        val outcome = try {
            doSync(trigger)
        } catch (e: PermanentSyncException) {
            SyncOutcome.Failure(e.message ?: "Błąd wysyłki")
        } catch (e: SecurityException) {
            SyncOutcome.Failure("Brak zgody Health Connect: ${e.message}")
        } catch (e: IOException) {
            SyncOutcome.Retry("Sieć: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: RemoteException) {
            // HC throws RemoteException e.g. when its rate limit is hit.
            SyncOutcome.Retry("Health Connect: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: CancellationException) {
            // Must come before IllegalStateException, which CancellationException extends.
            throw e
        } catch (e: IllegalStateException) {
            SyncOutcome.Retry("Błąd: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            // Anything unexpected must still land in the status/log, otherwise the worker dies silently.
            SyncOutcome.Failure("Nieoczekiwany błąd: ${e.javaClass.simpleName}: ${e.message}")
        }
        when (outcome) {
            is SyncOutcome.Success -> store.recordAttempt(true, outcome.message)
            is SyncOutcome.Retry -> store.recordAttempt(false, "${outcome.message} (ponowię później)")
            is SyncOutcome.Failure -> store.recordAttempt(false, outcome.message)
        }
        return outcome
    }

    private suspend fun doSync(trigger: SyncTrigger): SyncOutcome {
        if (!client.isConfigured) {
            return SyncOutcome.Failure("Brak SYNC_URL / SYNC_SECRET – uzupełnij local.properties i przebuduj apkę")
        }
        val status = source.status()
        when (status.availability) {
            HcAvailability.UNAVAILABLE -> return SyncOutcome.Failure("Health Connect niedostępny na tym urządzeniu")
            HcAvailability.UPDATE_REQUIRED -> return SyncOutcome.Failure("Health Connect wymaga aktualizacji")
            HcAvailability.AVAILABLE -> Unit
        }
        if (!status.hasAnyData) return SyncOutcome.Failure("Brak zgód na odczyt danych z Health Connect")
        if (trigger == SyncTrigger.PERIODIC && !status.hasBackground) {
            return SyncOutcome.Failure("Brak zgody na odczyt w tle – sync automatyczny nie zadziała")
        }
        if (status.missingData.isNotEmpty()) {
            val labels = Permissions.dataTypes.filter { Permissions.read(it.first) in status.missingData }.map { it.second }
            store.log(LogLevel.WARN, "Brak zgód na: ${labels.joinToString()} – te pola będą puste")
        }

        val zone = clock.zone
        val today = LocalDate.now(clock)
        val backfill = !store.current().backfillDone
        val dayCount = when {
            !backfill -> REGULAR_DAYS
            status.hasHistory -> BACKFILL_DAYS_WITH_HISTORY
            else -> BACKFILL_DAYS_WITHOUT_HISTORY
        }
        val from = today.minusDays(dayCount - 1L)

        var daysSent = 0
        var workoutsSent = 0
        val skipped = mutableListOf<SkippedRead>()
        var chunkStart = from
        while (!chunkStart.isAfter(today)) {
            val chunkEnd = minOf(chunkStart.plusDays(CHUNK_DAYS - 1L), today)
            val snapshot = source.read(chunkStart, chunkEnd)
            skipped += snapshot.skipped
            val summaries = DailyAggregator.build(chunkStart, chunkEnd, zone, snapshot)
            val resp = client.upload(summaries)
            daysSent += resp.days ?: summaries.size
            workoutsSent += resp.workouts ?: summaries.sumOf { it.workouts.size }
            chunkStart = chunkEnd.plusDays(1)
        }
        if (backfill) store.setBackfillDone(true)
        skippedSummary(skipped, zone)?.let { store.log(LogLevel.WARN, it) }

        val kind = if (backfill) "backfill $dayCount dni" else "ostatnie $dayCount dni"
        return SyncOutcome.Success("OK: $kind ($from – $today), dni: $daysSent, treningi: $workoutsSent")
    }

    companion object {
        const val REGULAR_DAYS = 7
        const val BACKFILL_DAYS_WITH_HISTORY = 90
        const val BACKFILL_DAYS_WITHOUT_HISTORY = 30
        const val CHUNK_DAYS = 30
    }
}
