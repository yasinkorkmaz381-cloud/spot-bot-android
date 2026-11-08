package com.spotbot.android.ui.strategy

import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.StrategyError
import com.spotbot.android.domain.model.TradingPair
import com.spotbot.android.ui.common.formatEditableDecimal
import com.spotbot.android.ui.common.parseDecimal
import java.util.Locale

/** Strateji formunun ham (kullanıcının yazdığı) değerleri ve alan hataları. */
data class StrategyFormState(
    val pair: TradingPair,
    val orderAmount: String,
    val takeProfitPercent: String,
    val stopLossPercent: String,
    val errors: Set<StrategyError> = emptySet(),
) {
    fun validate(): StrategyFormResult {
        val amount = parseDecimal(orderAmount)
        val takeProfit = parseDecimal(takeProfitPercent)
        val stopLoss = parseDecimal(stopLossPercent)
        if (amount == null || takeProfit == null || stopLoss == null) {
            return StrategyFormResult.Invalid(
                buildSet {
                    if (amount == null) add(StrategyError.ORDER_AMOUNT_TOO_LOW)
                    if (takeProfit == null) add(StrategyError.TAKE_PROFIT_OUT_OF_RANGE)
                    if (stopLoss == null) add(StrategyError.STOP_LOSS_OUT_OF_RANGE)
                },
            )
        }
        val config = StrategyConfig(pair, amount, takeProfit, stopLoss)
        val errors = config.validate()
        return if (errors.isEmpty()) StrategyFormResult.Valid(config) else StrategyFormResult.Invalid(errors.toSet())
    }

    companion object {
        fun from(config: StrategyConfig, locale: Locale = Locale.getDefault()) = StrategyFormState(
            pair = config.pair,
            orderAmount = formatEditableDecimal(config.orderAmount, locale),
            takeProfitPercent = formatEditableDecimal(config.takeProfitPercent, locale),
            stopLossPercent = formatEditableDecimal(config.stopLossPercent, locale),
        )
    }
}

sealed interface StrategyFormResult {
    data class Valid(val config: StrategyConfig) : StrategyFormResult
    data class Invalid(val errors: Set<StrategyError>) : StrategyFormResult
}
