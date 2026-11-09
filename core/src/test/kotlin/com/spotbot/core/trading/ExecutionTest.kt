package com.spotbot.core.trading

import com.spotbot.core.model.Candle
import com.spotbot.core.risk.CostModel
import com.spotbot.core.support.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTest {

    private val costs = CostModel(takerFee = 0.001, slippage = 0.0, halfSpread = 0.0, makerFee = 0.0)
    private val rationale = TradeRationale("", "", "", "", "", "")

    /** Giriş 100, stop 98 (R = 2), hedef 106; 10 adet; risk 20 USDT. */
    private fun opened(maker: Boolean = false) = PaperAccount.open(
        PortfolioState.create(10_000.0), TestData.setup(entry = 100.0, stop = 98.0, target = 106.0), 10.0, 100.0, costs,
        riskAmount = 20.0, qualityScore = 50.0, rationale = rationale, report = null, now = 0, maker = maker,
    )

    private fun bar(open: Double, high: Double, low: Double, close: Double, t: Long = 0) = Candle(t, open, high, low, close, 1.0, t + 900_000, 1.0)

    @Test
    fun `maker entry fills at the limit price without fee`() {
        val p = opened(maker = true).positions.single()
        assertEquals(100.0, p.entryPrice, 0.0)
        assertEquals(0.0, p.entryFee, 0.0)
    }

    @Test
    fun `stop is checked before targets inside the same bar`() {
        val p = opened().positions.single()
        val actions = ExitManager.onBar(p, bar(100.0, 107.0, 97.0, 101.0), ExecutionConfig(partialTakeProfitAtR = 1.0, breakEvenAtR = 1.0))
        assertEquals(listOf(ExitReason.STOP_LOSS), actions.filterIsInstance<ExitAction.Close>().map { it.reason })
        assertEquals(1, actions.size)
    }

    @Test
    fun `break even applies from the next bar and is reported as break-even exit`() {
        val cfg = ExecutionConfig(breakEvenAtR = 1.0)
        var state = opened()
        val p = state.positions.single()
        state = ExitManager.apply(state, p.id, ExitManager.onBar(p, bar(100.0, 102.5, 99.0, 101.0), cfg), costs, 1)
        val moved = state.positions.single()
        assertTrue(moved.breakEvenSet)
        assertEquals(p.entryPrice, moved.stopPrice, 0.0)
        assertEquals(98.0, moved.initialStopPrice, 0.0)

        state = ExitManager.apply(state, p.id, ExitManager.onBar(moved, bar(100.5, 100.6, 99.5, 99.8), cfg), costs, 2)
        assertEquals(ExitReason.BREAK_EVEN, state.closedTrades.single().reason)
    }

    @Test
    fun `partial take profit books half and the final trade includes it`() {
        val cfg = ExecutionConfig(partialTakeProfitAtR = 1.0, partialFraction = 0.5)
        var state = opened()
        val id = state.positions.single().id
        state = ExitManager.apply(state, id, ExitManager.onBar(state.positions.single(), bar(100.0, 102.5, 99.5, 102.0), cfg), costs, 1)
        val half = state.positions.single()
        assertEquals(5.0, half.quantity, 1e-12)
        assertTrue(half.partialTaken)
        // 5 adet 102'den: 510 - 0.51 ücret - (500 + 0.5 giriş ücreti payı)
        assertEquals(510.0 - 0.51 - 500.5, half.realizedPnl, 1e-9)

        state = ExitManager.apply(state, id, ExitManager.onBar(half, bar(103.0, 106.5, 102.5, 106.0), cfg), costs, 2)
        val trade = state.closedTrades.single()
        assertEquals(ExitReason.TAKE_PROFIT, trade.reason)
        val second = 530.0 - 0.53 - 500.5
        assertEquals(half.realizedPnl + second, trade.netPnl, 1e-9)
        assertEquals(10_000.0 + trade.netPnl, state.cash, 1e-9)
        assertEquals(1.0 + 0.51 + 0.53, trade.fees, 1e-9)
    }

    @Test
    fun `limit take profit needs the price to trade through the target`() {
        val p = opened().positions.single()
        val cfg = ExecutionConfig(limitTakeProfit = true)
        assertTrue(ExitManager.onBar(p, bar(104.0, 106.0, 103.5, 105.0), cfg).isEmpty())
        val close = ExitManager.onBar(p, bar(104.0, 106.1, 103.5, 105.0), cfg).single() as ExitAction.Close
        assertTrue(close.maker)
        assertEquals(106.0, close.price, 0.0)
    }

    @Test
    fun `time exit closes stale positions`() {
        val p = opened().positions.single()
        val action = ExitManager.onBar(p, bar(100.0, 101.0, 99.5, 100.5, t = 24 * 3_600_000L), ExecutionConfig(maxHoldingHours = 24)).single()
        assertEquals(ExitReason.TIME_EXIT, (action as ExitAction.Close).reason)
    }

    @Test
    fun `live price uses the same rules`() {
        val p = opened().positions.single()
        val cfg = ExecutionConfig(breakEvenAtR = 1.0)
        assertEquals(ExitReason.STOP_LOSS, (ExitManager.onPrice(p, 97.9, 0, cfg).single() as ExitAction.Close).reason)
        assertTrue(ExitManager.onPrice(p, 102.1, 0, cfg).single() is ExitAction.MoveStop)
        assertTrue(ExitManager.onPrice(p, 101.0, 0, cfg).isEmpty())
    }

    @Test
    fun `cooldown triggers after a losing streak and expires`() {
        var state = PortfolioState.create(10_000.0)
        repeat(3) { i ->
            state = PaperAccount.open(state, TestData.setup(entry = 100.0, stop = 98.0, target = 106.0), 10.0, 100.0, costs, 20.0, 50.0, rationale, null, i * 10L)
            state = PaperAccount.close(state, state.positions.single().id, 98.0, ExitReason.STOP_LOSS, "", costs, i * 10L + 5)
        }
        val cfg = ExecutionConfig(cooldownTrades = 3, cooldownLossR = 3.0, cooldownDays = 7)
        assertTrue(Cooldown.reason(state.closedTrades, "ETHUSDT", 100, cfg)!!.startsWith("Son 3 işlem"))
        assertNull(Cooldown.reason(state.closedTrades, "ETHUSDT", 8 * 24 * 3_600_000L, cfg))
        assertNull(Cooldown.reason(state.closedTrades, "BTCUSDT", 100, cfg))
        assertNull(Cooldown.reason(state.closedTrades, "ETHUSDT", 100, ExecutionConfig()))
    }

    @Test
    fun `bar opening above the target is a take profit even if it later falls through the stop`() {
        val p = opened().positions.single()
        val actions = ExitManager.onBar(p, bar(open = 107.0, high = 108.0, low = 97.0, close = 97.5), ExecutionConfig())
        val close = actions.single() as ExitAction.Close
        assertEquals(ExitReason.TAKE_PROFIT, close.reason)
        assertEquals(107.0, close.price, 1e-9)
    }
}
