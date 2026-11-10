package io.github.kopolot.hcsync.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.statusDataStore by preferencesDataStore(name = "sync_status")

enum class LogLevel { INFO, WARN, ERROR }

@Serializable
data class LogEntry(val time: Long, val level: LogLevel, val message: String)

data class SyncStatus(
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastResult: String? = null,
    val lastResultOk: Boolean? = null,
    val backfillDone: Boolean = false,
    val log: List<LogEntry> = emptyList(),
)

/** Small persistent state: last sync, backfill flag and a ring buffer of log lines. No health data. */
class StatusStore(private val context: Context) {
    private val store get() = context.statusDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val logSerializer = ListSerializer(LogEntry.serializer())

    val status: Flow<SyncStatus> = store.data.map { it.toStatus() }

    suspend fun current(): SyncStatus = status.first()

    suspend fun log(level: LogLevel, message: String, now: Long = System.currentTimeMillis()) {
        store.edit { prefs ->
            val list = decodeLog(prefs[LOG]) + LogEntry(now, level, message)
            prefs[LOG] = json.encodeToString(logSerializer, list.takeLast(MAX_LOG))
        }
    }

    suspend fun recordAttempt(ok: Boolean, result: String, now: Long = System.currentTimeMillis()) {
        store.edit { prefs ->
            prefs[LAST_ATTEMPT] = now
            if (ok) prefs[LAST_SUCCESS] = now
            prefs[LAST_RESULT] = result
            prefs[LAST_OK] = ok
        }
        log(if (ok) LogLevel.INFO else LogLevel.ERROR, result, now)
    }

    suspend fun setBackfillDone(done: Boolean) {
        store.edit { it[BACKFILL_DONE] = done }
    }

    suspend fun clearLog() {
        store.edit { it.remove(LOG) }
    }

    private fun Preferences.toStatus() = SyncStatus(
        lastAttemptAt = this[LAST_ATTEMPT],
        lastSuccessAt = this[LAST_SUCCESS],
        lastResult = this[LAST_RESULT],
        lastResultOk = this[LAST_OK],
        backfillDone = this[BACKFILL_DONE] ?: false,
        log = decodeLog(this[LOG]),
    )

    private fun decodeLog(raw: String?): List<LogEntry> =
        raw?.let { runCatching { json.decodeFromString(logSerializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val MAX_LOG = 50
        val LAST_ATTEMPT = longPreferencesKey("last_attempt_at")
        val LAST_SUCCESS = longPreferencesKey("last_success_at")
        val LAST_RESULT = stringPreferencesKey("last_result")
        val LAST_OK = booleanPreferencesKey("last_result_ok")
        val BACKFILL_DONE = booleanPreferencesKey("backfill_done")
        val LOG = stringPreferencesKey("log")
    }
}
