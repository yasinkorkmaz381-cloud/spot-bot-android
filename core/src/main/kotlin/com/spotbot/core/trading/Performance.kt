package com.spotbot.core.trading

import kotlinx.serialization.Serializable

@Serializable
data class PeriodResult(val netPnl: Double, val trades: Int, val percentOfCapital: Double)

@Serializable
data class PerformanceSummary(
    val today: PeriodResult,
    val week: PeriodResult,
    val month: PeriodResult,
    val allTime: PeriodResult,
    val winRatePercent: Double,
    val profitFactor: Double,
    val averageR: Double,
    val totalFees: Double,
)

/** Kapanmış işlemlerden dönemsel net performans (gerçekleşmemiş K/Z hariç). */
object Performance {
    private const val DAY = 24 * 60 * 60_000L

    fun summarize(state: PortfolioState, now: Long): PerformanceSummary {
        val trades = state.closedTrades
        val dayStart = Math.floorDiv(now, DAY) * DAY
        fun period(from: Long): PeriodResult {
            val inPeriod = trades.filter { it.closedAt >= from }
            val net = inPeriod.sumOf { it.netPnl }
            return PeriodResult(net, inPeriod.size, net / state.initialCapital * 100)
        }
        val grossWin = trades.filter { it.netPnl > 0 }.sumOf { it.netPnl }
        val grossLoss = -trades.filter { it.netPnl <= 0 }.sumOf { it.netPnl }
        return PerformanceSummary(
            today = period(dayStart),
            week = period(now - 7 * DAY),
            month = period(now - 30 * DAY),
            allTime = period(Long.MIN_VALUE),
            winRatePercent = if (trades.isEmpty()) 0.0 else trades.count { it.netPnl > 0 } * 100.0 / trades.size,
            profitFactor = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0,
            averageR = if (trades.isEmpty()) 0.0 else trades.sumOf { it.rMultiple } / trades.size,
            totalFees = trades.sumOf { it.fees },
        )
    }
}
