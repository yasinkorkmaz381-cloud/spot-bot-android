package com.spotbot.android.domain.model

/** Spot işlem çifti, örn. BTC/USDT. */
data class TradingPair(val base: String, val quote: String) {
    /** Borsa sembolü, örn. "BTCUSDT". */
    val symbol: String get() = base + quote

    /** Ekranda gösterilen biçim, örn. "BTC/USDT". */
    val displayName: String get() = "$base/$quote"
}
