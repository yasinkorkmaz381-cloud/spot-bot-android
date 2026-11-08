package com.spotbot.android.domain

import com.spotbot.android.domain.model.Position
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.TradeReason
import com.spotbot.android.domain.model.TradingPair
import com.spotbot.android.domain.trading.Signal
import com.spotbot.android.domain.trading.TakeProfitStopLossStrategy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class TakeProfitStopLossStrategyTest {

    private val pair = TradingPair("BTC", "USDT")
    private val config = StrategyConfig(
        pair = pair,
        orderAmount = BigDecimal("50"),
        takeProfitPercent = BigDecimal("2"),
        stopLossPercent = BigDecimal("1"),
    )
    private val position = Position(
        pair = pair,
        quantity = BigDecimal("0.001"),
        entryPrice = BigDecimal("50000"),
        cost = BigDecimal("50"),
    )

    private fun evaluate(price: String, position: Position? = this.position, available: String = "100") =
        TakeProfitStopLossStrategy.evaluate(config, position, BigDecimal(price), BigDecimal(available))

    @Test
    fun `buys when flat and balance covers the order`() {
        assertEquals(Signal.Buy, evaluate("50000", position = null, available = "50"))
    }

    @Test
    fun `holds when flat and balance is insufficient`() {
        assertEquals(Signal.Hold, evaluate("50000", position = null, available = "49.99"))
    }

    @Test
    fun `sells for take profit exactly at target`() {
        assertEquals(Signal.Sell(TradeReason.TAKE_PROFIT), evaluate("51000"))
    }

    @Test
    fun `sells for stop loss exactly at threshold`() {
        assertEquals(Signal.Sell(TradeReason.STOP_LOSS), evaluate("49500"))
    }

    @Test
    fun `holds between thresholds`() {
        assertEquals(Signal.Hold, evaluate("50999.99"))
        assertEquals(Signal.Hold, evaluate("49500.01"))
    }

    @Test
    fun `ignores a position on another pair`() {
        val other = position.copy(pair = TradingPair("ETH", "USDT"))
        assertEquals(Signal.Hold, evaluate("10", position = other))
    }
}
