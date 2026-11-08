package com.spotbot.android.ui

import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.StrategyError
import com.spotbot.android.ui.strategy.StrategyFormResult
import com.spotbot.android.ui.strategy.StrategyFormState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class StrategyFormStateTest {

    private val tr = Locale.forLanguageTag("tr-TR")
    private val form = StrategyFormState.from(StrategyConfig.DEFAULT, tr)

    @Test
    fun `form is prefilled using the locale decimal separator`() {
        assertEquals("50", form.orderAmount)
        assertEquals("1,5", form.takeProfitPercent)
        assertEquals("1", form.stopLossPercent)
    }

    @Test
    fun `valid form produces a config`() {
        val result = form.copy(orderAmount = "25,5", takeProfitPercent = "2").validate()

        val config = (result as StrategyFormResult.Valid).config
        assertEquals(0, BigDecimal("25.5").compareTo(config.orderAmount))
        assertEquals(0, BigDecimal("2").compareTo(config.takeProfitPercent))
        assertEquals(StrategyConfig.DEFAULT.pair, config.pair)
    }

    @Test
    fun `unparseable fields are reported as field errors`() {
        val result = form.copy(orderAmount = "", stopLossPercent = "x").validate()

        assertEquals(
            StrategyFormResult.Invalid(setOf(StrategyError.ORDER_AMOUNT_TOO_LOW, StrategyError.STOP_LOSS_OUT_OF_RANGE)),
            result,
        )
    }

    @Test
    fun `domain rules are applied after parsing`() {
        val result = form.copy(takeProfitPercent = "0").validate()

        assertEquals(StrategyFormResult.Invalid(setOf(StrategyError.TAKE_PROFIT_OUT_OF_RANGE)), result)
    }
}
