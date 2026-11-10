package io.github.kopolot.hcsync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.kopolot.hcsync.App
import io.github.kopolot.hcsync.BuildConfig
import io.github.kopolot.hcsync.data.SyncStatus
import io.github.kopolot.hcsync.health.DayDiagnostics
import io.github.kopolot.hcsync.health.HcStatus
import io.github.kopolot.hcsync.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class WorkState(val manualRunning: Boolean = false, val nextPeriodicRunAt: Long? = null)

sealed interface DiagnosticsState {
    data object Idle : DiagnosticsState
    data object Loading : DiagnosticsState
    data class Ready(val report: DayDiagnostics) : DiagnosticsState
    data class Error(val message: String) : DiagnosticsState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as App).container
    private val workManager = WorkManager.getInstance(app)

    val configured: Boolean = BuildConfig.SYNC_URL.isNotBlank() && BuildConfig.SYNC_SECRET.isNotBlank()

    private val _hcStatus = MutableStateFlow<HcStatus?>(null)
    val hcStatus: StateFlow<HcStatus?> = _hcStatus.asStateFlow()

    val syncStatus: StateFlow<SyncStatus> = container.statusStore.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())

    val workState: StateFlow<WorkState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.MANUAL_WORK),
        workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.PERIODIC_WORK),
    ) { manual, periodic ->
        WorkState(
            manualRunning = manual.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED },
            nextPeriodicRunAt = periodic.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis
                ?.takeIf { it != Long.MAX_VALUE },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkState())

    private val _diagnostics = MutableStateFlow<DiagnosticsState>(DiagnosticsState.Idle)
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()

    private val _diagDate = MutableStateFlow(LocalDate.now())
    val diagDate: StateFlow<LocalDate> = _diagDate.asStateFlow()


    fun refresh() {
        viewModelScope.launch {
            _hcStatus.value = runCatching { container.healthDataSource.status() }.getOrNull()
        }
    }

    fun syncNow() = SyncScheduler.syncNow(getApplication())

    fun clearLog() {
        viewModelScope.launch { container.statusStore.clearLog() }
    }

    fun resetBackfill() {
        viewModelScope.launch { container.statusStore.setBackfillDone(false) }
    }

    fun shiftDiagDate(days: Long) {
        val next = _diagDate.value.plusDays(days)
        if (!next.isAfter(LocalDate.now())) _diagDate.value = next
        _diagnostics.value = DiagnosticsState.Idle
    }

    fun runDiagnostics() {
        _diagnostics.value = DiagnosticsState.Loading
        viewModelScope.launch {
            _diagnostics.value = try {
                DiagnosticsState.Ready(container.healthDataSource.diagnose(_diagDate.value))
            } catch (e: Exception) {
                DiagnosticsState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
