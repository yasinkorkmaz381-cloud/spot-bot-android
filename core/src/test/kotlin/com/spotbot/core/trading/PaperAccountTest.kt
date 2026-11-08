package com.spotbot.core.trading

import com.spotbot.core.risk.CostModel
import com.spotbot.core.support.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperAccountTest {

    private val costs = CostModel(takerFee = 0.001, slippage = 0.0, halfSpread = 0.0)
    private val rationale = TradeRationale("coin", "giriş", "trend", "stop", "rr", "geçersizlik")

    private fun opened(): PortfolioState = PaperAccount.open(
        PortfolioState.create(1_000.0), TestData.setup(entry = 100.0, stop = 98.0, target = 105.0), quantity = 2.0,
        askReference = 100.0, costs = costs, riskAmount = 4.4, qualityScore = 70.0, rationale = rationale, report = null, now = 1,
    )

    @Test
    fun `opening deducts cost plus fee and records the rationale`() {
        val state = opened()
        val position = state.positions.single()
        assertEquals(1_000.0 - 200.0 - 0.2, state.cash, 1e-9)
        assertEquals(0.2, position.entryFee, 1e-12)
        assertEquals(DecisionOutcome.OPENED, state.decisions.last().outcome)
        assertEquals("giriş", position.rationale.whyEntry)
    }

    @Test
    fun `closing at target realizes net profit after both fees`() {
        val state = PaperAccount.close(opened(), "P1", 105.0, ExitReason.TAKE_PROFIT, "hedef", costs, 2)
        val trade = state.closedTrades.single()
        assertTrue(state.positions.isEmpty())
        assertEquals(210.0 - 0.21 - 200.2, trade.netPnl, 1e-9)
        assertEquals(trade.netPnl / 4.4, trade.rMultiple, 1e-9)
        assertEquals(1_000.0 + trade.netPnl, state.cash, 1e-9)
    }

    @Test
    fun `exit rules prefer the stop and detect the target`() {
        val position = opened().positions.single()
        assertEquals(ExitReason.STOP_LOSS, ExitRules.onPrice(position, 98.0))
        assertEquals(ExitReason.TAKE_PROFIT, ExitRules.onPrice(position, 105.0))
        assertNull(ExitRules.onPrice(position, 101.0))
        assertTrue(ExitRules.onHourlyClose(position, position.invalidationPrice - 0.01))
    }

    @Test
    fun `equity includes open positions at liquidation value`() {
        val state = opened()
        val equity = state.equity(mapOf("ETHUSDT" to 101.0)) { costs }
        assertEquals(state.cash + 2 * 101.0 * (1 - 0.001), equity, 1e-9)
    }

    @Test
    fun `day roll resets the daily baseline only on a new UTC day`() {
        val day = 24 * 60 * 60_000L
        val start = PaperAccount.rollDay(PortfolioState.create(1_000.0), 5 * day + 10, 990.0)
        assertEquals(990.0, start.dayStartEquity, 0.0)
        assertEquals(start, PaperAccount.rollDay(start, 5 * day + 5_000, 950.0))
        assertEquals(950.0, PaperAccount.rollDay(start, 6 * day, 950.0).dayStartEquity, 0.0)
    }

    @Test
    fun `decision log is bounded`() {
        var state = PortfolioState.create(1_000.0)
        repeat(PaperAccount.MAX_DECISIONS + 20) { state = PaperAccount.log(state, Decision(it.toLong(), null, null, DecisionOutcome.INFO, "x")) }
        assertEquals(PaperAccount.MAX_DECISIONS, state.decisions.size)
        assertEquals((PaperAccount.MAX_DECISIONS + 19).toLong(), state.decisions.last().time)
    }
}

class PerformanceTest {
    private val costs = CostModel(takerFee = 0.0, slippage = 0.0, halfSpread = 0.0)
    private val rationale = TradeRationale("", "", "", "", "", "")
    private val day = 24 * 60 * 60_000L

    private fun trade(state: PortfolioState, exit: Double, at: Long): PortfolioState {
        val opened = PaperAccount.open(state, TestData.setup(), 1.0, 100.0, costs, 2.0, 50.0, rationale, null, at - 1)
        return PaperAccount.close(opened, opened.positions.last().id, exit, ExitReason.TAKE_PROFIT, "", costs, at)
    }

    @Test
    fun `groups realized results by period`() {
        val now = 40 * day + 1_000
        var state = PortfolioState.create(1_000.0)
        state = trade(state, 110.0, now - 20 * day) // +10, yalnızca 30 günde
        state = trade(state, 95.0, now - 3 * day) // -5, 7 ve 30 günde
        state = trade(state, 102.0, now - 500) // +2, bugün

        val summary = Performance.summarize(state, now)
        assertEquals(2.0, summary.today.netPnl, 1e-9)
        assertEquals(-3.0, summary.week.netPnl, 1e-9)
        assertEquals(7.0, summary.month.netPnl, 1e-9)
        assertEquals(0.7, summary.month.percentOfCapital, 1e-9)
        assertEquals(3, summary.allTime.trades)
        assertEquals(12.0 / 5.0, summary.profitFactor, 1e-9)
    }
}
