package com.spotbot.core.backtest

import com.spotbot.core.analysis.Series
import com.spotbot.core.model.Timeframe
import com.spotbot.core.risk.CostModel
import com.spotbot.core.risk.netRiskReward
import com.spotbot.core.rules.RuleGroup
import com.spotbot.core.rules.RuleReport
import com.spotbot.core.strategy.TradeSetup
import com.spotbot.core.trading.EntryMode
import com.spotbot.core.trading.ExecutionConfig
import com.spotbot.core.trading.ExitManager
import com.spotbot.core.trading.ExitReason
import com.spotbot.core.trading.ExitRules
import com.spotbot.core.trading.PaperAccount
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.TradeRationale
import kotlinx.serialization.Serializable

/** Kural değerlendirmesinden geçen ya da reddedilen bir kurulumun, portföyden bağımsız simüle edilmiş sonucu. */
@Serializable
data class ShadowTrade(
    val symbol: String,
    val strategy: String,
    val signalTime: Long,
    val accepted: Boolean,
    val qualityScore: Double,
    /** Kural kimliği → geçti mi. */
    val rules: Map<String, Boolean>,
    val rMultiple: Double,
    val exitTime: Long,
)

@Serializable
data class RuleLift(
    val id: String,
    val title: String,
    val group: RuleGroup,
    val passCount: Int,
    val passAvgR: Double,
    val failCount: Int,
    val failAvgR: Double,
) {
    /** Kural geçtiğinde ortalama R ile kalmadığında ortalama R farkı. Pozitifse kural sonucu iyileştiriyor. */
    val lift: Double get() = passAvgR - failAvgR
}

@Serializable
data class ScoreBucket(val label: String, val count: Int, val avgR: Double, val winRatePercent: Double)

@Serializable
data class ShadowReport(
    val trades: Int,
    val avgR: Double,
    val winRatePercent: Double,
    val acceptedCount: Int,
    val acceptedAvgR: Double?,
    val rejectedCount: Int,
    val rejectedAvgR: Double?,
    val rules: List<RuleLift>,
    val scoreBuckets: List<ScoreBucket>,
    val byStrategy: Map<String, Double>,
    val limitNotFilled: Int,
)

/**
 * "Gölge işlem": bulunan her kurulum (alınsın ya da kurallarca reddedilsin) tek başına simüle edilir.
 * Böylece her kuralın sonucu gerçekten iyileştirip iyileştirmediği ölçülür. Portföy durumuna bağlı kurallar
 * (açık işlem sayısı, günlük limit, korelasyon, büyüklük, dinlenme) bu ölçümün dışında tutulur.
 */
object ShadowSimulator {
    /** Portföy durumuna bağlı olduğu için kurulum kalitesi hakkında bilgi taşımayan kurallar. */
    val portfolioRules = setOf("Z01", "Z06", "Z11", "Z12", "Z13", "Z14", "Z15", "Z16")
    private const val HORIZON_BARS = 4 * 24 * 7

    /** Kurulumu sinyalden sonraki mumdan itibaren simüle eder; giriş dolmazsa null. Dönüş: (R, çıkış zamanı). */
    fun simulate(setup: TradeSetup, m15: Series, h1: Series, costs: CostModel, execution: ExecutionConfig): Pair<Double, Long>? {
        val signalIndex = m15.indexAtOrBefore(setup.signalTime)
        if (signalIndex < 0 || signalIndex + 1 >= m15.size) return null
        val maker = execution.entryMode == EntryMode.LIMIT
        var fillIndex = -1
        var fillReference = 0.0
        val waitBars = if (maker) execution.limitEntryMaxBars else 1
        for (i in signalIndex + 1..minOf(m15.size - 1, signalIndex + waitBars)) {
            val bar = m15.candles[i]
            if (bar.open <= setup.stopPrice) return null
            if (!maker) { fillIndex = i; fillReference = bar.open; break }
            if (bar.low < setup.entryPrice) { fillIndex = i; fillReference = setup.entryPrice; break }
        }
        if (fillIndex < 0) return null
        val risk = netRiskReward(fillReference, setup.stopPrice, setup.targetPrice, costs, maker, execution.limitTakeProfit).riskPerUnit
        if (risk <= 0) return null
        var state = PaperAccount.open(
            PortfolioState.create(1e12), setup, 1.0, fillReference, costs, risk, 0.0,
            TradeRationale("", "", "", "", "", ""), null, m15.candles[fillIndex].openTime, maker,
        )
        val id = state.positions.single().id
        for (i in fillIndex until minOf(m15.size, fillIndex + HORIZON_BARS)) {
            val bar = m15.candles[i]
            val position = state.positions.firstOrNull() ?: break
            state = ExitManager.apply(state, id, ExitManager.onBar(position, bar, execution), costs, bar.closeTime)
            val still = state.positions.firstOrNull() ?: break
            if (bar.closeTime % Timeframe.H1.millis == 0L) {
                val j = h1.indexAtOrBefore(bar.closeTime)
                if (j >= 0 && h1.candles[j].closeTime == bar.closeTime && ExitRules.onHourlyClose(still, h1.close[j])) {
                    state = PaperAccount.close(state, id, h1.close[j], ExitReason.INVALIDATION, "", costs, bar.closeTime)
                }
            }
        }
        state.positions.firstOrNull()?.let { open ->
            val last = minOf(m15.size - 1, fillIndex + HORIZON_BARS - 1)
            state = PaperAccount.close(state, open.id, m15.close[last], ExitReason.MANUAL, "Ufuk sonu", costs, m15.candles[last].closeTime)
        }
        val trade = state.closedTrades.single()
        return trade.rMultiple to trade.closedAt
    }

    fun report(shadows: List<ShadowTrade>, titles: Map<String, Pair<String, RuleGroup>>, limitNotFilled: Int): ShadowReport {
        fun avg(list: List<ShadowTrade>) = if (list.isEmpty()) null else list.sumOf { it.rMultiple } / list.size
        val ruleIds = shadows.flatMap { it.rules.keys }.toSet() - portfolioRules
        val lifts = ruleIds.mapNotNull { id ->
            val pass = shadows.filter { it.rules[id] == true }
            val fail = shadows.filter { it.rules[id] == false }
            if (pass.isEmpty() || fail.isEmpty()) return@mapNotNull null
            val (title, group) = titles[id] ?: (id to RuleGroup.QUALITY)
            RuleLift(id, title, group, pass.size, avg(pass)!!, fail.size, avg(fail)!!)
        }.sortedByDescending { it.lift }
        val buckets = listOf(0.0 to 25.0, 25.0 to 50.0, 50.0 to 75.0, 75.0 to 100.01).map { (lo, hi) ->
            val inBucket = shadows.filter { it.qualityScore >= lo && it.qualityScore < hi }
            ScoreBucket(
                "%.0f–%.0f".format(lo, minOf(hi, 100.0)), inBucket.size, avg(inBucket) ?: 0.0,
                if (inBucket.isEmpty()) 0.0 else inBucket.count { it.rMultiple > 0 } * 100.0 / inBucket.size,
            )
        }
        val accepted = shadows.filter { it.accepted }
        val rejected = shadows.filterNot { it.accepted }
        return ShadowReport(
            trades = shadows.size,
            avgR = avg(shadows) ?: 0.0,
            winRatePercent = if (shadows.isEmpty()) 0.0 else shadows.count { it.rMultiple > 0 } * 100.0 / shadows.size,
            acceptedCount = accepted.size,
            acceptedAvgR = avg(accepted),
            rejectedCount = rejected.size,
            rejectedAvgR = avg(rejected),
            rules = lifts,
            scoreBuckets = buckets,
            byStrategy = shadows.groupBy { it.strategy }.mapValues { (_, v) -> v.sumOf { it.rMultiple } / v.size },
            limitNotFilled = limitNotFilled,
        )
    }

    fun outcomes(report: RuleReport): Map<String, Boolean> = report.outcomes.associate { it.id to it.passed }
}
