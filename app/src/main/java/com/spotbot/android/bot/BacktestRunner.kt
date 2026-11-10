package com.spotbot.android.bot

import com.spotbot.android.data.storageJson
import com.spotbot.core.backtest.BacktestConfig
import com.spotbot.core.backtest.BacktestDataLoader
import com.spotbot.core.backtest.BacktestEngine
import com.spotbot.core.backtest.BacktestReport
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.backtest.runResolvingIntrabar
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
    /** [data]: işlem ayrıntı grafikleri için test verisi (yalnız bellekte). */
    data class Done(val report: BacktestReport, val data: Map<String, HistoricalDataset> = emptyMap()) : BacktestState
    data class Isolated(val results: List<com.spotbot.core.backtest.IsolatedRun>, val days: Int, val symbols: List<String>) : BacktestState
    data class Compared(val results: List<com.spotbot.core.backtest.ProfileComparison>, val days: Int, val symbols: List<String>) : BacktestState
    data class Failed(val message: String) : BacktestState
}

/** Uygulama içinden backtest: veriyi MEXC'den indirir (önbelleğe alır) ve çekirdek backtest motorunu çalıştırır. */
class BacktestRunner(private val api: MarketDataApi, private val scope: CoroutineScope, private val cacheDir: File) {
    private val _state = MutableStateFlow<BacktestState>(BacktestState.Idle)
    val state: StateFlow<BacktestState> = _state.asStateFlow()
    private var job: Job? = null

    /** Profilleri aynı veri, dönem, maliyet ve risk ayarlarıyla karşılaştırır (ilk/ikinci yarı ayrımıyla). */
    fun compare(symbols: List<String>, days: Int, capital: Double, engine: EngineConfig, realSpread: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            runCatching {
                val (config, data, info) = prepare(symbols, days, capital, engine.enabledStrategies, engine, realSpread)
                _state.value = BacktestState.Running("Profiller aynı koşullarda çalışıyor", 0.5f)
                com.spotbot.core.backtest.StrategyComparison.run(config.copy(shadowAnalysis = false), data, info) { p ->
                    _state.value = BacktestState.Running("Profiller aynı koşullarda çalışıyor", 0.5f + p.toFloat() / 2)
                }
            }.onSuccess { _state.value = BacktestState.Compared(it, days, symbols) }
                .onFailure { _state.value = BacktestState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    /** A ve B'yi aynı veri, ücret, sermaye ve dönemde ayrı ayrı çalıştırır (ilk/ikinci yarı ayrımıyla). */
    fun isolate(symbols: List<String>, days: Int, capital: Double, engine: EngineConfig, realSpread: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            runCatching {
                val (config, data, info) = prepare(symbols, days, capital, setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK), engine, realSpread)
                _state.value = BacktestState.Running("A ve B aynı koşullarda ayrı çalışıyor", 0.5f)
                com.spotbot.core.backtest.StrategyIsolation.run(config.copy(shadowAnalysis = false), data, info) { p ->
                    _state.value = BacktestState.Running("A ve B aynı koşullarda ayrı çalışıyor", 0.5f + p.toFloat() / 2)
                }
            }.onSuccess { _state.value = BacktestState.Isolated(it, days, symbols) }
                .onFailure { _state.value = BacktestState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private suspend fun prepare(
        symbols: List<String>,
        days: Int,
        capital: Double,
        strategies: Set<StrategyId>,
        engine: EngineConfig,
        realSpread: Boolean = false,
    ): Triple<BacktestConfig, Map<String, HistoricalDataset>, Map<String, com.spotbot.core.model.SymbolInfo>> {
        val end = Timeframe.H4.lastCloseAtOrBefore(api.serverTime())
        val start = end - days * DAY
        val config = BacktestConfig(
            symbols = symbols, startTime = start, endTime = end, initialCapital = capital,
            risk = engine.risk, scanner = engine.scanner, strategyParams = engine.strategyParams,
            analysis = engine.analysis, enabledStrategies = strategies, slippage = engine.slippage,
            execution = engine.execution, shadowAnalysis = true, profile = engine.profile, maxEntryDriftPercent = engine.maxEntryDriftPercent,
            // Ölçülen spread: şu anki en iyi alış/satış farkının yarısı (alınamazsa sabit değere düşer).
            halfSpreadBySymbol = if (realSpread) runCatching {
                api.bookTickers().filter { it.bid > 0 && !it.spreadPercent.isNaN() }.associate { it.symbol to it.spreadPercent / 200 }
            }.getOrDefault(emptyMap()) else emptyMap(),
        )
        val loadStart = start - config.warmupDays * DAY
        val all = (symbols + "BTCUSDT").distinct()
        val data = all.mapIndexed { i, symbol ->
            _state.value = BacktestState.Running("$symbol verisi hazırlanıyor (${i + 1}/${all.size})", i.toFloat() / all.size * 0.5f)
            symbol to load(symbol, loadStart, end)
        }.toMap()
        return Triple(config, data, api.exchangeInfo().associateBy { it.symbol })
    }

    fun run(symbols: List<String>, days: Int, capital: Double, strategies: Set<StrategyId>, engine: EngineConfig, realSpread: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            runCatching {
                val (config, data, info) = prepare(symbols, days, capital, strategies, engine, realSpread)
                _state.value = BacktestState.Running("Backtest çalışıyor", 0.5f)
                // Aynı mumda stop ve hedef görülen durumlar için 5M mumlar indirilir ve test yeniden çalıştırılır.
                val (report, resolved) = BacktestEngine(config).runResolvingIntrabar(data, info, BacktestDataLoader(api)) { p ->
                    _state.value = BacktestState.Running("Backtest çalışıyor", 0.5f + p.toFloat() / 2)
                }
                resolved.forEach { (symbol, d) -> if (d.m5.size != data[symbol]?.m5?.size) save(symbol, config.startTime - config.warmupDays * DAY, config.endTime, d) }
                BacktestState.Done(report, resolved)
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = BacktestState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun cacheFile(symbol: String, start: Long, end: Long) = File(cacheDir, "bt-$symbol-$start-$end.json")

    private fun save(symbol: String, start: Long, end: Long, dataset: HistoricalDataset) =
        runCatching { cacheFile(symbol, start, end).writeText(storageJson.encodeToString(HistoricalDataset.serializer(), dataset)) }

    private suspend fun load(symbol: String, start: Long, end: Long): HistoricalDataset {
        val file = cacheFile(symbol, start, end)
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
