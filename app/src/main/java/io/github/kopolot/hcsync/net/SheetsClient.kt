package io.github.kopolot.hcsync.net

import io.github.kopolot.hcsync.domain.DailySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown for failures that will not fix themselves by retrying (bad secret, bad URL, bad deploy). */
class PermanentSyncException(message: String) : Exception(message)

/**
 * Posts daily summaries to the Apps Script Web App.
 *
 * Apps Script answers a POST with 302 -> script.googleusercontent.com; OkHttp follows it as a GET
 * and that GET returns the JSON produced by doPost. The write already happened on the POST.
 */
class SheetsClient(
    private val url: String,
    private val secret: String,
    private val appVersion: String,
    private val http: OkHttpClient = defaultClient(),
) {
    val isConfigured: Boolean get() = url.isNotBlank() && secret.isNotBlank()

    /** @throws IOException on network / 5xx (retryable), [PermanentSyncException] otherwise. */
    suspend fun upload(days: List<DailySummary>): SyncResponse = withContext(Dispatchers.IO) {
        if (!isConfigured) throw PermanentSyncException("Brak SYNC_URL / SYNC_SECRET w local.properties")
        val body = PayloadJson.encodeToString(
            SyncPayload.serializer(),
            SyncPayload(secret = secret, appVersion = appVersion, days = days.map { it.toDto() }),
        )
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            when {
                resp.code >= 500 -> throw IOException("HTTP ${resp.code} z Apps Script")
                !resp.isSuccessful -> throw PermanentSyncException("HTTP ${resp.code}: ${text.take(200)}")
            }
            val parsed = try {
                PayloadJson.decodeFromString(SyncResponse.serializer(), text)
            } catch (e: SerializationException) {
                throw PermanentSyncException(
                    "Odpowiedź nie jest JSON-em (deployment musi mieć 'Who has access: Anyone'): ${text.take(120)}"
                )
            } catch (e: IllegalArgumentException) {
                throw PermanentSyncException("Niepoprawna odpowiedź: ${text.take(120)}")
            }
            if (!parsed.ok) throw PermanentSyncException("Apps Script: ${parsed.error ?: "nieznany błąd"}")
            parsed
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
