package com.spotbot.android.bot

import com.spotbot.core.kr.HistoryOutcome
import com.spotbot.core.kr.HistoryProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Geçmiş testin isteği: başlangıç günü (UTC 00:00), bitiş (hariç), başlangıç sanal bakiyesi. */
data class HistoryRequest(val from: Long, val to: Long, val equity: Double, val label: String)

sealed interface HistoryState {
    data object Idle : HistoryState
    data class Running(val request: HistoryRequest, val progress: HistoryProgress) : HistoryState
    data class Done(val request: HistoryRequest, val outcome: HistoryOutcome) : HistoryState
    data class Failed(val request: HistoryRequest, val message: String) : HistoryState
    data class Cancelled(val request: HistoryRequest) : HistoryState
}

/**
 * Uygulama içi geçmiş testi çalıştırır. Karar motoru paper ile aynıdır ([com.spotbot.core.kr.KrHistory] →
 * [com.spotbot.core.kr.KrBacktest]). Bir anda tek test çalışır; iptal düğmesi işi durdurur.
 */
class HistoryRunner(
    private val scope: CoroutineScope,
    private val engine: suspend (HistoryRequest, (HistoryProgress) -> Unit) -> HistoryOutcome,
) {
    private val _state = MutableStateFlow<HistoryState>(HistoryState.Idle)
    val state: StateFlow<HistoryState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(request: HistoryRequest) {
        if (job?.isActive == true) return
        _state.value = HistoryState.Running(request, HistoryProgress("Hazırlanıyor", 0, 1))
        job = scope.launch {
            try {
                val outcome = engine(request) { p -> if (_state.value is HistoryState.Running) _state.value = HistoryState.Running(request, p) }
                _state.value = HistoryState.Done(request, outcome)
            } catch (e: CancellationException) {
                _state.value = HistoryState.Cancelled(request)
            } catch (e: Exception) {
                _state.value = HistoryState.Failed(request, e.message ?: e.toString())
            }
        }
    }

    fun cancel() {
        val running = _state.value as? HistoryState.Running ?: return
        job?.cancel()
        _state.value = HistoryState.Cancelled(running.request)
    }

    fun reset() { if (job?.isActive != true) _state.value = HistoryState.Idle }
}
