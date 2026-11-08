package com.spotbot.android.domain.model

import java.math.BigDecimal
import java.math.MathContext

/** Bir paritenin anlık fiyat bilgisi. */
data class MarketTicker(
    val pair: TradingPair,
    val lastPrice: BigDecimal,
    /** Değişimin hesaplandığı referans (açılış) fiyatı. */
    val openPrice: BigDecimal,
) {
    /** Açılış fiyatına göre yüzde değişim. */
    val changePercent: Double
        get() = if (openPrice.signum() == 0) {
            0.0
        } else {
            (lastPrice - openPrice).divide(openPrice, MathContext.DECIMAL64).toDouble() * 100
        }
}
