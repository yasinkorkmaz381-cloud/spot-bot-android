package com.spotbot.android.domain

import com.spotbot.android.assertDecimalEquals
import com.spotbot.android.domain.model.BotState
import com.spotbot.android.domain.model.OrderSide
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.TradeReason
import com.spotbot.android.domain.model.TradingPair
import com.spotbot.android.domain.trading.PaperTrader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class PaperTraderTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val config = StrategyConfig(
        pair = TradingPair("BTC", "USDT"),
        orderAmount = BigDecimal("50"),
        takeProfitPercent = BigDecimal("2"),
        stopLossPercent = BigDecimal("1"),
    )
    private val running = BotState.initial(BigDecimal("1000"), config).copy(isRunning = true)

    /** 50000'den alım: ücret 0,05; miktar 49,95 / 50000 = 0,000999; efektif giriş 50 / 0,000999. */
    private val afterBuy = PaperTrader.onPrice(running, BigDecimal("50000"), now)

    @Test
    fun `does nothing while the bot is stopped`() {
        val stopped = running.copy(isRunning = false)
        assertSame(stopped, PaperTrader.onPrice(stopped, BigDecimal("50000"), now))
    }

    @Test
    fun `buy opens a position and charges the fee`() {
        val position = afterBuy.account.position!!
        assertDecimalEquals("950", afterBuy.account.quoteBalance)
        assertDecimalEquals("0.000999", position.quantity)
        assertDecimalEquals("50050.05005005", position.entryPrice)
        assertDecimalEquals("50", position.cost)

        val trade = afterBuy.trades.single()
        assertEquals(1L, trade.id)
        assertEquals(OrderSide.BUY, trade.side)
        assertEquals(TradeReason.ENTRY, trade.reason)
        assertDecimalEquals("0.05", trade.fee)
        assertNull(trade.realizedPnl)
    }

    @Test
    fun `take profit closes the position and realizes the gain`() {
        val state = PaperTrader.onPrice(afterBuy, BigDecimal("52000"), now)

        // brüt 51,948; ücret 0,051948; net 51,896052
        assertNull(state.account.position)
        assertDecimalEquals("1001.896052", state.account.quoteBalance)
        val trade = state.trades.first()
        assertEquals(2L, trade.id)
        assertEquals(OrderSide.SELL, trade.side)
        assertEquals(TradeReason.TAKE_PROFIT, trade.reason)
        assertDecimalEquals("1.896052", trade.realizedPnl)
    }

    @Test
    fun `stop loss closes the position and realizes the loss`() {
        val state = PaperTrader.onPrice(afterBuy, BigDecimal("49000"), now)

        // brüt 48,951; ücret 0,048951; net 48,902049
        assertNull(state.account.position)
        assertDecimalEquals("998.902049", state.account.quoteBalance)
        assertEquals(TradeReason.STOP_LOSS, state.trades.first().reason)
        assertDecimalEquals("-1.097951", state.trades.first().realizedPnl)
    }

    @Test
    fun `holds the position between thresholds`() {
        assertSame(afterBuy, PaperTrader.onPrice(afterBuy, BigDecimal("50500"), now))
    }

    @Test
    fun `does not buy when balance is insufficient`() {
        val poor = BotState.initial(BigDecimal("10"), config).copy(isRunning = true)
        assertSame(poor, PaperTrader.onPrice(poor, BigDecimal("50000"), now))
    }

    @Test
    fun `trade history is capped`() {
        var state = running
        repeat(PaperTrader.MAX_TRADE_HISTORY + 10) { i ->
            // Sırayla alım ve kâr al satışı tetikleyen fiyatlar.
            val price = if (i % 2 == 0) BigDecimal("50000") else BigDecimal("60000")
            state = PaperTrader.onPrice(state, price, now)
        }
        assertEquals(PaperTrader.MAX_TRADE_HISTORY, state.trades.size)
        assertEquals((PaperTrader.MAX_TRADE_HISTORY + 10).toLong(), state.trades.first().id)
    }
}
