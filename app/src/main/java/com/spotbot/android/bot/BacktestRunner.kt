package com.spotbot.android.bot

import com.spotbot.android.data.storageJson
import com.spotbot.core.backtest.BacktestConfig
import com.spotbot.core.backtest.BacktestDataLoader
import com.spotbot.core.backtest.BacktestEngine
import com.spotbot.core.backtest.BacktestReport
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.engine.EngineConfig
import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.Timeframe
import com.spotbot.core.strategy.StrategyId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface BacktestState {
    data object Idle : BacktestState
    data class Running(val message: String, val progress: Float?) : BacktestState
    data class Done(val report: BacktestReport) : BacktestState
    data class Failed(val message: String) : BacktestState
}

/** Uygulama içinden backtest: veriyi MEXC'den indirir (önbelleğe alır) ve çekirdek backtest motorunu çalıştırır. */
class BacktestRunner(private val api: MarketDataApi, private val scope: CoroutineScope, private val cacheDir: File) {
    private val _state = MutableStateFlow<BacktestState>(BacktestState.Idle)
    val state: StateFlow<BacktestState> = _state.asStateFlow()
    private var job: Job? = null

    fun run(symbols: List<String>, days: Int, capital: Double, strategies: Set<StrategyId>, engine: EngineConfig) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            runCatching {
                val end = Timeframe.H4.lastCloseAtOrBefore(api.serverTime())
                val start = end - days * DAY
                val config = BacktestConfig(
                    symbols = symbols, startTime = start, endTime = end, initialCapital = capital,
                    risk = engine.risk, scanner = engine.scanner, strategyParams = engine.strategyParams,
                    analysis = engine.analysis, enabledStrategies = strategies, slippage = engine.slippage,
                )
                val loadStart = start - config.warmupDays * DAY
                val all = (symbols + "BTCUSDT").distinct()
                val data = all.mapIndexed { i, symbol ->
                    _state.value = BacktestState.Running("$symbol verisi hazırlanıyor (${i + 1}/${all.size})", i.toFloat() / all.size * 0.5f)
                    symbol to load(symbol, loadStart, end)
                }.toMap()
                val info = api.exchangeInfo().associateBy { it.symbol }
                _state.value = BacktestState.Running("Backtest çalışıyor", 0.5f)
                BacktestEngine(config).run(data, info) { p -> _state.value = BacktestState.Running("Backtest çalışıyor", 0.5f + p.toFloat() / 2) }
            }.onSuccess { _state.value = BacktestState.Done(it) }
                .onFailure { _state.value = BacktestState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private suspend fun load(symbol: String, start: Long, end: Long): HistoricalDataset {
        val file = File(cacheDir, "bt-$symbol-$start-$end.json")
        file.takeIf { it.exists() }?.let { cached ->
            runCatching { return storageJson.decodeFromString(HistoricalDataset.serializer(), cached.readText()) }
        }
        cacheDir.listFiles { f -> f.name.startsWith("bt-$symbol-") }?.forEach { it.delete() }
        return BacktestDataLoader(api).load(listOf(symbol), start, end).getValue(symbol).also {
            file.writeText(storageJson.encodeToString(HistoricalDataset.serializer(), it))
        }
    }

    companion object {
        private const val DAY = 24 * 60 * 60_000L
    }
}
