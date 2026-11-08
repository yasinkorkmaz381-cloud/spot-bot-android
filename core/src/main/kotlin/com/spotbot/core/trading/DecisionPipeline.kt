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
)

sealed interface CandidateDecision {
    val symbol: String

    data class Enter(
        override val symbol: String,
        val setup: TradeSetup,
        val sizing: SizingResult.Sized,
        val riskReward: RiskReward,
        val report: RuleReport,
        val rationale: TradeRationale,
    ) : CandidateDecision

    data class Reject(override val symbol: String, val setup: TradeSetup, val report: RuleReport) : CandidateDecision

    data class NoSetup(override val symbol: String, val reasons: List<String>) : CandidateDecision
}

/**
 * Aday → strateji (rejime göre) → kurallar → risk. "İşlem açmamak da doğru bir karar olabilir":
 * zorunlu kurallardan biri bile sağlanmazsa sonuç reddedilir ve gerekçesi kaydedilir.
 */
class DecisionPipeline(
    val ruleEngine: RuleEngine = RuleEngine(),
    val riskConfig: RiskConfig = RiskConfig(),
    val enabledStrategies: Set<StrategyId> = setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK),
    strategyParams: StrategyParams = StrategyParams(),
) {
    val strategies: List<Strategy> = listOf(
        BreakoutRetestStrategy(strategyParams),
        TrendPullbackStrategy(strategyParams),
        RangeStrategy(strategyParams),
    )

    fun evaluate(candidate: Candidate, context: DecisionContext): CandidateDecision {
        val mtf = candidate.mtf
        val regime = mtf.h4.regime
        val applicable = strategies.filter { it.id in enabledStrategies && regime in it.suitableRegimes }
        if (applicable.isEmpty()) {
            return CandidateDecision.NoSetup(mtf.symbol, listOf("4H rejimi $regime için etkin strateji yok"))
        }
        val notFound = mutableListOf<String>()
        val evaluated = applicable.mapNotNull { strategy ->
            when (val result = strategy.evaluate(mtf)) {
                is StrategyResult.NotFound -> {
                    notFound += "${strategy.id.code}: ${result.reasons.joinToString("; ")}"
                    null
                }
                is StrategyResult.Found -> assess(strategy, result.setup, candidate, context)
            }
        }
        if (evaluated.isEmpty()) return CandidateDecision.NoSetup(mtf.symbol, notFound)
        return evaluated.filterIsInstance<CandidateDecision.Enter>().maxByOrNull { it.report.qualityScore }
            ?: evaluated.filterIsInstance<CandidateDecision.Reject>().maxBy { it.report.qualityScore }
    }

    private fun assess(strategy: Strategy, setup: TradeSetup, candidate: Candidate, context: DecisionContext): CandidateDecision {
        val rr = netRiskReward(setup.entryPrice, setup.stopPrice, setup.targetPrice, candidate.costs)
        val sizing = PositionSizer.size(
            context.equity, context.availableQuote, setup.entryPrice, setup.stopPrice, setup.targetPrice,
            candidate.info, candidate.costs, riskConfig,
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
        )
        val report = ruleEngine.evaluate(ruleContext)
        if (!report.allowed || sizing !is SizingResult.Sized) return CandidateDecision.Reject(setup.symbol, setup, report)
        return CandidateDecision.Enter(setup.symbol, setup, sizing, rr, report, rationale(setup, candidate, context, rr, sizing))
    }

    /** Aday ile güçlü korelasyonlu (ya da aynı) açık pozisyonların toplam riski, özsermayenin %'si. */
    private fun correlatedRisk(symbol: String, context: DecisionContext): Double {
        val mine = context.hourlyCloses[symbol]
        return context.openPositions.filter { p ->
            if (p.symbol == symbol) return@filter true
            val other = context.hourlyCloses[p.symbol] ?: return@filter true // bilinmiyorsa ihtiyatlı davran
            mine == null || Indicators.returnCorrelation(mine, other, riskConfig.correlationWindowBars) >= riskConfig.correlationThreshold
        }.sumOf { it.riskAmount } / context.equity * 100
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
            riskReward = "Net R:R %.2f (komisyon %%%.3f, spread ve kayma dahil); hedef %s (%s); risk %.2f USDT (%%%.2f)".format(
                rr.ratio, candidate.costs.takerFee * 100, setup.targetPrice.fmt(), setup.targetReason, sizing.riskAmount, sizing.riskPercent,
            ),
            invalidation = setup.invalidationReason,
        )
    }

    private fun Double.fmt() = if (this >= 100) "%.2f".format(this) else if (this >= 1) "%.4f".format(this) else "%.6f".format(this)
}
