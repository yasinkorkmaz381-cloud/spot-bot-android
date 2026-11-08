package com.spotbot.android.data.market

import com.spotbot.android.domain.model.MarketTicker
import kotlinx.coroutines.flow.StateFlow

/** Piyasa verisi kaynağı. Şimdilik simülasyon; ileride Binance REST/WebSocket ile değiştirilecek. */
interface MarketRepository {
    /** Takip edilen tüm paritelerin anlık fiyatları. */
    val tickers: StateFlow<List<MarketTicker>>
}
