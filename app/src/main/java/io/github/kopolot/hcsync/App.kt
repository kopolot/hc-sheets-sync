package io.github.kopolot.hcsync

import android.app.Application
import io.github.kopolot.hcsync.sync.SyncScheduler

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
    }
}
