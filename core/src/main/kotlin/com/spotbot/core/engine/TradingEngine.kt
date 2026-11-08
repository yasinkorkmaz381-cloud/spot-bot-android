package com.spotbot.core.engine

import com.spotbot.core.analysis.AnalysisConfig
import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Series
import com.spotbot.core.exchange.FeedHealth
import com.spotbot.core.exchange.LivePriceFeed
import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.exchange.mexc.MexcWebSocketClient
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Timeframe
import com.spotbot.core.risk.CostModel
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.rules.RuleConfig
import com.spotbot.core.rules.RuleEngine
import com.spotbot.core.scanner.CoinMarketData
import com.spotbot.core.scanner.CoinStatus
import com.spotbot.core.scanner.ScanReport
import com.spotbot.core.scanner.Scanner
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.StrategyParams
import com.spotbot.core.trading.Candidate
import com.spotbot.core.trading.CandidateDecision
import com.spotbot.core.trading.ClosedTrade
import com.spotbot.core.trading.Decision
import com.spotbot.core.trading.DecisionContext
import com.spotbot.core.trading.DecisionOutcome
import com.spotbot.core.trading.DecisionPipeline
import com.spotbot.core.trading.ExitReason
import com.spotbot.core.trading.ExitRules
import com.spotbot.core.trading.EntryMode
import com.spotbot.core.trading.ExecutionConfig
import com.spotbot.core.trading.ExitManager
import com.spotbot.core.trading.asPendingPosition
import com.spotbot.core.trading.PaperAccount
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.Position
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class EngineConfig(
    val initialCapital: Double = 1_000.0,
    val scanner: ScannerConfig = ScannerConfig(),
    val risk: RiskConfig = RiskConfig(),
    val strategyParams: StrategyParams = StrategyParams(),
    val analysis: AnalysisConfig = AnalysisConfig(),
    val enabledStrategies: Set<StrategyId> = setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK),
    val slippage: Double = 0.0005,
    /** Canlı spread bilinmiyorsa kullanılacak yarım spread oranı. */
    val fallbackHalfSpread: Double = 0.00025,
    /** Başlangıçta yüklenecek mum sayısı (MEXC sınırı 500). */
    val historyBars: Int = 400,
    /** Mum kapanışından sonra verinin oturması için beklenen süre. */
    val candleSettleMillis: Long = 3_000,
    /** Sinyal kapanışı ile anlık alış fiyatı arasında izin verilen en büyük kayma (%). */
    val maxEntryDriftPercent: Double = 0.3,
    val universeRefreshMillis: Long = 4 * 60 * 60_000L,
    val execution: ExecutionConfig = ExecutionConfig(),
)

/** Portföy durumunu kalıcı saklama (Android'de uygulama dosyası, CLI'da yerel JSON). */
interface StateStore {
    fun load(): PortfolioState?
    fun save(state: PortfolioState)
}

class InMemoryStateStore(private var state: PortfolioState? = null) : StateStore {
    override fun load() = state
    override fun save(state: PortfolioState) { this.state = state }
}

enum class EngineStatus { STOPPED, STARTING, RUNNING, ERROR }

data class EngineHealth(
    val status: EngineStatus = EngineStatus.STOPPED,
    val entriesPaused: Boolean = false,
    val feed: FeedHealth = FeedHealth(),
    val lastCandleSyncAt: Long? = null,
    val lastScanAt: Long? = null,
    val lastError: String? = null,
    val serverTimeOffsetMillis: Long = 0,
    val universe: List<String> = emptyList(),
    val message: String? = null,
    /** Bekleyen limit giriş emirleri (ör. "ETHUSDT @ 2751.7"). */
    val pendingOrders: List<String> = emptyList(),
)

sealed interface EngineEvent {
    data class Opened(val position: Position) : EngineEvent
    data class Closed(val trade: ClosedTrade) : EngineEvent
    data class Problem(val message: String) : EngineEvent
}

/**
 * 7/24 çalışan sanal işlem (paper trading) motoru. MEXC canlı verisini kullanır, gerçek emir göndermez.
 *
 * Akış: her 15M kapanışında kapanmış mumlar REST ile alınır → tarama → en iyi 2–4 aday → strateji →
 * zorunlu/kalite kuralları → risk → sanal giriş. Açık pozisyonların stop/hedefi WebSocket fiyatlarıyla
 * anlık, yapısal bozulması 1H kapanışlarında izlenir.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TradingEngine(
    private val api: MarketDataApi,
    webSocket: MexcWebSocketClient,
    private val scope: CoroutineScope,
    private val store: StateStore,
    config: EngineConfig = EngineConfig(),
    private val ruleConfig: RuleConfig = RuleConfig.default(),
    private val timeSource: TimeSource = TimeSource.System,
) {
    var config: EngineConfig = config
        private set

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val feed = LivePriceFeed(api, webSocket, scope, timeSource)
    private var pipeline = buildPipeline()

    private val _portfolio = MutableStateFlow(store.load() ?: PortfolioState.create(config.initialCapital))
    val portfolio: StateFlow<PortfolioState> = _portfolio.asStateFlow()

    private val _lastScan = MutableStateFlow<ScanReport?>(null)
    val lastScan: StateFlow<ScanReport?> = _lastScan.asStateFlow()

    private val _health = MutableStateFlow(EngineHealth())
    val health: StateFlow<EngineHealth> = _health.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    val prices: StateFlow<Map<String, BookTicker>> get() = feed.prices

    private val candles = HashMap<String, HashMap<Timeframe, List<Candle>>>()
    private var symbols: Map<String, SymbolInfo> = emptyMap()
    private var universe: List<String> = emptyList()
    private var universeLoadedAt = 0L
    private var serverOffset = 0L
    private var jobs = emptyList<Job>()

    val ruleEngine: RuleEngine get() = pipeline.ruleEngine

    fun start() {
        if (jobs.isNotEmpty()) return
        _health.update { it.copy(status = EngineStatus.STARTING, message = "Başlatılıyor", lastError = null) }
        jobs = listOf(
            scope.launch(dispatcher) { runMain() },
            scope.launch(dispatcher) { feed.prices.collect { onPrices(it) } },
            scope.launch(dispatcher) { feed.health.collect { f -> _health.update { it.copy(feed = f) } } },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        feed.stop()
        store.save(_portfolio.value)
        _health.update { it.copy(status = EngineStatus.STOPPED, message = "Durduruldu") }
    }

    val isRunning: Boolean get() = jobs.isNotEmpty()

    /** Yeni işlem açmayı durdurur/sürdürür; açık pozisyonların takibi devam eder. */
    fun setEntriesPaused(paused: Boolean) {
        _health.update { it.copy(entriesPaused = paused) }
        log(Decision(now(), null, null, DecisionOutcome.INFO, if (paused) "Yeni işlemler durduruldu (açık pozisyon takibi sürüyor)" else "Yeni işlemlere izin verildi"))
    }

    fun closePosition(positionId: String) {
        scope.launch(dispatcher) {
            val position = _portfolio.value.positions.firstOrNull { it.id == positionId } ?: return@launch
            val bid = feed.prices.value[position.symbol]?.bid ?: runCatching { api.bookTicker(position.symbol).bid }.getOrNull() ?: position.lastPrice
            closeAt(position, bid, ExitReason.MANUAL, "Kullanıcı tarafından kapatıldı")
        }
    }

    /** Ayarları değiştirir. Risk/strateji değişiklikleri bir sonraki taramada geçerli olur. */
    fun updateConfig(newConfig: EngineConfig) {
        scope.launch(dispatcher) {
            config = newConfig
            pipeline = buildPipeline()
            log(Decision(now(), null, null, DecisionOutcome.INFO, "Ayarlar güncellendi"))
        }
    }

    fun resetPaperAccount(capital: Double) {
        scope.launch(dispatcher) {
            config = config.copy(initialCapital = capital)
            publish(PortfolioState.create(capital))
        }
    }

    /** Ekranda gösterilecek bir paritenin seri verisi (yalnızca kapanmış mumlar). */
    fun series(symbol: String, timeframe: Timeframe): Series? =
        candles[symbol]?.get(timeframe)?.takeIf { it.isNotEmpty() }?.let { Series(symbol, timeframe, it, config.analysis) }

    fun analysis(symbol: String): MultiTimeframe? {
        val h4 = series(symbol, Timeframe.H4) ?: return null
        val h1 = series(symbol, Timeframe.H1) ?: return null
        val m15 = series(symbol, Timeframe.M15) ?: return null
        return MultiTimeframe(symbol, h4.analyze(), h1.analyze(), m15.analyze())
    }

    private fun buildPipeline() = DecisionPipeline(RuleEngine(ruleConfig), config.risk, config.enabledStrategies, config.strategyParams, config.execution)

    /** Bekleyen limit giriş emri (yalnız bellekte; uygulama yeniden başlarsa iptal olur). */
    private class PendingLimit(val decision: CandidateDecision.Enter, val expiresAt: Long)

    private val pendingLimits = mutableListOf<PendingLimit>()

    private fun publishPending() {
        _health.update { h -> h.copy(pendingOrders = pendingLimits.map { "${it.decision.symbol} @ ${"%.6g".format(it.decision.setup.entryPrice)}" }) }
    }

    private fun now() = timeSource.now() + serverOffset

    private suspend fun runMain() {
        // Başlangıç (sunucu saati, borsa bilgisi, geçmiş mumlar) ağ yokken başarısız olabilir — ör. cihaz açılışında.
        // 7/24 çalışma için motor durmaz; artan aralıklarla yeniden dener.
        var attempt = 0
        while (true) {
            try {
                syncServerTime()
                refreshUniverse()
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val wait = minOf(5_000L shl minOf(attempt, 6), 5 * 60_000L)
                attempt++
                _health.update {
                    it.copy(status = EngineStatus.ERROR, lastError = e.message, message = "Bağlantı bekleniyor; ${wait / 1000} sn sonra tekrar denenecek")
                }
                if (attempt == 1) problem("Başlatılamadı, yeniden denenecek: ${e.message}", e)
                delay(wait)
            }
        }
        _health.update { it.copy(status = EngineStatus.RUNNING, message = "Çalışıyor", lastError = null) }
        val first = Timeframe.M15.lastCloseAtOrBefore(now())
        runCatching { process(first, allowEntries = false) }.onFailure { problem("İlk tarama hatası: ${it.message}", it) }
        var lastProcessed = first
        var lastTimeSync = timeSource.now()
        var lastEquityUpdate = 0L
        while (scope.isActive) {
            val settled = Timeframe.M15.lastCloseAtOrBefore(now() - config.candleSettleMillis)
            if (settled > lastProcessed) {
                runCatching { process(settled, allowEntries = true) }.onFailure {
                    if (it is CancellationException) throw it
                    problem("Tarama hatası: ${it.message}", it)
                }
                lastProcessed = settled
            }
            if (timeSource.now() - lastTimeSync > 10 * 60_000L) {
                runCatching { syncServerTime() }
                lastTimeSync = timeSource.now()
            }
            if (timeSource.now() - lastEquityUpdate > 10_000L) {
                updateEquity()
                lastEquityUpdate = timeSource.now()
            }
            delay(1_000)
        }
    }

    private suspend fun syncServerTime() {
        val before = timeSource.now()
        val server = api.serverTime()
        val after = timeSource.now()
        serverOffset = server - (before + after) / 2
        _health.update { it.copy(serverTimeOffsetMillis = serverOffset) }
    }

    private suspend fun refreshUniverse() {
        symbols = api.exchangeInfo().associateBy { it.symbol }
        val tickers = api.tickers24h()
        universe = Scanner.selectUniverse(symbols.values.toList(), tickers, config.scanner)
        universeLoadedAt = timeSource.now()
        val watch = (universe + "BTCUSDT" + _portfolio.value.positions.map { it.symbol }).distinct()
        for (symbol in watch) {
            for (tf in listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4)) {
                if (candles[symbol]?.get(tf).isNullOrEmpty()) loadHistory(symbol, tf)
            }
        }
        candles.keys.retainAll(watch.toSet())
        feed.watch(watch.toSet())
        feed.start()
        _health.update { it.copy(universe = universe) }
        log(Decision(now(), null, null, DecisionOutcome.INFO, "İzleme listesi güncellendi: ${universe.size} coin", listOf(universe.joinToString())))
    }

    private suspend fun loadHistory(symbol: String, tf: Timeframe) {
        val closed = api.klines(symbol, tf, limit = config.historyBars).filter { it.isClosedAt(now()) }
        candles.getOrPut(symbol) { HashMap() }[tf] = closed
    }

    /** Kapanmış yeni mumları ekler; süreklilik bozulmuşsa geçmişi yeniden yükler (REST doğrulaması). */
    private suspend fun syncCandles(symbol: String, tf: Timeframe, boundary: Long) {
        val existing = candles[symbol]?.get(tf).orEmpty()
        val fresh = api.klines(symbol, tf, limit = 3).filter { it.closeTime <= boundary }
        val merged = (existing.filter { c -> fresh.none { it.openTime == c.openTime } } + fresh).sortedBy { it.openTime }
        val continuous = merged.zipWithNext().all { (a, b) -> b.openTime - a.openTime == tf.millis }
        if (!continuous || merged.lastOrNull()?.closeTime != boundary) {
            loadHistory(symbol, tf)
        } else {
            candles.getOrPut(symbol) { HashMap() }[tf] = merged.takeLast(config.historyBars)
        }
    }

    private suspend fun process(boundary: Long, allowEntries: Boolean) {
        if (timeSource.now() - universeLoadedAt > config.universeRefreshMillis) refreshUniverse()
        val watch = candles.keys.toList()
        val timeframes = buildList {
            add(Timeframe.M15)
            if (boundary % Timeframe.H1.millis == 0L) add(Timeframe.H1)
            if (boundary % Timeframe.H4.millis == 0L) add(Timeframe.H4)
        }
        if (allowEntries) {
            for (symbol in watch) for (tf in timeframes) {
                runCatching { syncCandles(symbol, tf, boundary) }.onFailure { problem("$symbol ${tf.label} mum verisi alınamadı: ${it.message}", it) }
            }
            _health.update { it.copy(lastCandleSyncAt = now()) }
        }
        if (Timeframe.H1 in timeframes) checkInvalidations()

        val report = scan(boundary)
        _lastScan.value = report
        _health.update { it.copy(lastScanAt = now()) }
        if (!allowEntries) {
            log(Decision(now(), null, null, DecisionOutcome.INFO, "Başlangıç taraması tamamlandı; işlemler bir sonraki 15M kapanışından itibaren değerlendirilecek"))
            return
        }
        if (_health.value.entriesPaused) return
        decide(report)
    }

    private suspend fun scan(boundary: Long): ScanReport {
        val tickers = runCatching { api.tickers24h() }.getOrDefault(emptyList()).associateBy { it.symbol }
        val restBooks = if (feed.prices.value.keys.containsAll(universe)) emptyMap()
        else runCatching { api.bookTickers().associateBy { it.symbol } }.getOrDefault(emptyMap())
        val coins = universe.mapNotNull { symbol ->
            val info = symbols[symbol] ?: return@mapNotNull null
            val book = feed.prices.value[symbol] ?: restBooks[symbol]
            val ticker = tickers[symbol]
            val prefilterOk = ticker != null && ticker.quoteVolume >= config.scanner.minQuoteVolume24h &&
                (book?.spreadPercent ?: Double.MAX_VALUE) <= config.scanner.maxSpreadPercent
            val orderBook = if (prefilterOk) runCatching { api.orderBook(symbol, 100) }.getOrNull() else null
            CoinMarketData(info, ticker, book, orderBook, analysis(symbol)?.takeIf { it.isValid })
        }
        return Scanner.scan(coins, analysis("BTCUSDT")?.takeIf { it.isValid }, config.scanner, boundary)
    }

    private fun costsFor(symbol: String): CostModel {
        val spread = feed.prices.value[symbol]?.spreadPercent?.takeIf { !it.isNaN() }?.let { it / 200 } ?: config.fallbackHalfSpread
        return CostModel(symbols[symbol]?.takerFee ?: 0.0005, config.slippage, spread)
    }

    private fun decide(report: ScanReport) {
        for (symbol in report.candidates) {
            val mtf = analysis(symbol)?.takeIf { it.isValid } ?: continue
            val state = _portfolio.value
            val equity = currentEquity(state)
            val context = DecisionContext(
                now = now(),
                equity = equity,
                availableQuote = state.cash,
                openPositions = state.positions + pendingLimits.map { it.decision.asPendingPosition(now()) },
                dailyPnlPercent = (equity - state.dayStartEquity) / state.dayStartEquity * 100,
                market = report.market,
                btc = analysis("BTCUSDT"),
                hourlyCloses = candles.mapValues { (_, byTf) -> byTf[Timeframe.H1].orEmpty().takeLast(config.risk.correlationWindowBars + 1).map { it.close }.toDoubleArray() },
                closedTrades = state.closedTrades,
            )
            val candidate = Candidate(mtf, report.coins.firstOrNull { it.symbol == symbol }, symbols[symbol], costsFor(symbol))
            when (val decision = pipeline.evaluate(candidate, context)) {
                is CandidateDecision.NoSetup -> log(
                    Decision(now(), symbol, null, DecisionOutcome.NO_SETUP, "$symbol: uygun kurulum yok", decision.reasons),
                )
                is CandidateDecision.Reject -> log(
                    Decision(
                        now(), symbol, decision.setup.strategy, DecisionOutcome.REJECTED,
                        "$symbol: ${decision.setup.strategy.title} kurulumu reddedildi",
                        decision.report.mandatoryFailures.map { "${it.id} ${it.title}: ${it.detail}" },
                        decision.report.qualityScore,
                    ),
                )
                is CandidateDecision.Enter -> enter(decision)
            }
        }
    }

    private fun enter(decision: CandidateDecision.Enter) {
        val setup = decision.setup
        if (config.execution.entryMode == EntryMode.LIMIT) {
            pendingLimits += PendingLimit(decision, now() + config.execution.limitEntryMaxBars * Timeframe.M15.millis)
            publishPending()
            log(
                Decision(
                    now(), setup.symbol, setup.strategy, DecisionOutcome.INFO,
                    "${setup.symbol}: limit alış emri verildi @ ${"%.6g".format(setup.entryPrice)} (${config.execution.limitEntryMaxBars} mum geçerli)",
                    listOf(decision.rationale.whyEntry), decision.report.qualityScore,
                ),
            )
            return
        }
        val ask = feed.prices.value[setup.symbol]?.ask
        val drift = ask?.let { (it / setup.entryPrice - 1) * 100 }
        if (ask == null || ask <= setup.stopPrice || drift!! > config.maxEntryDriftPercent) {
            log(
                Decision(
                    now(), setup.symbol, setup.strategy, DecisionOutcome.REJECTED, "${setup.symbol}: giriş fiyatı uygun değil",
                    listOf(if (ask == null) "Canlı fiyat yok" else "Anlık alış %.6g, sinyal %.6g (kayma %%%.2f)".format(ask, setup.entryPrice, drift)),
                    decision.report.qualityScore,
                ),
            )
            return
        }
        val costs = costsFor(setup.symbol)
        val updated = PaperAccount.open(
            _portfolio.value, setup, decision.sizing.quantity, ask, costs, decision.sizing.riskAmount,
            decision.report.qualityScore, decision.rationale, decision.report, now(),
        )
        publish(updated)
        updated.positions.lastOrNull()?.let { _events.tryEmit(EngineEvent.Opened(it)) }
    }

    private fun onPrices(prices: Map<String, BookTicker>) {
        fillPendingLimits(prices)
        for (position in _portfolio.value.positions.toList()) {
            val bid = prices[position.symbol]?.bid ?: continue
            if (bid <= 0) continue
            val actions = ExitManager.onPrice(position, bid, now(), config.execution)
            if (actions.isEmpty()) continue
            val before = _portfolio.value
            val updated = ExitManager.apply(before, position.id, actions, costsFor(position.symbol), now())
            publish(updated)
            if (updated.closedTrades.size > before.closedTrades.size) _events.tryEmit(EngineEvent.Closed(updated.closedTrades.last()))
        }
    }

    /** Limit alış emri: en iyi satış fiyatı limitin altına inerse emir dolmuş sayılır (maker). */
    private fun fillPendingLimits(prices: Map<String, BookTicker>) {
        if (pendingLimits.isEmpty()) return
        val iterator = pendingLimits.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val pending = iterator.next()
            val setup = pending.decision.setup
            val ask = prices[setup.symbol]?.ask
            when {
                now() > pending.expiresAt -> {
                    iterator.remove()
                    changed = true
                    log(Decision(now(), setup.symbol, setup.strategy, DecisionOutcome.INFO, "${setup.symbol}: limit emir dolmadı, iptal edildi"))
                }
                ask != null && ask > 0 && ask <= setup.stopPrice -> {
                    iterator.remove()
                    changed = true
                    log(Decision(now(), setup.symbol, setup.strategy, DecisionOutcome.INFO, "${setup.symbol}: fiyat stopun altına indi, limit emir iptal edildi"))
                }
                ask != null && ask > 0 && ask < setup.entryPrice -> {
                    iterator.remove()
                    changed = true
                    val updated = PaperAccount.open(
                        _portfolio.value, setup, pending.decision.sizing.quantity, setup.entryPrice, costsFor(setup.symbol),
                        pending.decision.sizing.riskAmount, pending.decision.report.qualityScore, pending.decision.rationale,
                        pending.decision.report, now(), maker = true,
                    )
                    publish(updated)
                    updated.positions.lastOrNull()?.let { _events.tryEmit(EngineEvent.Opened(it)) }
                }
            }
        }
        if (changed) publishPending()
    }

    private suspend fun checkInvalidations() {
        for (position in _portfolio.value.positions.toList()) {
            val last = candles[position.symbol]?.get(Timeframe.H1)?.lastOrNull() ?: continue
            if (ExitRules.onHourlyClose(position, last.close)) {
                val bid = feed.prices.value[position.symbol]?.bid ?: runCatching { api.bookTicker(position.symbol).bid }.getOrNull() ?: last.close
                closeAt(position, bid, ExitReason.INVALIDATION, "1H kapanış (${last.close}) geçersizlik seviyesinin (${position.invalidationPrice}) altında")
            }
        }
    }

    private fun closeAt(position: Position, bid: Double, reason: ExitReason, note: String) {
        val before = _portfolio.value
        if (before.positions.none { it.id == position.id }) return
        val updated = PaperAccount.close(before, position.id, bid, reason, note, costsFor(position.symbol), now())
        publish(updated)
        updated.closedTrades.lastOrNull()?.let { _events.tryEmit(EngineEvent.Closed(it)) }
    }

    private fun currentEquity(state: PortfolioState): Double {
        val bids = feed.prices.value.mapValues { it.value.bid }
        return state.equity(bids) { costsFor(it) }
    }

    private fun updateEquity() {
        val bids = feed.prices.value.mapValues { it.value.bid }
        var state = PaperAccount.withPrices(_portfolio.value, bids)
        val equity = state.equity(bids) { costsFor(it) }
        state = PaperAccount.rollDay(state, now(), equity)
        state = PaperAccount.recordEquity(state, now(), equity)
        if (state != _portfolio.value) publish(state, persist = state.equityHistory != _portfolio.value.equityHistory || state.dayStart != _portfolio.value.dayStart)
    }

    private fun log(decision: Decision) = publish(PaperAccount.log(_portfolio.value, decision))

    private fun publish(state: PortfolioState, persist: Boolean = true) {
        _portfolio.value = state
        if (persist) runCatching { store.save(state) }.onFailure { _health.update { h -> h.copy(lastError = "Kayıt hatası: ${it.message}") } }
    }

    private fun problem(message: String, error: Throwable? = null) {
        _health.update { it.copy(lastError = message) }
        _events.tryEmit(EngineEvent.Problem(message))
        log(Decision(now(), null, null, DecisionOutcome.INFO, message, listOfNotNull(error?.javaClass?.simpleName)))
    }
}
