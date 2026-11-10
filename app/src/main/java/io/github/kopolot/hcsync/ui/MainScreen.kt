package io.github.kopolot.hcsync.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kopolot.hcsync.data.LogLevel
import io.github.kopolot.hcsync.health.HcAvailability
import io.github.kopolot.hcsync.health.HcStatus
import io.github.kopolot.hcsync.health.Permissions
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val ok = Color(0xFF2E7D32)
private val bad = Color(0xFFC62828)

@Composable
fun MainScreen(vm: MainViewModel) {
    val hc by vm.hcStatus.collectAsStateWithLifecycle()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val work by vm.workState.collectAsStateWithLifecycle()
    val diag by vm.diagnostics.collectAsStateWithLifecycle()
    val diagDate by vm.diagDate.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { vm.refresh() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("HC → Sheets", style = MaterialTheme.typography.headlineMedium)

            if (!vm.configured) {
                Section("Konfiguracja") {
                    Text("Brak SYNC_URL / SYNC_SECRET. Uzupełnij local.properties i przebuduj apkę.", color = bad)
                }
            }

            PermissionsSection(
                hc = hc,
                onRequestData = { permissionLauncher.launch(Permissions.data) },
                onRequestExtra = { permissionLauncher.launch(Permissions.extra) },
            )

            Section("Sync") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::syncNow, enabled = !work.manualRunning) { Text("Sync teraz") }
                    if (work.manualRunning) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.width(24.dp))
                    }
                }
                KeyValue("Ostatni udany", status.lastSuccessAt.fmt())
                KeyValue("Ostatnia próba", status.lastAttemptAt.fmt())
                KeyValue("Następny automatyczny", work.nextPeriodicRunAt.fmt())
                KeyValue("Backfill", if (status.backfillDone) "zrobiony" else "przy najbliższym syncu")
                status.lastResult?.let {
                    Text(it, color = if (status.lastResultOk == true) ok else bad)
                }
                if (status.backfillDone) {
                    TextButton(onClick = vm::resetBackfill) { Text("Powtórz backfill przy następnym syncu") }
                }
            }

            Section("Diagnostyka") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.shiftDiagDate(-1) }) { Text("◀") }
                    Text(diagDate.toString(), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.shiftDiagDate(1) }) { Text("▶") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = vm::runDiagnostics, enabled = hc?.hasAnyData == true) { Text("Porównaj") }
                }
                when (val d = diag) {
                    DiagnosticsState.Idle -> Text(
                        "Porównuje agregat Health Connect z prostą sumą surowych rekordów i pokazuje, które aplikacje je zapisały.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DiagnosticsState.Loading -> CircularProgressIndicator()
                    is DiagnosticsState.Error -> Text(d.message, color = bad)
                    is DiagnosticsState.Ready -> {
                        d.report.metrics.forEach { m ->
                            HorizontalDivider()
                            Text(m.metric, style = MaterialTheme.typography.titleSmall)
                            KeyValue("Agregat HC", m.aggregate?.let { "%.1f".format(Locale.ROOT, it) } ?: "—")
                            KeyValue("Suma surowa (${m.rawCount} rek.)", "%.1f".format(Locale.ROOT, m.rawTotal))
                            m.rawByOrigin.forEach { (pkg, v) -> KeyValue("  $pkg", "%.1f".format(Locale.ROOT, v)) }
                        }
                        HorizontalDivider()
                        KeyValue("Źródła w agregacie", d.report.aggregateOrigins.joinToString().ifEmpty { "—" })
                        d.report.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Section("Log") {
                if (status.log.isEmpty()) Text("Pusto")
                status.log.asReversed().forEach { e ->
                    Text(
                        "${e.time.fmt()}  ${e.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (e.level) {
                            LogLevel.ERROR -> bad
                            LogLevel.WARN -> Color(0xFFEF6C00)
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (status.log.isNotEmpty()) TextButton(onClick = vm::clearLog) { Text("Wyczyść log") }
            }
        }
    }
}

@Composable
private fun PermissionsSection(hc: HcStatus?, onRequestData: () -> Unit, onRequestExtra: () -> Unit) {
    val context = LocalContext.current
    Section("Health Connect") {
        when (hc?.availability) {
            null -> Text("Sprawdzam…")
            HcAvailability.UNAVAILABLE -> Text("Health Connect jest niedostępny na tym urządzeniu.", color = bad)
            HcAvailability.UPDATE_REQUIRED -> {
                Text("Health Connect wymaga aktualizacji.", color = bad)
                Button(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "market://details?id=com.google.android.apps.healthdata".toUri())
                    )
                }) { Text("Otwórz sklep") }
            }
            HcAvailability.AVAILABLE -> {
                Permissions.dataTypes.forEach { (type, label) ->
                    Granted(label, Permissions.read(type) in hc.granted)
                }
                Granted(
                    "Odczyt w tle" + if (!hc.backgroundFeature) " (niedostępne)" else "",
                    hc.hasBackground,
                )
                Granted(
                    "Odczyt historii > 30 dni" + if (!hc.historyFeature) " (niedostępne)" else "",
                    hc.hasHistory,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hc.missingData.isNotEmpty()) Button(onClick = onRequestData) { Text("Nadaj zgody") }
                    if (hc.hasAnyData && (!hc.hasBackground || !hc.hasHistory)) {
                        OutlinedButton(onClick = onRequestExtra) { Text("Tło + historia") }
                    }
                }
                TextButton(onClick = {
                    context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                }) { Text("Ustawienia Health Connect") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Granted(label: String, granted: Boolean) {
    Row {
        Text(if (granted) "✓" else "✗", color = if (granted) ok else bad, modifier = Modifier.width(24.dp))
        Text(label)
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Long?.fmt(): String =
    this?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—"
