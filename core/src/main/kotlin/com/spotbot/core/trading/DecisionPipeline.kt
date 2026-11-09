package com.spotbot.core.trading

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.risk.CostModel
import com.spotbot.core.risk.PositionSizer
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.risk.RiskReward
import com.spotbot.core.risk.SizingResult
import com.spotbot.core.risk.netRiskReward
import com.spotbot.core.rules.RuleContext
import com.spotbot.core.rules.RuleEngine
import com.spotbot.core.rules.RuleGroup
import com.spotbot.core.rules.RuleOutcome
import com.spotbot.core.rules.RuleReport
import com.spotbot.core.scanner.CoinScan
import com.spotbot.core.scanner.MarketState
import com.spotbot.core.strategy.BreakoutRetestStrategy
import com.spotbot.core.strategy.RangeStrategy
import com.spotbot.core.strategy.Strategy
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.StrategyParams
import com.spotbot.core.strategy.StrategyResult
import com.spotbot.core.strategy.TradeSetup
import com.spotbot.core.strategy.TrendPullbackStrategy
import com.spotbot.core.ta.Indicators

/** Ayrıntılı incelemeye alınan bir adayın girdileri. */
class Candidate(
    val mtf: MultiTimeframe,
    val scan: CoinScan?,
    val info: SymbolInfo?,
    val costs: CostModel,
    /** Veri güncel değilse nedeni (Z17). */
    val dataIssue: String? = null,
)

/**
 * Bağımsız test edilebilir strateji profilleri. Hiçbiri mevcut kuralları değiştirmez; yalnız hangi stratejilerin
 * kullanılacağını ve (Seçici'de) ek giriş eşiklerini belirler. Canlıdaki varsayılan Adaptif'tir.
 */
@kotlinx.serialization.Serializable
enum class StrategyProfile(val title: String, val description: String) {
    ADAPTIVE("Adaptif", "4H rejimine göre uygun strateji (varsayılan: A ve B; C ayarlardan açılırsa)"),
    TREND("Trend", "Yalnız B – Trend Pullback"),
    SELECTIVE("Seçici", "A+B; yalnız kalite puanı ≥ 60 ve net R:R ≥ 2 olan kurulumlar"),
    ;

    fun strategies(configured: Set<StrategyId>): Set<StrategyId> = when (this) {
        ADAPTIVE -> configured
        TREND -> setOf(StrategyId.TREND_PULLBACK)
        SELECTIVE -> setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK)
    }

    companion object {
        const val SELECTIVE_MIN_QUALITY = 60.0
        const val SELECTIVE_MIN_NET_RR = 2.0
    }
}

/** Bir stratejinin bir aday için sonucu (karar günlüğü için). */
class StrategyEvaluation(
    val strategy: StrategyId,
    val applicable: Boolean,
    val reasons: List<String>,
    val setup: TradeSetup? = null,
    val riskReward: RiskReward? = null,
    val report: RuleReport? = null,
)

/** Kararın verildiği andaki portföy ve piyasa bağlamı. */
class DecisionContext(
    val now: Long,
    val equity: Double,
    val availableQuote: Double,
    val openPositions: List<Position>,
    val dailyPnlPercent: Double,
    val market: MarketState,
    val btc: MultiTimeframe?,
    /** Korelasyon için 1H kapanış dizileri (sembol → kapanışlar). */
    val hourlyCloses: Map<String, DoubleArray>,
    /** Kapanmış işlemler (coin dinlenmesi için). */
    val closedTrades: List<ClosedTrade> = emptyList(),
    /** Bekleyen limit emirlerin riski (USDT); açık riske eklenir. */
    val pendingRiskAmount: Double = 0.0,
)

sealed interface CandidateDecision {
    val symbol: String
    val evaluations: List<StrategyEvaluation>

    data class Enter(
        override val symbol: String,
        val setup: TradeSetup,
        val sizing: SizingResult.Sized,
        val riskReward: RiskReward,
        val report: RuleReport,
        val rationale: TradeRationale,
        override val evaluations: List<StrategyEvaluation> = emptyList(),
    ) : CandidateDecision

    data class Reject(
        override val symbol: String,
        val setup: TradeSetup,
        val report: RuleReport,
        override val evaluations: List<StrategyEvaluation> = emptyList(),
    ) : CandidateDecision

    data class NoSetup(
        override val symbol: String,
        val reasons: List<String>,
        override val evaluations: List<StrategyEvaluation> = emptyList(),
    ) : CandidateDecision
}

/**
 * Aday → strateji (rejime göre) → kurallar → risk. "İşlem açmamak da doğru bir karar olabilir":
 * zorunlu kurallardan biri bile sağlanmazsa sonuç reddedilir ve gerekçesi kaydedilir.
 */
class DecisionPipeline(
    val ruleEngine: RuleEngine = RuleEngine(),
    val riskConfig: RiskConfig = RiskConfig(),
    val enabledStrategies: Set<StrategyId> = setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK),
    private val strategyParams: StrategyParams = StrategyParams(),
    val execution: ExecutionConfig = ExecutionConfig(),
    val profile: StrategyProfile = StrategyProfile.ADAPTIVE,
) {
    private val makerEntry get() = execution.entryMode == EntryMode.LIMIT

    /** Profile göre kullanılabilir stratejiler. */
    val activeStrategies: Set<StrategyId> = profile.strategies(enabledStrategies)

    val strategies: List<Strategy> = listOf(
        BreakoutRetestStrategy(strategyParams),
        TrendPullbackStrategy(strategyParams),
        RangeStrategy(strategyParams),
    )

    fun evaluate(candidate: Candidate, context: DecisionContext): CandidateDecision {
        val mtf = candidate.mtf
        val regime = mtf.h4.regime
        val evaluations = mutableListOf<StrategyEvaluation>()
        val decisions = mutableListOf<CandidateDecision>()
        for (strategy in strategies) {
            when {
                strategy.id !in activeStrategies ->
                    evaluations += StrategyEvaluation(strategy.id, false, listOf("${profile.title} profilinde ya da ayarlarda kapalı"))
                regime !in strategy.suitableRegimes ->
                    evaluations += StrategyEvaluation(strategy.id, false, listOf("4H rejimi $regime bu strateji için uygun değil (uygun: ${strategy.suitableRegimes.joinToString()})"))
                else -> when (val result = strategy.evaluate(mtf)) {
                    is StrategyResult.NotFound -> evaluations += StrategyEvaluation(strategy.id, true, result.reasons)
                    is StrategyResult.Found -> {
                        val decision = assess(strategy, applyAtrStop(result.setup, mtf), candidate, context)
                        decisions += decision
                        val (report, rr) = when (decision) {
                            is CandidateDecision.Enter -> decision.report to decision.riskReward
                            is CandidateDecision.Reject -> decision.report to null
                            is CandidateDecision.NoSetup -> null to null
                        }
                        val setup = (decision as? CandidateDecision.Enter)?.setup ?: (decision as? CandidateDecision.Reject)?.setup
                        evaluations += StrategyEvaluation(strategy.id, true, emptyList(), setup, rr ?: setup?.let { netRiskReward(it.entryPrice, it.stopPrice, it.targetPrice, candidate.costs, makerEntry, execution.limitTakeProfit) }, report)
                    }
                }
            }
        }
        if (decisions.isEmpty()) {
            val reasons = evaluations.filter { it.applicable }.map { "${it.strategy.code}: ${it.reasons.joinToString("; ")}" }
                .ifEmpty { listOf("4H rejimi $regime için ${profile.title} profilinde etkin strateji yok") }
            return CandidateDecision.NoSetup(mtf.symbol, reasons, evaluations)
        }
        return when (val best = decisions.filterIsInstance<CandidateDecision.Enter>().maxByOrNull { it.report.qualityScore }
            ?: decisions.filterIsInstance<CandidateDecision.Reject>().maxBy { it.report.qualityScore }) {
            is CandidateDecision.Enter -> best.copy(evaluations = evaluations)
            is CandidateDecision.Reject -> best.copy(evaluations = evaluations)
            is CandidateDecision.NoSetup -> best
        }
    }

    /** ATR stop seçeneği (varsayılan kapalı). Geçersizlik seviyesi yapısal kalır. */
    private fun applyAtrStop(setup: TradeSetup, mtf: MultiTimeframe): TradeSetup {
        val atr = mtf.h1.indicators.atr
        if (riskConfig.atrStopMode == com.spotbot.core.risk.AtrStopMode.OFF || atr.isNaN() || atr <= 0) return setup
        val atrStop = setup.entryPrice - riskConfig.atrStopMultiple * atr
        return when (riskConfig.atrStopMode) {
            com.spotbot.core.risk.AtrStopMode.FLOOR -> if (atrStop < setup.stopPrice) {
                setup.copy(stopPrice = atrStop, stopReason = setup.stopReason + " Stop, asgari %.1f×ATR(1H) mesafeye genişletildi.".format(riskConfig.atrStopMultiple))
            } else setup
            com.spotbot.core.risk.AtrStopMode.REPLACE -> setup.copy(stopPrice = atrStop, stopReason = "ATR stop: giriş − %.1f×ATR(1H).".format(riskConfig.atrStopMultiple))
            com.spotbot.core.risk.AtrStopMode.OFF -> setup
        }
    }

    private fun assess(strategy: Strategy, setup: TradeSetup, candidate: Candidate, context: DecisionContext): CandidateDecision {
        val rr = netRiskReward(setup.entryPrice, setup.stopPrice, setup.targetPrice, candidate.costs, makerEntry, execution.limitTakeProfit)
        val sizing = PositionSizer.size(
            context.equity, context.availableQuote, setup.entryPrice, setup.stopPrice, setup.targetPrice,
            candidate.info, candidate.costs, riskConfig, makerEntry, execution.limitTakeProfit,
        )
        val newRiskPercent = (sizing as? SizingResult.Sized)?.riskPercent ?: riskConfig.riskPerTradePercent
        val ruleContext = RuleContext(
            setup = setup,
            mtf = candidate.mtf,
            btc = context.btc,
            scan = candidate.scan,
            market = context.market,
            riskConfig = riskConfig,
            riskReward = rr,
            sizing = sizing,
            strategyEnabled = strategy.id in enabledStrategies,
            strategySuitableRegimes = strategy.suitableRegimes,
            now = context.now,
            openSymbols = context.openPositions.map { it.symbol }.toSet(),
            openPositionCount = context.openPositions.size,
            dailyPnlPercent = context.dailyPnlPercent,
            correlatedRiskPercent = correlatedRisk(setup.symbol, context) + newRiskPercent,
            cooldownReason = Cooldown.reason(context.closedTrades, setup.symbol, context.now, execution),
            dataIssue = candidate.dataIssue,
            openRiskPercent = (context.openPositions.sumOf { it.openRisk } + context.pendingRiskAmount) / context.equity * 100,
            newRiskPercent = newRiskPercent,
            marketRegime = com.spotbot.core.analysis.RegimeClassifier.classify(context.btc?.h4),
            regimePolicy = strategyParams.regimePolicy,
        )
        val report = withProfileGates(ruleEngine.evaluate(ruleContext), rr)
        if (!report.allowed || sizing !is SizingResult.Sized) return CandidateDecision.Reject(setup.symbol, setup, report)
        return CandidateDecision.Enter(setup.symbol, setup, sizing, rr, report, rationale(setup, candidate, context, rr, sizing))
    }

    /** Seçici profilin ek zorunlu eşikleri (kurallara eklenir, mevcut kuralları değiştirmez). */
    private fun withProfileGates(report: RuleReport, rr: RiskReward): RuleReport {
        if (profile != StrategyProfile.SELECTIVE) return report
        val gates = listOf(
            RuleOutcome(
                "P01", "Seçici profil: kalite puanı ≥ %.0f".format(StrategyProfile.SELECTIVE_MIN_QUALITY), RuleGroup.MANDATORY,
                report.qualityScore >= StrategyProfile.SELECTIVE_MIN_QUALITY, "Kalite puanı %.0f".format(report.qualityScore), 0.0,
            ),
            RuleOutcome(
                "P02", "Seçici profil: net R:R ≥ %.1f".format(StrategyProfile.SELECTIVE_MIN_NET_RR), RuleGroup.MANDATORY,
                rr.ratio >= StrategyProfile.SELECTIVE_MIN_NET_RR, "Net R:R %.2f".format(rr.ratio), 0.0,
            ),
        )
        return report.copy(outcomes = report.outcomes + gates)
    }

    /** Aday ile güçlü korelasyonlu (ya da aynı) açık pozisyonların toplam riski, özsermayenin %'si. */
    private fun correlatedRisk(symbol: String, context: DecisionContext): Double {
        val mine = context.hourlyCloses[symbol]
        return context.openPositions.filter { p ->
            if (p.symbol == symbol) return@filter true
            val other = context.hourlyCloses[p.symbol] ?: return@filter true // bilinmiyorsa ihtiyatlı davran
            mine == null || Indicators.returnCorrelation(mine, other, riskConfig.correlationWindowBars) >= riskConfig.correlationThreshold
        }.sumOf { it.openRisk } / context.equity * 100
    }

    private fun rationale(
        setup: TradeSetup,
        candidate: Candidate,
        context: DecisionContext,
        rr: RiskReward,
        sizing: SizingResult.Sized,
    ): TradeRationale {
        val mtf = candidate.mtf
        val scan = candidate.scan
        val h4 = mtf.h4.indicators
        val structure = mtf.h1.structure
        return TradeRationale(
            whyCoin = buildString {
                append("Tarama ön puanı ${scan?.preScore ?: "-"}; 4H rejimi ${mtf.h4.regime}")
                scan?.relativeStrength7dPercent?.let { append("; BTC'ye göre 7 günlük göreceli güç %+.1f%%".format(it)) }
                scan?.quoteVolume24h?.let { append("; 24s hacim %,.0f USDT".format(it)) }
            },
            whyEntry = "${setup.strategy.code} – ${setup.strategy.title}: ${setup.entryReason} Uyumlar: ${setup.confluences.joinToString()}. " +
                "Teyitler: ${setup.confirmations.joinToString()}.",
            trendCheck = "4H: ${mtf.h4.regime}, ADX %.1f, SuperTrend %s; 1H yapı %s (%s/%s); BTC 4H: %s".format(
                h4.adx, if (h4.superTrendUp) "yukarı" else "aşağı", structure.trend,
                structure.lastHighLabel ?: "-", structure.lastLowLabel ?: "-", context.btc?.h4?.regime ?: "-",
            ),
            stopReason = "Stop ${setup.stopPrice.fmt()}: ${setup.stopReason}",
            riskReward = "Net R:R %.2f (%s giriş, komisyon ve %s dahil); hedef %s (%s); risk %.2f USDT (%%%.2f)".format(
                rr.ratio, if (makerEntry) "limit (maker)" else "piyasa", if (makerEntry) "stopta kayma" else "spread ve kayma",
                setup.targetPrice.fmt(), setup.targetReason, sizing.riskAmount, sizing.riskPercent,
            ),
            invalidation = setup.invalidationReason,
        )
    }

    private fun Double.fmt() = if (this >= 100) "%.2f".format(this) else if (this >= 1) "%.4f".format(this) else "%.6f".format(this)
}

/** Henüz dolmamış (bekleyen) bir girişi, karar bağlamında açık pozisyon gibi saymak için yer tutucu. */
fun CandidateDecision.Enter.asPendingPosition(now: Long): Position = Position(
    id = "pending-$symbol", symbol = symbol, strategy = setup.strategy, openedAt = now,
    entryPrice = setup.entryPrice, quantity = sizing.quantity, entryFee = 0.0,
    stopPrice = setup.stopPrice, targetPrice = setup.targetPrice, invalidationPrice = setup.invalidationPrice,
    riskAmount = sizing.riskAmount, qualityScore = report.qualityScore, setup = setup,
    rationale = rationale,
)
