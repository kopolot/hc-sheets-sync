package io.github.kopolot.hcsync

import android.content.Context
import io.github.kopolot.hcsync.data.StatusStore
import io.github.kopolot.hcsync.health.HealthConnectDataSource
import io.github.kopolot.hcsync.health.HealthDataSource
import io.github.kopolot.hcsync.net.SheetsClient
import io.github.kopolot.hcsync.sync.SyncRepository

/** Manual DI – the app is small enough not to need Hilt. */
class AppContainer(context: Context) {
    val statusStore = StatusStore(context)
    val healthDataSource: HealthDataSource = HealthConnectDataSource(context)
    val sheetsClient = SheetsClient(BuildConfig.SYNC_URL, BuildConfig.SYNC_SECRET, BuildConfig.VERSION_NAME)
    val syncRepository = SyncRepository(healthDataSource, sheetsClient, statusStore)
}
