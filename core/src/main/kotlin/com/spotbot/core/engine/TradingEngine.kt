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
import com.spotbot.core.trading.CoinDecision
import com.spotbot.core.trading.CoinOutcome
import com.spotbot.core.trading.DataStamp
import com.spotbot.core.trading.DecisionLogStore
import com.spotbot.core.trading.DecisionLogs
import com.spotbot.core.trading.Downtime
import com.spotbot.core.trading.ExitAction
import com.spotbot.core.trading.Fill
import com.spotbot.core.trading.FillModel
import com.spotbot.core.trading.FillResult
import com.spotbot.core.trading.InMemoryDecisionLogStore
import com.spotbot.core.trading.OrderRules
import com.spotbot.core.trading.OrderSide
import com.spotbot.core.trading.OrderType
import com.spotbot.core.trading.PendingOrder
import com.spotbot.core.trading.Reliability
import com.spotbot.core.trading.RiskStatus
import com.spotbot.core.trading.ScanLog
import com.spotbot.core.trading.StrategyProfile
import com.spotbot.core.ta.Indicators
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
    /** Sinyal kapanışı ile ortalama dolum fiyatı arasında izin verilen en büyük kayma (%). */
    val maxEntryDriftPercent: Double = 0.3,
    val universeRefreshMillis: Long = 4 * 60 * 60_000L,
    val execution: ExecutionConfig = ExecutionConfig(),
    /** Strateji profili (varsayılan Adaptif = önceki davranış). */
    val profile: StrategyProfile = StrategyProfile.ADAPTIVE,
    /** Girişte canlı fiyatın en fazla yaşı (sn); daha eskiyse işlem açılmaz. */
    val maxPriceAgeSeconds: Int = 30,
    /** Piyasa emri emir defterinde en iyi fiyattan en fazla bu oran kadar uzaklaşabilir; ötesi kısmi gerçekleşir. */
    val maxBookSlippage: Double = 0.005,
    val heartbeatMillis: Long = 60_000,
    /** Son "canlıyım" kaydından bu kadar süre sonra başlarsa aradaki süre çevrimdışı sayılır. */
    val downtimeThresholdMillis: Long = 3 * 60_000,
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
    /** Botun çalıştığı yer (telefon / sunucu). */
    val runLocation: String = "",
    /** Son 24 saatte (ya da ilk başlatmadan beri) çalışma oranı, %. */
    val uptime24hPercent: Double? = null,
    val lastDowntime: Downtime? = null,
    /** Son taramada verisi güncel olmayan coinler. */
    val staleSymbols: List<String> = emptyList(),
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
 * zorunlu/kalite kuralları → risk → sanal emir. Her coin için karar ve kullanılan verinin zamanı
 * [scanLogs] ile kaydedilir. Eski veriyle (mum ya da fiyat) işlem açılmaz. Emirler emir defteri
 * derinliğiyle doldurulur; yetmezse kısmi gerçekleşir. Açık pozisyonların stop/hedefi WebSocket
 * fiyatlarıyla, yapısal bozulması 1H kapanışlarında izlenir.
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
    private val decisionLog: DecisionLogStore = InMemoryDecisionLogStore(),
    runLocation: String = "Sunucu / bilgisayar (komut satırı)",
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

    private val _scanLogs = MutableStateFlow(runCatching { decisionLog.recent(SCAN_LOGS_IN_MEMORY) }.getOrDefault(emptyList()))
    /** Son taramaların coin bazında karar kayıtları (en yenisi sonda). */
    val scanLogs: StateFlow<List<ScanLog>> = _scanLogs.asStateFlow()

    private val _health = MutableStateFlow(EngineHealth(runLocation = runLocation))
    val health: StateFlow<EngineHealth> = _health.asStateFlow()

    private val _riskStatus = MutableStateFlow(RiskStatus())
    val riskStatus: StateFlow<RiskStatus> = _riskStatus.asStateFlow()

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
        val stopped = _portfolio.value.copy(stoppedByUserAt = now(), lastHeartbeat = now())
        _portfolio.value = stopped
        runCatching { store.save(stopped) }
        _health.update { it.copy(status = EngineStatus.STOPPED, message = "Durduruldu") }
    }

    val isRunning: Boolean get() = jobs.isNotEmpty()

    /** Yeni işlem açmayı durdurur/sürdürür; açık pozisyonların takibi devam eder. */
    fun setEntriesPaused(paused: Boolean) {
        scope.launch(dispatcher) {
            _health.update { it.copy(entriesPaused = paused) }
            log(Decision(now(), null, null, DecisionOutcome.INFO, if (paused) "Yeni işlemler durduruldu (açık pozisyon takibi sürüyor)" else "Yeni işlemlere izin verildi"))
            refreshRisk()
        }
    }

    fun closePosition(positionId: String) {
        scope.launch(dispatcher) {
            val position = _portfolio.value.positions.firstOrNull { it.id == positionId } ?: return@launch
            val quote = feed.prices.value[position.symbol] ?: runCatching { api.bookTicker(position.symbol) }.getOrNull()
            if (quote == null) {
                problem("${position.symbol} için fiyat alınamadı; manuel kapatma yapılamadı")
                return@launch
            }
            exit(position, ExitAction.Close(quote.bid, ExitReason.MANUAL, false, "Kullanıcı tarafından kapatıldı"), quote)
        }
    }

    fun cancelPendingOrder(symbol: String) {
        scope.launch(dispatcher) {
            val state = _portfolio.value
            val order = state.pendingOrders.firstOrNull { it.symbol == symbol } ?: return@launch
            publish(PaperAccount.log(state.copy(pendingOrders = state.pendingOrders - order), Decision(now(), symbol, order.setup.strategy, DecisionOutcome.INFO, "$symbol: bekleyen limit emir kullanıcı tarafından iptal edildi")))
            publishPending()
        }
    }

    /** Ayarları değiştirir. Risk/strateji değişiklikleri bir sonraki taramada geçerli olur. */
    fun updateConfig(newConfig: EngineConfig) {
        scope.launch(dispatcher) {
            config = newConfig
            pipeline = buildPipeline()
            log(Decision(now(), null, null, DecisionOutcome.INFO, "Ayarlar güncellendi (profil: ${newConfig.profile.title})"))
            refreshRisk()
        }
    }

    fun resetPaperAccount(capital: Double) {
        scope.launch(dispatcher) {
            config = config.copy(initialCapital = capital)
            publish(PortfolioState.create(capital).copy(firstStartedAt = now(), lastHeartbeat = now()))
            publishPending()
            refreshRisk()
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

    private fun buildPipeline() = DecisionPipeline(
        RuleEngine(ruleConfig), config.risk, config.enabledStrategies, config.strategyParams, config.execution, config.profile,
    )

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
        recordStartup()
        _health.update { it.copy(status = EngineStatus.RUNNING, message = "Çalışıyor", lastError = null) }
        publishPending()
        val first = Timeframe.M15.lastCloseAtOrBefore(now())
        runCatching { process(first, allowEntries = false) }.onFailure { if (it is CancellationException) throw it; problem("İlk tarama hatası: ${it.message}", it) }
        var lastProcessed = first
        var lastTimeSync = timeSource.now()
        var lastEquityUpdate = 0L
        var lastHeartbeat = timeSource.now()
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
            if (timeSource.now() - lastHeartbeat >= config.heartbeatMillis) {
                publish(_portfolio.value.copy(lastHeartbeat = now()))
                lastHeartbeat = timeSource.now()
            }
            if (timeSource.now() - lastEquityUpdate > 10_000L) {
                updateEquity()
                lastEquityUpdate = timeSource.now()
            }
            delay(1_000)
        }
    }

    /**
     * Başlangıçta önceki "canlıyım" kaydına bakar: aradaki süre çevrimdışı olarak kaydedilir ve bu sürede açık
     * pozisyonların stop/hedef seviyeleri aşıldıysa bildirilir (bot çalışmadığı için o anda satış yapılamamıştır;
     * pozisyon ilk canlı fiyatla kapanır).
     */
    private fun recordStartup() {
        var state = _portfolio.value
        val now = now()
        if (state.firstStartedAt == 0L) state = state.copy(firstStartedAt = now)
        val last = state.lastHeartbeat
        if (last > 0 && now - last > config.downtimeThresholdMillis) {
            val byUser = state.stoppedByUserAt > 0 && state.stoppedByUserAt >= last - config.heartbeatMillis
            val note = if (byUser) "Bot kullanıcı tarafından kapatılmıştı" else "Bot beklenmedik şekilde durmuş (telefon, Android ya da uygulama kapanmış olabilir)"
            val minutes = (now - last) / 60_000
            val missed = state.positions.mapNotNull { p ->
                val during = candles[p.symbol]?.get(Timeframe.M15).orEmpty().filter { it.closeTime > last && it.openTime < now }
                when {
                    during.any { it.low <= p.stopPrice } -> "${p.symbol}: çevrimdışıyken stop (${p.stopPrice}) aşıldı, en düşük ${during.minOf { it.low }}; satış yapılamadı, ilk canlı fiyatla kapanacak"
                    during.any { it.high >= p.targetPrice } -> "${p.symbol}: çevrimdışıyken hedef (${p.targetPrice}) görüldü; satış yapılamadı"
                    else -> null
                }
            }
            state = state.copy(downtimes = (state.downtimes + Downtime(last, now, note)).takeLast(MAX_DOWNTIMES))
            state = PaperAccount.log(state, Decision(now, null, null, DecisionOutcome.INFO, "Bot $minutes dakika çalışmadı: $note", missed))
        }
        publish(state.copy(lastHeartbeat = now, stoppedByUserAt = 0))
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
        val state = _portfolio.value
        val watch = (universe + "BTCUSDT" + state.positions.map { it.symbol } + state.pendingOrders.map { it.symbol }).distinct()
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

    private fun stamp(symbol: String) = DataStamp(
        m15Close = candles[symbol]?.get(Timeframe.M15)?.lastOrNull()?.closeTime,
        h1Close = candles[symbol]?.get(Timeframe.H1)?.lastOrNull()?.closeTime,
        h4Close = candles[symbol]?.get(Timeframe.H4)?.lastOrNull()?.closeTime,
        priceTime = feed.prices.value[symbol]?.time,
    )

    private fun freshnessIssue(stamp: DataStamp, boundary: Long): String? =
        com.spotbot.core.trading.DataFreshness.issue(stamp, boundary, now(), config.maxPriceAgeSeconds)

    private enum class Mode { LIVE, OBSERVE, PAUSED }

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
        val mode = when {
            !allowEntries -> Mode.OBSERVE
            _health.value.entriesPaused -> Mode.PAUSED
            else -> Mode.LIVE
        }
        val decisions = mutableListOf<CoinDecision>()
        for (coin in report.coins.filter { it.symbol !in report.candidates }) {
            val (outcome, summary) = when (coin.status) {
                CoinStatus.ELIMINATED -> CoinOutcome.ELIMINATED to "Tarama filtresinde elendi"
                CoinStatus.FLAGGED -> CoinOutcome.FLAGGED to "Manipülasyon riski işareti"
                else -> CoinOutcome.NOT_SELECTED to "Ön puan ${coin.preScore}: en iyi ${config.scanner.topCandidates} aday arasında değil (ya da puan ≤ 0)"
            }
            decisions += CoinDecision(coin.symbol, outcome, summary, coin.reasons, coin.preScore, stamp(coin.symbol))
        }
        for (symbol in report.candidates) decisions += decide(symbol, report, boundary, mode)
        val stale = decisions.filter { it.outcome == CoinOutcome.STALE_DATA }.map { it.symbol }
        val scanLog = ScanLog(now(), boundary, config.profile, report.market.allowsLongs, report.market.reasons, mode == Mode.PAUSED, decisions)
        runCatching { decisionLog.append(scanLog) }.onFailure { _health.update { h -> h.copy(lastError = "Karar günlüğü yazılamadı: ${it.message}") } }
        _scanLogs.update { (it + scanLog).takeLast(SCAN_LOGS_IN_MEMORY) }
        _health.update { it.copy(lastScanAt = now(), staleSymbols = stale) }
        if (mode == Mode.OBSERVE) {
            log(Decision(now(), null, null, DecisionOutcome.INFO, "Başlangıç taraması tamamlandı; işlemler bir sonraki 15M kapanışından itibaren değerlendirilecek"))
        }
        refreshRisk(report.market)
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
        val info = symbols[symbol]
        return CostModel(info?.takerFee ?: 0.0005, config.slippage, spread, info?.makerFee ?: 0.0)
    }

    private fun hourlyCloses(): Map<String, DoubleArray> =
        candles.mapValues { (_, byTf) -> byTf[Timeframe.H1].orEmpty().takeLast(config.risk.correlationWindowBars + 1).map { it.close }.toDoubleArray() }

    private suspend fun decide(symbol: String, report: ScanReport, boundary: Long, mode: Mode): CoinDecision {
        val stamp = stamp(symbol)
        val scan = report.coins.firstOrNull { it.symbol == symbol }
        val mtf = analysis(symbol)?.takeIf { it.isValid }
            ?: return CoinDecision(symbol, CoinOutcome.STALE_DATA, "Analiz için yeterli mum verisi yok", preScore = scan?.preScore, data = stamp)
        val state = _portfolio.value
        val equity = currentEquity(state)
        val context = DecisionContext(
            now = now(),
            equity = equity,
            availableQuote = state.availableCash,
            openPositions = state.positions + state.pendingOrders.map { it.asPosition() },
            dailyPnlPercent = (equity - state.dayStartEquity) / state.dayStartEquity * 100,
            market = report.market,
            btc = analysis("BTCUSDT"),
            hourlyCloses = hourlyCloses(),
            closedTrades = state.closedTrades,
        )
        val dataIssue = freshnessIssue(stamp, boundary)
        val decision = pipeline.evaluate(Candidate(mtf, scan, symbols[symbol], costsFor(symbol), dataIssue), context)
        val checks = DecisionLogs.strategyChecks(decision.evaluations)
        return when (decision) {
            is CandidateDecision.NoSetup -> {
                log(Decision(now(), symbol, null, DecisionOutcome.NO_SETUP, "$symbol: uygun kurulum yok", decision.reasons))
                CoinDecision(symbol, CoinOutcome.NO_SETUP, "Uygun kurulum bulunamadı", decision.reasons, scan?.preScore, stamp, checks)
            }
            is CandidateDecision.Reject -> {
                val failures = decision.report.mandatoryFailures.map { "${it.id} ${it.title}: ${it.detail}" }
                log(Decision(now(), symbol, decision.setup.strategy, DecisionOutcome.REJECTED, "$symbol: ${decision.setup.strategy.title} kurulumu reddedildi", failures, decision.report.qualityScore))
                val onlyData = decision.report.mandatoryFailures.all { it.id == "Z17" }
                CoinDecision(
                    symbol, if (onlyData) CoinOutcome.STALE_DATA else CoinOutcome.REJECTED,
                    "${decision.setup.strategy.title} kurulumu zorunlu kurallarca reddedildi", failures, scan?.preScore, stamp, checks,
                )
            }
            is CandidateDecision.Enter -> when (mode) {
                Mode.OBSERVE -> CoinDecision(symbol, CoinOutcome.OBSERVE_ONLY, "Kurallar geçti ama başlangıç taramasında işlem açılmaz", emptyList(), scan?.preScore, stamp, checks)
                Mode.PAUSED -> CoinDecision(symbol, CoinOutcome.PAUSED, "Kurallar geçti ama yeni işlemler duraklatılmış", emptyList(), scan?.preScore, stamp, checks)
                Mode.LIVE -> {
                    val (outcome, summary, reasons) = enter(decision)
                    CoinDecision(symbol, outcome, summary, reasons, scan?.preScore, stamp, checks)
                }
            }
        }
    }

    private suspend fun enter(decision: CandidateDecision.Enter): Triple<CoinOutcome, String, List<String>> {
        val setup = decision.setup
        val info = symbols[setup.symbol]
        val quote = feed.prices.value[setup.symbol]
        val fail = { reason: String ->
            log(Decision(now(), setup.symbol, setup.strategy, DecisionOutcome.REJECTED, "${setup.symbol}: emir gerçekleşmedi", listOf(reason), decision.report.qualityScore))
            Triple(CoinOutcome.ENTRY_FAILED, "Kurallar geçti ama emir gerçekleşmedi", listOf(reason))
        }
        if (config.execution.entryMode == EntryMode.LIMIT) {
            val limit = OrderRules.roundPrice(setup.entryPrice, OrderSide.BUY, info)
            val qty = OrderRules.roundQuantity(decision.sizing.quantity, info)
            OrderRules.validate(OrderSide.BUY, OrderType.LIMIT, qty, limit, info, quote?.ask)?.let { return fail(it) }
            if (limit * qty * (1 + costsFor(setup.symbol).makerFee) > _portfolio.value.availableCash) return fail("Bekleyen emirler sonrası bakiye yetersiz")
            val order = PendingOrder(
                setup, limit, qty, decision.sizing.riskAmount, decision.report.qualityScore, decision.rationale, decision.report,
                now(), now() + config.execution.limitEntryMaxBars * Timeframe.M15.millis,
            )
            publish(
                PaperAccount.log(
                    _portfolio.value.copy(pendingOrders = _portfolio.value.pendingOrders + order),
                    Decision(now(), setup.symbol, setup.strategy, DecisionOutcome.INFO, "${setup.symbol}: limit alış emri verildi @ ${"%.6g".format(limit)} (${config.execution.limitEntryMaxBars} mum geçerli)", listOf(decision.rationale.whyEntry), decision.report.qualityScore),
                ),
            )
            publishPending()
            return Triple(CoinOutcome.ORDER_PLACED, "Limit alış emri verildi @ ${"%.6g".format(limit)}", emptyList())
        }
        if (quote == null) return fail("Canlı fiyat yok")
        val age = (now() - quote.time) / 1000
        if (age > config.maxPriceAgeSeconds) return fail("Canlı fiyat $age sn eski")
        val costs = costsFor(setup.symbol)
        val book = runCatching { api.orderBook(setup.symbol, 50) }.getOrNull()
        val result = if (book != null) FillModel.walkBook(OrderSide.BUY, decision.sizing.quantity, book, config.maxBookSlippage, costs, info)
        else FillModel.marketFromQuote(OrderSide.BUY, decision.sizing.quantity, quote.ask, costs, info)
        val fill = when (result) {
            is FillResult.Rejected -> return fail(result.reason)
            is FillResult.Filled -> result.fill
        }
        val drift = (fill.averagePrice / setup.entryPrice - 1) * 100
        if (drift > config.maxEntryDriftPercent) return fail("Ortalama dolum %.6g, sinyal %.6g (kayma %%%.2f > %%%.2f); emir gönderilmedi".format(fill.averagePrice, setup.entryPrice, drift, config.maxEntryDriftPercent))
        if (fill.averagePrice <= setup.stopPrice) return fail("Dolum fiyatı stopun altında")
        val before = _portfolio.value
        val updated = PaperAccount.openWithFill(before, setup, fill, decision.sizing.riskAmount, decision.report.qualityScore, decision.rationale, decision.report, now())
        publish(updated)
        val opened = updated.positions.size > before.positions.size
        if (!opened) return Triple(CoinOutcome.ENTRY_FAILED, "Bakiye yetersiz", listOf(updated.decisions.last().details.joinToString()))
        updated.positions.last().let { _events.tryEmit(EngineEvent.Opened(it)) }
        refreshRisk()
        return Triple(CoinOutcome.OPENED, "İşlem açıldı @ ${"%.6g".format(fill.averagePrice)}${if (fill.partial) " (kısmi: ${fill.quantity}/${fill.requestedQuantity})" else ""}", listOfNotNull(fill.note.takeIf { it.isNotEmpty() }))
    }

    private fun onPrices(prices: Map<String, BookTicker>) {
        fillPendingOrders(prices)
        for (position in _portfolio.value.positions.toList()) {
            val quote = prices[position.symbol] ?: continue
            if (quote.bid <= 0) continue
            val actions = ExitManager.onPrice(position, quote.bid, now(), config.execution)
            if (actions.isEmpty()) continue
            actions.forEach { exit(position, it, quote) }
        }
    }

    /** Çıkış eylemini en iyi alış fiyatı ve oradaki miktarla (likidite sınırı) gerçekleştirir. */
    private fun exit(position: Position, action: ExitAction, quote: BookTicker) {
        val before = _portfolio.value
        if (before.positions.none { it.id == position.id }) return
        val costs = costsFor(position.symbol)
        val info = symbols[position.symbol]
        val updated = ExitManager.applyWithFills(before, position.id, listOf(action), now()) { p, a, requested ->
            val maker = (a as? ExitAction.Close)?.maker ?: (a as? ExitAction.PartialClose)?.maker ?: false
            val price = if (maker) ((a as? ExitAction.Close)?.price ?: (a as ExitAction.PartialClose).price) else quote.bid * (1 - costs.slippage)
            val full = requested >= p.quantity * (1 - 1e-9)
            // Son kalan borsada satılamayacak kadar küçükse (toz) pozisyon açık bırakılmaz.
            if (full && OrderRules.isDust(p.quantity, price, info)) return@applyWithFills FillModel.dustExit(p.quantity, price, maker, costs)
            val available = if (quote.bidQty > 0) quote.bidQty else requested
            val qty = OrderRules.roundQuantity(minOf(requested, available), info).let { if (requested - it < 1e-12 || it >= p.quantity) minOf(requested, p.quantity) else it }
            if (qty <= 0) return@applyWithFills null
            // Kalanın tamamı satılıyorsa asgari tutar engeli uygulanmaz (aksi halde pozisyon kapanamaz).
            if (qty < p.quantity && OrderRules.validate(OrderSide.SELL, OrderType.MARKET, qty, price, info) != null) return@applyWithFills null
            val note = if (qty < requested) "Kısmi gerçekleşme: en iyi alış seviyesinde yalnız $qty adet vardı" else ""
            FillModel.absorbDust(Fill(qty, price, price * qty * (if (maker) costs.makerFee else costs.takerFee), maker, requested, note), p.quantity, info)
        }
        if (updated == before) return
        publish(updated)
        if (updated.closedTrades.size > before.closedTrades.size) _events.tryEmit(EngineEvent.Closed(updated.closedTrades.last()))
        refreshRisk()
    }

    /** Limit alış emri: en iyi satış fiyatı limitin altına inerse o seviyedeki miktar kadar dolar (maker). */
    private fun fillPendingOrders(prices: Map<String, BookTicker>) {
        val pending = _portfolio.value.pendingOrders
        if (pending.isEmpty()) return
        for (order in pending) {
            val quote = prices[order.symbol]
            val state = _portfolio.value
            when {
                now() > order.expiresAt -> publish(PaperAccount.log(state.copy(pendingOrders = state.pendingOrders - order), Decision(now(), order.symbol, order.setup.strategy, DecisionOutcome.INFO, "${order.symbol}: limit emir ${config.execution.limitEntryMaxBars} mum içinde dolmadı, iptal edildi")))
                quote != null && quote.ask > 0 && quote.ask <= order.setup.stopPrice -> publish(PaperAccount.log(state.copy(pendingOrders = state.pendingOrders - order), Decision(now(), order.symbol, order.setup.strategy, DecisionOutcome.INFO, "${order.symbol}: fiyat stopun altına indi, limit emir iptal edildi")))
                quote != null && quote.ask > 0 && quote.ask < order.limitPrice -> {
                    val info = symbols[order.symbol]
                    val qty = OrderRules.roundQuantity(if (quote.askQty > 0) minOf(order.quantity, quote.askQty) else order.quantity, info)
                    val invalid = OrderRules.validate(OrderSide.BUY, OrderType.LIMIT, qty, order.limitPrice, info)
                    if (invalid != null) {
                        // Bu fiyattaki miktar asgari emri karşılamıyor; emir beklemeye devam eder.
                        continue
                    }
                    val costs = costsFor(order.symbol)
                    val note = if (qty < order.quantity) "Kısmi gerçekleşme: limit seviyesinde yalnız $qty adet vardı; kalan iptal edildi" else "Limit emir doldu"
                    val fill = Fill(qty, order.limitPrice, order.limitPrice * qty * costs.makerFee, true, order.quantity, note)
                    val updated = PaperAccount.openWithFill(state, order.setup, fill, order.riskAmount, order.qualityScore, order.rationale, order.report, now(), fromPending = order)
                    publish(updated)
                    if (updated.positions.size > state.positions.size) _events.tryEmit(EngineEvent.Opened(updated.positions.last()))
                }
            }
        }
        if (_portfolio.value.pendingOrders != pending) {
            publishPending()
            refreshRisk()
        }
    }

    private fun publishPending() {
        _health.update { h -> h.copy(pendingOrders = _portfolio.value.pendingOrders.map { "${it.symbol} @ ${"%.6g".format(it.limitPrice)}" }) }
    }

    private suspend fun checkInvalidations() {
        for (position in _portfolio.value.positions.toList()) {
            val last = candles[position.symbol]?.get(Timeframe.H1)?.lastOrNull() ?: continue
            if (ExitRules.onHourlyClose(position, last.close)) {
                val quote = feed.prices.value[position.symbol] ?: runCatching { api.bookTicker(position.symbol) }.getOrNull() ?: continue
                exit(position, ExitAction.Close(quote.bid, ExitReason.INVALIDATION, false, "1H kapanış (${last.close}) geçersizlik seviyesinin (${position.invalidationPrice}) altında"), quote)
            }
        }
    }

    private fun currentEquity(state: PortfolioState): Double {
        val bids = feed.prices.value.mapValues { it.value.bid }
        return state.equity(bids) { costsFor(it) }
    }

    private fun correlatedPairs(state: PortfolioState): List<String> {
        val closes = hourlyCloses()
        val symbols = (state.positions.map { it.symbol } + state.pendingOrders.map { it.symbol }).distinct()
        return symbols.flatMapIndexed { i, a ->
            symbols.drop(i + 1).mapNotNull { b ->
                val ca = closes[a] ?: return@mapNotNull null
                val cb = closes[b] ?: return@mapNotNull null
                val corr = Indicators.returnCorrelation(ca, cb, config.risk.correlationWindowBars)
                if (corr >= config.risk.correlationThreshold) "%s ~ %s (%.2f)".format(a.removeSuffix("USDT"), b.removeSuffix("USDT"), corr) else null
            }
        }
    }

    private fun refreshRisk(market: com.spotbot.core.scanner.MarketState? = _lastScan.value?.market) {
        val state = _portfolio.value
        val equity = currentEquity(state)
        _riskStatus.value = RiskStatus.of(state, equity, config.risk, config.profile, _health.value.entriesPaused, market?.allowsLongs, correlatedPairs(state), market)
    }

    private fun updateEquity() {
        val bids = feed.prices.value.mapValues { it.value.bid }
        var state = PaperAccount.withPrices(_portfolio.value, bids)
        val equity = state.equity(bids) { costsFor(it) }
        state = PaperAccount.rollDay(state, now(), equity)
        state = PaperAccount.recordEquity(state, now(), equity)
        if (state != _portfolio.value) publish(state, persist = state.equityHistory != _portfolio.value.equityHistory || state.dayStart != _portfolio.value.dayStart)
        val from = maxOf(now() - DAY, state.firstStartedAt.takeIf { it > 0 } ?: now())
        _health.update { it.copy(uptime24hPercent = Reliability.uptimePercent(state.downtimes, from, now()), lastDowntime = state.downtimes.lastOrNull()) }
        refreshRisk()
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

    companion object {
        const val SCAN_LOGS_IN_MEMORY = 48
        private const val MAX_DOWNTIMES = 100
        private const val DAY = 24 * 60 * 60_000L
    }
}
