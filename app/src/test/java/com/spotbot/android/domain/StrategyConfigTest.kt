package com.spotbot.android.domain

import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.StrategyError
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class StrategyConfigTest {

    private val valid = StrategyConfig.DEFAULT

    @Test
    fun `default config is valid`() {
        assertEquals(emptyList<StrategyError>(), valid.validate())
    }

    @Test
    fun `order amount below minimum is rejected`() {
        val config = valid.copy(orderAmount = BigDecimal("4.99"))
        assertEquals(listOf(StrategyError.ORDER_AMOUNT_TOO_LOW), config.validate())
    }

    @Test
    fun `minimum order amount is accepted`() {
        val config = valid.copy(orderAmount = BigDecimal("5.00"))
        assertEquals(emptyList<StrategyError>(), config.validate())
    }

    @Test
    fun `take profit must be in range 0 exclusive to 100 inclusive`() {
        assertEquals(
            listOf(StrategyError.TAKE_PROFIT_OUT_OF_RANGE),
            valid.copy(takeProfitPercent = BigDecimal.ZERO).validate(),
        )
        assertEquals(
            listOf(StrategyError.TAKE_PROFIT_OUT_OF_RANGE),
            valid.copy(takeProfitPercent = BigDecimal("100.01")).validate(),
        )
        assertEquals(emptyList<StrategyError>(), valid.copy(takeProfitPercent = BigDecimal("100")).validate())
    }

    @Test
    fun `stop loss must be between 0 and 100 exclusive`() {
        assertEquals(
            listOf(StrategyError.STOP_LOSS_OUT_OF_RANGE),
            valid.copy(stopLossPercent = BigDecimal("-1")).validate(),
        )
        assertEquals(
            listOf(StrategyError.STOP_LOSS_OUT_OF_RANGE),
            valid.copy(stopLossPercent = BigDecimal("100")).validate(),
        )
    }

    @Test
    fun `all errors are reported together`() {
        val config = valid.copy(
            orderAmount = BigDecimal.ONE,
            takeProfitPercent = BigDecimal.ZERO,
            stopLossPercent = BigDecimal.ZERO,
        )
        assertEquals(StrategyError.entries.toList(), config.validate())
    }
}
