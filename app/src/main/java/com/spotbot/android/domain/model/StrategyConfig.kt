package com.spotbot.android.domain.model

import java.math.BigDecimal

/**
 * Kâr al / zarar durdur stratejisinin ayarları.
 *
 * Açık pozisyon yoksa [orderAmount] tutarında (kote para birimi, örn. USDT) alım yapılır.
 * Fiyat giriş fiyatının [takeProfitPercent] kadar üzerine çıkınca ya da
 * [stopLossPercent] kadar altına düşünce pozisyon kapatılır.
 */
data class StrategyConfig(
    val pair: TradingPair,
    val orderAmount: BigDecimal,
    val takeProfitPercent: BigDecimal,
    val stopLossPercent: BigDecimal,
) {
    fun validate(): List<StrategyError> = buildList {
        if (orderAmount < MIN_ORDER_AMOUNT) add(StrategyError.ORDER_AMOUNT_TOO_LOW)
        if (takeProfitPercent.signum() <= 0 || takeProfitPercent > HUNDRED) {
            add(StrategyError.TAKE_PROFIT_OUT_OF_RANGE)
        }
        if (stopLossPercent.signum() <= 0 || stopLossPercent >= HUNDRED) {
            add(StrategyError.STOP_LOSS_OUT_OF_RANGE)
        }
    }

    companion object {
        /** Binance spot piyasasındaki asgari emir tutarına yakın bir alt sınır. */
        val MIN_ORDER_AMOUNT: BigDecimal = BigDecimal("5")
        private val HUNDRED = BigDecimal(100)

        val DEFAULT = StrategyConfig(
            pair = TradingPair("BTC", "USDT"),
            orderAmount = BigDecimal("50"),
            takeProfitPercent = BigDecimal("1.5"),
            stopLossPercent = BigDecimal("1"),
        )
    }
}

enum class StrategyError {
    ORDER_AMOUNT_TOO_LOW,
    TAKE_PROFIT_OUT_OF_RANGE,
    STOP_LOSS_OUT_OF_RANGE,
}
