package com.spotbot.core.backtest

import com.spotbot.core.analysis.AnalysisConfig
import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Series
import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.exchange.klinesBetween
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe
import com.spotbot.core.risk.CostModel
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.rules.RuleConfig
import com.spotbot.core.rules.RuleEngine
import com.spotbot.core.scanner.CoinMarketData
import com.spotbot.core.scanner.Scanner
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.StrategyParams
import com.spotbot.core.trading.Candidate
import com.spotbot.core.trading.CandidateDecision
import com.spotbot.core.trading.ClosedTrade
import com.spotbot.core.trading.DecisionContext
import com.spotbot.core.trading.DecisionPipeline
import com.spotbot.core.trading.ExitReason
import com.spotbot.core.trading.ExitRules
import com.spotbot.core.trading.PaperAccount
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.asPendingPosition
import com.spotbot.core.trading.EntryMode
import com.spotbot.core.trading.ExecutionConfig
import com.spotbot.core.trading.ExitManager
import com.spotbot.core.rules.BuiltInRules
import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class BacktestConfig(
    val symbols: List<String>,
    val startTime: Long,
    val endTime: Long,
    val initialCapital: Double = 1_000.0,
    val risk: RiskConfig = RiskConfig(),
    val scanner: ScannerConfig = ScannerConfig(),
    val strategyParams: StrategyParams = StrategyParams(),
    val analysis: AnalysisConfig = AnalysisConfig(),
    val enabledStrategies: Set<StrategyId> = setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK),
    /** Kayma ve yarım spread oranları; komisyon her paritenin MEXC taker ücretinden alınır. */
    val slippage: Double = 0.0005,
    val halfSpread: Double = 0.00025,
    val defaultTakerFee: Double = 0.0005,
    /** Göstergelerin ısınması için başlangıçtan önce yüklenecek gün sayısı. */
    val warmupDays: Int = 45,
    val execution: ExecutionConfig = ExecutionConfig(),
    /** Gölge işlem analizi (her kurulumun bağımsız sonucu ve kural katkısı). */
    val shadowAnalysis: Boolean = false,
    val profile: com.spotbot.core.trading.StrategyProfile = com.spotbot.core.trading.StrategyProfile.ADAPTIVE,
    /**
     * Bir emir, 15M mumun USDT hacminin en fazla bu payı kadar dolabilir; fazlası kısmi gerçekleşir.
     * Geçmiş emir defteri olmadığından likidite bu yolla sınırlanır.
     */
    val participationRate: Double = 0.1,
    /**
     * Canlı motordaki gibi: piyasa girişinde dolum fiyatı sinyal kapanışından bu yüzdeden fazla yukarıdaysa emir
     * gönderilmez (EngineConfig.maxEntryDriftPercent ile aynı varsayılan).
     */
    val maxEntryDriftPercent: Double = 0.3,
)

/** Bir paritenin backtest için tarihsel mumları. */
@Serializable
data class HistoricalDataset(
    val symbol: String,
    val m15: List<Candle>,
    val h1: List<Candle>,
    val h4: List<Candle>,
    /** İsteğe bağlı 5M mumlar: yalnız aynı 15M mumda stop ve hedefin birlikte görüldüğü durumları çözmek için. */
    val m5: List<Candle> = emptyList(),
)

@Serializable
data class StrategyStats(val strategy: StrategyId, val stats: PerformanceStats)

@Serializable
data class BacktestReport(
    val symbols: List<String>,
    val startTime: Long,
    val endTime: Long,
    val initialCapital: Double,
    val finalEquity: Double,
    val netReturnPercent: Double,
    val maxDrawdownPercent: Double,
    val trades: Int,
    val winRatePercent: Double,
    val averageWin: Double,
    val averageLoss: Double,
    val profitFactor: Double,
    /** İşlem başına beklenen değer (USDT). */
    val expectancy: Double,
    val expectancyR: Double,
    val totalFees: Double,
    val spreadAndSlippageCost: Double,
    val exposurePercent: Double,
    val btcBuyAndHoldPercent: Double?,
    val perStrategy: List<StrategyStats>,
    val exitReasons: Map<String, Int>,
    val setupsFound: Int,
    /** Bulunan kurulumları en sık engelleyen zorunlu kurallar. */
    val blockedByRule: Map<String, Int>,
    val closedTrades: List<ClosedTrade>,
    val equityCurve: List<Pair<Long, Double>>,
    val warnings: List<String>,
    /** Limit giriş emri verilip dolmayan kurulum sayısı. */
    val limitOrdersNotFilled: Int = 0,
    val shadow: ShadowReport? = null,
    /** Kısmi gerçekleşen giriş/çıkış emri sayısı (likidite sınırı). */
    val partialFills: Int = 0,
    /** Borsa kuralları ya da likidite nedeniyle gerçekleşmeyen emirler (neden → sayı). */
    val rejectedOrders: Map<String, Int> = emptyMap(),
    val profile: com.spotbot.core.trading.StrategyProfile = com.spotbot.core.trading.StrategyProfile.ADAPTIVE,
    /** Tüm işlemlerin ölçüleri (kazanç/kayıp sayısı, brüt kâr/zarar, ortalama R, ücretler). */
    val overall: PerformanceStats = PerformanceStats.of(emptyList(), initialCapital),
    /** Gün ve ay sonu özsermayesine göre değişim (açık pozisyonlar piyasa değeriyle). */
    val dailyReturns: List<PeriodReturn> = emptyList(),
    val monthlyReturns: List<PeriodReturn> = emptyList(),
    val intrabar: IntrabarStats = IntrabarStats(),
    /** 5M verisi olmadığı için ihtiyatlı kuralla çözülen mumlar (uygulama bunları indirip yeniden çalıştırır). */
    val missingSubBars: List<SubBarRequest> = emptyList(),
    /** Her işlemin giriş anındaki özellikleri (sembol + sinyal zamanıyla eşleşir). */
    val entryFeatures: List<TradeFeatures> = emptyList(),
    /** Pozisyon kimliği → işlem süresince en iyi/en kötü hareket. */
    val excursions: Map<String, Excursion> = emptyMap(),
    /** Dolum fiyatı sinyalden çok uzaklaştığı için gönderilmeyen giriş emirleri (canlı motorla aynı kural). */
    val entriesSkippedForDrift: Int = 0,
    val regimePolicy: com.spotbot.core.analysis.RegimePolicy = com.spotbot.core.analysis.RegimePolicy.OFF,
)

/** Tarihsel veriyi MEXC'den indirir (15M, 1H, 4H; BTCUSDT her zaman eklenir). */
class BacktestDataLoader(private val api: MarketDataApi) {
    suspend fun fetch(symbol: String, timeframe: Timeframe, start: Long, end: Long): List<Candle> = api.klinesBetween(symbol, timeframe, start, end)

    suspend fun load(
        symbols: List<String>,
        start: Long,
        end: Long,
        onProgress: (String) -> Unit = {},
    ): Map<String, HistoricalDataset> {
        val all = (symbols + "BTCUSDT").distinct()
        return all.associateWith { symbol ->
            onProgress("$symbol indiriliyor…")
            HistoricalDataset(
                symbol,
                m15 = api.klinesBetween(symbol, Timeframe.M15, start, end),
                h1 = api.klinesBetween(symbol, Timeframe.H1, start, end),
                h4 = api.klinesBetween(symbol, Timeframe.H4, start, end),
            )
        }
    }
}

/** Belirsiz 15M mumlar için 5M mumları indirip veri setine ekler (yalnız gereken pencereler). */
suspend fun BacktestDataLoader.addSubBars(data: Map<String, HistoricalDataset>, requests: Collection<SubBarRequest>): Map<String, HistoricalDataset> {
    if (requests.isEmpty()) return data
    val bySymbol = requests.groupBy { it.symbol }
    return data.mapValues { (symbol, dataset) ->
        val windows = bySymbol[symbol] ?: return@mapValues dataset
        val extra = windows.flatMap { w ->
            fetch(symbol, Timeframe.M5, w.openTime, w.openTime + Timeframe.M15.millis)
                .filter { it.openTime >= w.openTime && it.openTime < w.openTime + Timeframe.M15.millis }
        }
        dataset.copy(m5 = (dataset.m5 + extra).distinctBy { it.openTime }.sortedBy { it.openTime })
    }
}

/**
 * Backtest'i çalıştırır; aynı 15M mumda stop ve hedefin birlikte görüldüğü her durum için 5M mumları indirir ve
 * testi yeniden çalıştırır (bir mumun sonucu sonraki kararları etkilediği için tüm test tekrarlanır). 5M verisi
 * bulunamayan mumlar raporda "belirsiz" olarak kalır ve ihtiyatlı kural (stop önce) uygulanır.
 */
suspend fun BacktestEngine.runResolvingIntrabar(
    data: Map<String, HistoricalDataset>,
    symbolInfo: Map<String, SymbolInfo>,
    loader: BacktestDataLoader,
    maxPasses: Int = 4,
    onProgress: (Double) -> Unit = {},
): Pair<BacktestReport, Map<String, HistoricalDataset>> {
    var current = data
    val requested = HashSet<SubBarRequest>()
    repeat(maxPasses) {
        val report = run(current, symbolInfo, onProgress)
        val fresh = report.missingSubBars.filter { it !in requested }
        if (fresh.isEmpty()) return report to current
        requested += fresh
        current = loader.addSubBars(current, fresh)
    }
    return run(current, symbolInfo, onProgress) to current
}

/**
 * Olay güdümlü backtest. Canlı motorla aynı tarama, strateji, kural ve risk kodunu kullanır.
 *
 * Gerçekçilik varsayımları:
 * - Sinyal 15M mum kapanışında oluşur, giriş bir sonraki mumun açılışından (spread + kayma ile) yapılır.
 * - Aynı 15M mumda hem stop hem hedef görülürse 5M mumlarla hangisinin önce geldiği doğrulanır; 5M verisi yoksa
 *   ya da aynı 5M mumda da ikisi birden varsa stopun önce gerçekleştiği kabul edilir (ihtiyatlı) ve işlem
 *   "belirsiz" olarak işaretlenir.
 * - Fiyat stopun altında açılırsa (boşluk) çıkış açılış fiyatından yapılır.
 * - Geçmiş emir defteri olmadığından spread sabit [BacktestConfig.halfSpread] ile modellenir, derinlik
 *   filtresi uygulanmaz; 24 saatlik hacim 1H mumlardan hesaplanır.
 */
class BacktestEngine(private val config: BacktestConfig, private val ruleConfig: RuleConfig = RuleConfig.default()) {

    private class Pending(val decision: CandidateDecision.Enter, val costs: CostModel, var barsLeft: Int)

    fun run(
        data: Map<String, HistoricalDataset>,
        symbolInfo: Map<String, SymbolInfo>,
        onProgress: (Double) -> Unit = {},
    ): BacktestReport {
        val pipeline = DecisionPipeline(RuleEngine(ruleConfig), config.risk, config.enabledStrategies, config.strategyParams, config.execution, config.profile)
        var partialFills = 0
        val rejectedOrders = HashMap<String, Int>()
        val execution = config.execution
        val maker = execution.entryMode == EntryMode.LIMIT
        var limitNotFilled = 0
        val shadows = ArrayList<ShadowTrade>()
        val shadowBusyUntil = HashMap<String, Long>()
        var shadowNotFilled = 0
        val series = data.mapValues { (symbol, d) ->
            Triple(
                Series(symbol, Timeframe.M15, d.m15, config.analysis),
                Series(symbol, Timeframe.H1, d.h1, config.analysis),
                Series(symbol, Timeframe.H4, d.h4, config.analysis),
            )
        }
        val costsBySymbol = data.keys.associateWith { symbol ->
            CostModel(symbolInfo[symbol]?.takerFee ?: config.defaultTakerFee, config.slippage, config.halfSpread)
        }
        val tradable = config.symbols.filter { it in series }
        val timeline = series.values.flatMap { it.first.candles.map { c -> c.closeTime } }
            .filter { it > config.startTime && it <= config.endTime }
            .distinct()
            .sorted()

        var state = PortfolioState.create(config.initialCapital)
        var pending = emptyList<Pending>()
        var peak = config.initialCapital
        var maxDrawdown = 0.0
        var exposedSteps = 0
        var setupsFound = 0
        val blocked = HashMap<String, Int>()
        val curve = ArrayList<Pair<Long, Double>>()
        val daily = ArrayList<Pair<Long, Double>>()
        val subBars = data.mapValues { (_, d) -> d.m5.associateBy { it.openTime } }
        var intrabar = IntrabarStats()
        val missingSubBars = LinkedHashSet<SubBarRequest>()
        var frictionTotal = 0.0
        var driftSkipped = 0
        val features = ArrayList<TradeFeatures>()

        timeline.forEachIndexed { step, t ->
            val bars = tradable.associateWith { s -> series.getValue(s).first.let { m15 -> m15.indexAtOrBefore(t).takeIf { it >= 0 && m15.candles[it].closeTime == t }?.let(m15.candles::get) } }

            // 1) Bekleyen girişler: piyasa emri bu mumun açılışında; limit emir yalnız fiyat limitin altında işlem görürse.
            //    Her iki durumda da mum hacminin [participationRate] payıyla sınırlı ve borsa emir kurallarına tabi.
            val stillPending = mutableListOf<Pending>()
            for (p in pending) {
                val bar = bars[p.decision.symbol] ?: continue
                val setup = p.decision.setup
                if (bar.open <= setup.stopPrice || state.positions.any { it.symbol == setup.symbol }) continue
                val info = symbolInfo[setup.symbol]
                val limit = com.spotbot.core.trading.OrderRules.roundPrice(setup.entryPrice, com.spotbot.core.trading.OrderSide.BUY, info)
                val unitCost = if (maker) limit * (1 + p.costs.makerFee) else p.costs.buyPrice(bar.open) * (1 + p.costs.takerFee)
                val qty = minOf(p.decision.sizing.quantity, state.availableCash / unitCost)
                val result = if (maker) {
                    com.spotbot.core.trading.FillModel.limitFromBar(com.spotbot.core.trading.OrderSide.BUY, qty, limit, bar.low, bar.high, bar.quoteVolume, config.participationRate, p.costs, info)
                } else {
                    com.spotbot.core.trading.FillModel.marketFromBar(com.spotbot.core.trading.OrderSide.BUY, qty, bar.open, bar.quoteVolume, config.participationRate, p.costs, info)
                }
                when (result) {
                    is com.spotbot.core.trading.FillResult.Rejected -> {
                        if (maker && result.reason.startsWith("Fiyat limit")) {
                            p.barsLeft--
                            if (p.barsLeft > 0) stillPending += p else limitNotFilled++
                        } else {
                            rejectedOrders.merge(result.reason.substringBefore(" (").take(60), 1, Int::plus)
                        }
                    }
                    is com.spotbot.core.trading.FillResult.Filled -> {
                        val drift = (result.fill.averagePrice / setup.entryPrice - 1) * 100
                        if (!maker && drift > config.maxEntryDriftPercent) {
                            driftSkipped++
                            continue
                        }
                        if (result.fill.partial) partialFills++
                        if (!maker) frictionTotal += (result.fill.averagePrice - bar.open) * result.fill.quantity
                        state = PaperAccount.openWithFill(
                            state, setup, result.fill.copy(requestedQuantity = p.decision.sizing.quantity), p.decision.sizing.riskAmount,
                            p.decision.report.qualityScore, p.decision.rationale, null, bar.openTime,
                        )
                    }
                }
            }
            pending = stillPending

            // 2) Açık pozisyonlarda mum içi stop / hedef / kısmi kâr / başa baş / süre kontrolü (ihtiyatlı sıra);
            //    çıkış emirleri de mum hacmiyle sınırlı (yetmezse kalan bir sonraki mumda satılır).
            //    Aynı mumda stop ve hedef birlikte görülürse sıra 5M mumlarla doğrulanır.
            for (position in state.positions.toList()) {
                val bar = bars[position.symbol] ?: continue
                val costs = costsBySymbol.getValue(position.symbol)
                val info = symbolInfo[position.symbol]
                val fill = { p: com.spotbot.core.trading.Position, action: com.spotbot.core.trading.ExitAction, requested: Double, b: Candle ->
                    barExitFill(p, action, requested, b, costs, info)?.also {
                        if (it.partial) partialFills++
                        if (!it.maker) frictionTotal += (exitReference(action) - it.averagePrice) * it.quantity
                    }
                }
                if (!isAmbiguous(position, bar, execution)) {
                    val actions = ExitManager.onBar(position, bar, execution)
                    if (actions.isNotEmpty()) state = ExitManager.applyWithFills(state, position.id, actions, t) { p, a, r -> fill(p, a, r, bar) }
                    continue
                }
                val subs = (0 until 3).mapNotNull { k -> subBars[position.symbol]?.get(bar.openTime + k * Timeframe.M5.millis) }
                val before = state.closedTrades.size
                val tag: String
                if (subs.size < 3) {
                    missingSubBars += SubBarRequest(position.symbol, bar.openTime)
                    intrabar = intrabar.copy(ambiguousBars = intrabar.ambiguousBars + 1, unresolvedNoData = intrabar.unresolvedNoData + 1)
                    state = ExitManager.applyWithFills(state, position.id, ExitManager.onBar(position, bar, execution), t) { p, a, r -> fill(p, a, r, bar) }
                    tag = "BELİRSİZ: aynı 15M mumda stop ve hedef görüldü, 5M veri yok; ihtiyatlı olarak stop varsayıldı"
                } else {
                    var sameSubBar = false
                    for (sub in subs) {
                        val current = state.positions.firstOrNull { it.id == position.id } ?: break
                        if (isAmbiguous(current, sub, execution)) sameSubBar = true
                        val actions = ExitManager.onBar(current, sub, execution)
                        if (actions.isNotEmpty()) state = ExitManager.applyWithFills(state, position.id, actions, sub.closeTime) { p, a, r -> fill(p, a, r, sub) }
                    }
                    val closed = state.closedTrades.drop(before).lastOrNull { it.position.id == position.id }
                    intrabar = intrabar.copy(
                        ambiguousBars = intrabar.ambiguousBars + 1,
                        unresolvedSameSubBar = intrabar.unresolvedSameSubBar + if (sameSubBar) 1 else 0,
                        resolvedTargetFirst = intrabar.resolvedTargetFirst + if (!sameSubBar && closed?.reason == ExitReason.TAKE_PROFIT) 1 else 0,
                        resolvedStopFirst = intrabar.resolvedStopFirst + if (!sameSubBar && closed?.reason != ExitReason.TAKE_PROFIT) 1 else 0,
                    )
                    tag = if (sameSubBar) "BELİRSİZ: aynı 5M mumda da stop ve hedef görüldü; ihtiyatlı olarak stop varsayıldı"
                    else "Aynı 15M mumda stop ve hedef görüldü; sıra 5M mumlarla doğrulandı"
                }
                state = state.copy(
                    closedTrades = state.closedTrades.mapIndexed { k, trade ->
                        if (k >= before && trade.position.id == position.id) trade.copy(note = trade.note + " · " + tag) else trade
                    },
                )
            }

            // 3) 1H kapanışında yapısal bozulma kontrolü.
            if (t % Timeframe.H1.millis == 0L) {
                for (position in state.positions.toList()) {
                    val h1 = series.getValue(position.symbol).second
                    val i = h1.indexAtOrBefore(t)
                    val bar = bars[position.symbol]
                    if (i >= 0 && h1.candles[i].closeTime == t && bar != null && ExitRules.onHourlyClose(position, h1.close[i])) {
                        val action = com.spotbot.core.trading.ExitAction.Close(h1.close[i], ExitReason.INVALIDATION, false, "1H kapanış geçersizlik seviyesinin altında")
                        val costs = costsBySymbol.getValue(position.symbol)
                        state = ExitManager.applyWithFills(state, position.id, listOf(action), t) { p, a, requested ->
                            barExitFill(p, a, requested, bar, costs, symbolInfo[p.symbol])?.also {
                                if (it.partial) partialFills++
                                frictionTotal += (exitReference(a) - it.averagePrice) * it.quantity
                            }
                        }
                    }
                }
            }

            // 4) Özsermaye ve düşüş takibi.
            val closes = data.keys.associateWith { s -> series.getValue(s).first.let { m15 -> m15.indexAtOrBefore(t).takeIf { it >= 0 }?.let { m15.close[it] } } }
                .filterValues { it != null }.mapValues { it.value!! }
            state = PaperAccount.withPrices(state, closes)
            val equity = state.equity(closes) { costsBySymbol.getValue(it) }
            state = PaperAccount.rollDay(state, t, equity)
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, (peak - equity) / peak * 100)
            if (state.positions.isNotEmpty()) exposedSteps++
            if (step % 96 == 0) curve += t to equity
            if (t % DAY == 0L) daily += t to equity

            // 5) Tarama ve karar (yalnızca 15M kapanışlarında, kapanmış mumlarla).
            val views = tradable.associateWith { s -> multiTimeframe(s, series.getValue(s), t) }
            val btc = series["BTCUSDT"]?.let { multiTimeframe("BTCUSDT", it, t) }
            val coins = tradable.mapNotNull { s ->
                val info = symbolInfo[s] ?: return@mapNotNull null
                val mtf = views[s]
                CoinMarketData(info, syntheticTicker(s, series.getValue(s).second, t), syntheticBook(s, closes[s], t), null, mtf)
            }
            val report = Scanner.scan(coins, btc, config.scanner, t)
            if (report.candidates.isEmpty()) return@forEachIndexed

            val hourlyCloses = tradable.associateWith { s ->
                val h1 = series.getValue(s).second
                val i = h1.indexAtOrBefore(t)
                if (i < 0) DoubleArray(0) else h1.close.copyOfRange(maxOf(0, i - config.risk.correlationWindowBars), i + 1)
            }
            val newEntries = mutableListOf<Pending>()
            for (symbol in report.candidates) {
                val mtf = views[symbol] ?: continue
                val costs = costsBySymbol.getValue(symbol)
                val pendingAsPositions = newEntries.map { it.decision }
                val context = DecisionContext(
                    now = t,
                    equity = equity,
                    availableQuote = state.cash - pendingAsPositions.sumOf { it.sizing.notional * (1 + costs.takerFee) },
                    openPositions = state.positions + (pendingAsPositions + pending.map { it.decision }).map { it.asPendingPosition(t) },
                    dailyPnlPercent = (equity - state.dayStartEquity) / state.dayStartEquity * 100,
                    market = report.market,
                    btc = btc,
                    hourlyCloses = hourlyCloses,
                    closedTrades = state.closedTrades,
                )
                val decision = pipeline.evaluate(Candidate(mtf, report.coins.firstOrNull { it.symbol == symbol }, symbolInfo[symbol], costs), context)
                when (decision) {
                    is CandidateDecision.Enter -> {
                        setupsFound++
                        newEntries += Pending(decision, costs, if (maker) execution.limitEntryMaxBars else 1)
                        features += FeatureExtractor.extract(mtf, btc, decision.setup, decision.report.qualityScore)
                    }
                    is CandidateDecision.Reject -> {
                        setupsFound++
                        decision.report.mandatoryFailures.forEach { blocked.merge("${it.id} ${it.title}", 1, Int::plus) }
                    }
                    is CandidateDecision.NoSetup -> Unit
                }
                if (config.shadowAnalysis) {
                    val (setup, ruleReport) = when (decision) {
                        is CandidateDecision.Enter -> decision.setup to decision.report
                        is CandidateDecision.Reject -> decision.setup to decision.report
                        is CandidateDecision.NoSetup -> null to null
                    }
                    val key = "$symbol/${setup?.strategy}"
                    if (setup != null && ruleReport != null && t >= (shadowBusyUntil[key] ?: Long.MIN_VALUE)) {
                        val (m15s, h1s, _) = series.getValue(symbol)
                        val outcome = ShadowSimulator.simulate(setup, m15s, h1s, costs, execution)
                        if (outcome == null) {
                            shadowNotFilled++
                        } else {
                            shadowBusyUntil[key] = outcome.second
                            shadows += ShadowTrade(
                                symbol, setup.strategy.code, setup.signalTime, decision is CandidateDecision.Enter,
                                ruleReport.qualityScore, ShadowSimulator.outcomes(ruleReport), outcome.first, outcome.second,
                            )
                        }
                    }
                }
            }
            pending = newEntries
            if (step % 200 == 0) onProgress(step.toDouble() / timeline.size)
        }

        // Dönem sonunda açık pozisyonları son fiyattan kapat.
        val last = timeline.lastOrNull() ?: config.endTime
        for (position in state.positions.toList()) {
            val m15 = series.getValue(position.symbol).first
            val i = m15.indexAtOrBefore(last)
            if (i >= 0) {
                val costs = costsBySymbol.getValue(position.symbol)
                frictionTotal += (m15.close[i] - costs.sellPrice(m15.close[i])) * position.quantity
                state = PaperAccount.close(state, position.id, m15.close[i], ExitReason.MANUAL, "Backtest dönemi sonu", costs, last)
            }
        }
        curve += last to state.cash
        if (daily.lastOrNull()?.first != last) daily += last to state.cash
        onProgress(1.0)
        val titles = BuiltInRules.all.associate { it.id to (it.title to it.group) } +
            ruleConfig.custom.associate { it.id to (it.title to it.group) }
        val shadowReport = if (config.shadowAnalysis) ShadowSimulator.report(shadows, titles, shadowNotFilled) else null
        val trades = state.closedTrades
        val usedFeatures = features.filter { f -> trades.any { it.position.symbol == f.symbol && it.position.setup.signalTime == f.signalTime } }
        return report(state, maxDrawdown, exposedSteps, timeline.size, setupsFound, blocked, curve, series["BTCUSDT"]?.first)
            .copy(
                limitOrdersNotFilled = limitNotFilled, shadow = shadowReport, partialFills = partialFills, rejectedOrders = rejectedOrders,
                profile = config.profile,
                spreadAndSlippageCost = frictionTotal,
                dailyReturns = PeriodReturns.of(daily, config.initialCapital, trades, monthly = false),
                monthlyReturns = PeriodReturns.of(daily, config.initialCapital, trades, monthly = true),
                intrabar = intrabar,
                missingSubBars = missingSubBars.toList(),
                entryFeatures = usedFeatures,
                excursions = trades.associate { it.position.id to excursion(it, series.getValue(it.position.symbol).first) },
                entriesSkippedForDrift = driftSkipped,
                regimePolicy = config.strategyParams.regimePolicy,
            )
    }

    /** Stop ile hedef (ya da kısmi kâr seviyesi) aynı mumda görüldü mü; açılış stopun altındaysa boşluktur, belirsiz değildir. */
    private fun isAmbiguous(p: com.spotbot.core.trading.Position, bar: Candle, cfg: ExecutionConfig): Boolean {
        if (p.closingReason != null || bar.open <= p.stopPrice || bar.open >= p.targetPrice || bar.low > p.stopPrice) return false
        if (bar.high >= p.targetPrice) return true
        val partial = cfg.partialTakeProfitAtR?.let { p.entryPrice + it * (p.entryPrice - p.initialStopPrice) }
        return partial != null && !p.partialTaken && bar.high >= partial
    }

    private fun exitReference(action: com.spotbot.core.trading.ExitAction): Double = when (action) {
        is com.spotbot.core.trading.ExitAction.Close -> action.price
        is com.spotbot.core.trading.ExitAction.PartialClose -> action.price
        is com.spotbot.core.trading.ExitAction.MoveStop -> 0.0
    }

    /** İşlem süresince 15M mumların en yüksek/en düşük değerleri, ilk riske (R) oranla. */
    private fun excursion(trade: ClosedTrade, m15: Series): Excursion {
        val p = trade.position
        val risk = (p.entryPrice - p.initialStopPrice).coerceAtLeast(p.entryPrice * 1e-9)
        val from = m15.indexAtOrBefore(p.openedAt + Timeframe.M15.millis)
        val to = m15.indexAtOrBefore(trade.closedAt)
        if (from < 0 || to < from) return Excursion(0.0, 0.0, 0)
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        for (i in from..to) { high = max(high, m15.high[i]); low = minOf(low, m15.low[i]) }
        return Excursion((high - p.entryPrice) / risk, (low - p.entryPrice) / risk, to - from + 1)
    }

    /** Mum içi çıkış dolumu: piyasa satışı açılış/stop referansından kayma ile, limit satış seviyeden; hacimle sınırlı. */
    private fun barExitFill(
        position: com.spotbot.core.trading.Position,
        action: com.spotbot.core.trading.ExitAction,
        requested: Double,
        bar: com.spotbot.core.model.Candle,
        costs: CostModel,
        info: SymbolInfo?,
    ): com.spotbot.core.trading.Fill? {
        val (price, maker) = when (action) {
            is com.spotbot.core.trading.ExitAction.Close -> action.price to action.maker
            is com.spotbot.core.trading.ExitAction.PartialClose -> action.price to action.maker
            is com.spotbot.core.trading.ExitAction.MoveStop -> return null
        }
        val side = com.spotbot.core.trading.OrderSide.SELL
        val result = if (maker) {
            com.spotbot.core.trading.FillModel.limitFromBar(side, requested, price, bar.low, bar.high, bar.quoteVolume, config.participationRate, costs, info)
        } else {
            com.spotbot.core.trading.FillModel.marketFromBar(side, requested, price, bar.quoteVolume, config.participationRate, costs, info)
        }
        val full = requested >= position.quantity * (1 - 1e-9)
        return when (result) {
            is com.spotbot.core.trading.FillResult.Filled -> com.spotbot.core.trading.FillModel.absorbDust(result.fill, position.quantity, info)
            // Pozisyonun son kalanı borsada satılamayacak kadar küçükse (toz) aynı fiyattan kapanmış sayılır;
            // aksi halde pozisyon dönem sonuna kadar açık kalır ve işlem sınırını boşuna meşgul eder.
            is com.spotbot.core.trading.FillResult.Rejected -> {
                val fillPrice = if (maker) price else costs.sellPrice(price)
                if (full && com.spotbot.core.trading.OrderRules.isDust(requested, fillPrice, info)) com.spotbot.core.trading.FillModel.dustExit(requested, fillPrice, maker, costs) else null
            }
        }
    }

    private fun multiTimeframe(symbol: String, s: Triple<Series, Series, Series>, t: Long): MultiTimeframe? {
        val (m15, h1, h4) = s
        val mtf = MultiTimeframe(symbol, h4.analyze(h4.indexAtOrBefore(t)), h1.analyze(h1.indexAtOrBefore(t)), m15.analyze(m15.indexAtOrBefore(t)))
        return mtf.takeIf { it.isValid }
    }

    private fun syntheticTicker(symbol: String, h1: Series, t: Long): Ticker24h? {
        val i = h1.indexAtOrBefore(t)
        if (i < 24) return null
        val volume = (i - 23..i).sumOf { h1.candles[it].quoteVolume }
        val change = (h1.close[i] / h1.close[i - 24] - 1) * 100
        return Ticker24h(symbol, h1.close[i], change, 0.0, 0.0, 0.0, volume, h1.close[i], h1.close[i])
    }

    private fun syntheticBook(symbol: String, price: Double?, t: Long): BookTicker? = price?.let {
        BookTicker(symbol, it * (1 - config.halfSpread), 0.0, it * (1 + config.halfSpread), 0.0, t)
    }

    private fun report(
        state: PortfolioState,
        maxDrawdown: Double,
        exposedSteps: Int,
        steps: Int,
        setupsFound: Int,
        blocked: Map<String, Int>,
        curve: List<Pair<Long, Double>>,
        btc: Series?,
    ): BacktestReport {
        val trades = state.closedTrades
        val wins = trades.filter { it.netPnl > 0 }
        val losses = trades.filter { it.netPnl <= 0 }
        val grossWin = wins.sumOf { it.netPnl }
        val grossLoss = -losses.sumOf { it.netPnl }
        val finalEquity = state.cash
        val btcReturn = btc?.let { s ->
            val a = s.indexAtOrBefore(config.startTime).coerceAtLeast(0)
            val b = s.indexAtOrBefore(config.endTime)
            if (b > a) ((s.close[b] * (1 - config.defaultTakerFee)) / (s.close[a] * (1 + config.defaultTakerFee)) - 1) * 100 else null
        }
        val warnings = buildList {
            if (trades.size < 30) add("İşlem sayısı (${trades.size}) istatistiksel olarak güvenilir sonuç için az; dönemi ya da coin sayısını artırın.")
            add("Spread sabit (%%%.3f) modellendi; geçmiş emir defteri derinliği test edilmedi.".format(config.halfSpread * 200))
        }
        return BacktestReport(
            symbols = config.symbols,
            startTime = config.startTime,
            endTime = config.endTime,
            initialCapital = config.initialCapital,
            finalEquity = finalEquity,
            netReturnPercent = (finalEquity / config.initialCapital - 1) * 100,
            maxDrawdownPercent = maxDrawdown,
            trades = trades.size,
            winRatePercent = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
            averageWin = if (wins.isEmpty()) 0.0 else grossWin / wins.size,
            averageLoss = if (losses.isEmpty()) 0.0 else -grossLoss / losses.size,
            profitFactor = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0,
            expectancy = if (trades.isEmpty()) 0.0 else trades.sumOf { it.netPnl } / trades.size,
            expectancyR = if (trades.isEmpty()) 0.0 else trades.sumOf { it.rMultiple } / trades.size,
            totalFees = trades.sumOf { it.fees },
            spreadAndSlippageCost = 0.0,
            exposurePercent = if (steps == 0) 0.0 else exposedSteps * 100.0 / steps,
            btcBuyAndHoldPercent = btcReturn,
            perStrategy = trades.groupBy { it.position.strategy }.toSortedMap().map { (strategy, list) -> StrategyStats(strategy, PerformanceStats.of(list, config.initialCapital)) },
            exitReasons = trades.groupingBy { it.reason.title }.eachCount(),
            setupsFound = setupsFound,
            blockedByRule = blocked.entries.sortedByDescending { it.value }.associate { it.key to it.value },
            closedTrades = trades,
            equityCurve = curve,
            warnings = warnings,
            overall = PerformanceStats.of(trades, config.initialCapital).copy(maxDrawdownPercent = maxDrawdown),
        )
    }

    companion object {
        private const val DAY = 24 * 60 * 60_000L
    }
}
