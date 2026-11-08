package com.spotbot.android.domain.trading

import com.spotbot.android.domain.model.Position
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.TradeReason
import java.math.BigDecimal

sealed interface Signal {
    data object Hold : Signal
    data object Buy : Signal
    data class Sell(val reason: TradeReason) : Signal
}

/** Gelen fiyata göre al / sat / bekle kararını veren saf strateji mantığı. */
object TakeProfitStopLossStrategy {

    fun evaluate(
        config: StrategyConfig,
        position: Position?,
        price: BigDecimal,
        availableQuote: BigDecimal,
    ): Signal {
        if (position == null) {
            return if (availableQuote >= config.orderAmount) Signal.Buy else Signal.Hold
        }
        if (position.pair != config.pair) return Signal.Hold

        val takeProfitPrice = position.entryPrice * (BigDecimal.ONE + config.takeProfitPercent.movePointLeft(2))
        val stopLossPrice = position.entryPrice * (BigDecimal.ONE - config.stopLossPercent.movePointLeft(2))
        return when {
            price >= takeProfitPrice -> Signal.Sell(TradeReason.TAKE_PROFIT)
            price <= stopLossPrice -> Signal.Sell(TradeReason.STOP_LOSS)
            else -> Signal.Hold
        }
    }
}
