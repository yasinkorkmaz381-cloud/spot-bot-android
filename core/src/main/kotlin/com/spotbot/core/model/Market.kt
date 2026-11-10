package com.spotbot.core.model

import kotlinx.serialization.Serializable

/** Botun kullandığı zaman dilimleri. [mexcInterval] REST, [mexcStream] WebSocket adıdır. */
enum class Timeframe(val label: String, val mexcInterval: String, val mexcStream: String, val millis: Long) {
    M5("5M", "5m", "Min5", 5 * 60_000L),
    M15("15M", "15m", "Min15", 15 * 60_000L),
    H1("1H", "60m", "Min60", 60 * 60_000L),
    H4("4H", "4h", "Hour4", 4 * 60 * 60_000L),
    D1("1D", "1d", "Day1", 24 * 60 * 60_000L),
    ;

    /** [time] anına kadar kapanmış son mumun kapanış zamanı. */
    fun lastCloseAtOrBefore(time: Long): Long = time - Math.floorMod(time, millis)
}

/** OHLCV mumu. Zamanlar epoch milisaniyedir; [closeTime] = [openTime] + süre. */
@Serializable
data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val quoteVolume: Double,
) {
    fun isClosedAt(now: Long): Boolean = closeTime <= now
    val isBullish: Boolean get() = close > open
    val isBearish: Boolean get() = close < open
    val range: Double get() = high - low
    val body: Double get() = kotlin.math.abs(close - open)
}

/** Bir paritenin borsa kuralları (MEXC exchangeInfo). */
@Serializable
data class SymbolInfo(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    /** Fiyat ondalık sayısı (MEXC: quotePrecision). */
    val pricePrecision: Int,
    /** Miktar ondalık sayısı (MEXC: baseAssetPrecision). */
    val quantityPrecision: Int,
    /** Asgari emir miktarı (MEXC: baseSizePrecision). */
    val minQuantity: Double,
    /** Asgari emir tutarı, USDT (MEXC: quoteAmountPrecision). */
    val minNotional: Double,
    val makerFee: Double,
    val takerFee: Double,
    val isOnline: Boolean,
    val isSpotTradingAllowed: Boolean,
    val orderTypes: List<String>,
    /** MEXC'nin risk uyarısı (ST) etiketi. */
    val riskFlagged: Boolean,
    /** 1: alım-satım serbest, 2: yalnız alım, 3: yalnız satım, 4: kapalı. */
    val tradeSideType: Int,
    /** Emir başına en büyük tutar, USDT (MEXC: maxQuoteAmount; 0 = bilinmiyor). */
    val maxQuoteAmount: Double = 0.0,
    /** Piyasa emri asgari tutarı, USDT (MEXC: quoteAmountPrecisionMarket). */
    val minMarketNotional: Double = 0.0,
    /** Piyasa emri en büyük tutarı, USDT (MEXC: maxQuoteAmountMarket; 0 = bilinmiyor). */
    val maxMarketQuoteAmount: Double = 0.0,
    /** Fiyat bandı (MEXC PERCENT_PRICE_BY_SIDE): alış emri fiyatı ≤ piyasa × (1 + bu oran). */
    val buyPriceBandUp: Double = 0.0,
    /** Satış emri fiyatı ≥ piyasa × (1 − bu oran). */
    val sellPriceBandDown: Double = 0.0,
    /** MEXC sektör/ürün etiketleri (conceptPlates): "MEME", "AI", "DeFi", "Tokenized Stocks", "Stablecoins"… */
    val sectors: List<String> = emptyList(),
) {
    val canTradeBothSides: Boolean get() = tradeSideType == 1
}

@Serializable
data class Ticker24h(
    val symbol: String,
    val lastPrice: Double,
    /** Yüzde olarak 24 saatlik değişim (örn. -2.2 = %-2,2). */
    val changePercent: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val volume: Double,
    val quoteVolume: Double,
    val bidPrice: Double,
    val askPrice: Double,
)

@Serializable
data class BookTicker(
    val symbol: String,
    val bid: Double,
    val bidQty: Double,
    val ask: Double,
    val askQty: Double,
    val time: Long,
) {
    val mid: Double get() = (bid + ask) / 2
    /** Alış-satış farkı, yüzde. */
    val spreadPercent: Double get() = if (mid > 0) (ask - bid) / mid * 100 else Double.NaN
}

@Serializable
data class OrderBook(val symbol: String, val bids: List<PriceLevel>, val asks: List<PriceLevel>) {
    /** Orta fiyatın ±[percent] aralığındaki toplam USDT derinliği (alış, satış). */
    fun depthWithin(percent: Double): Pair<Double, Double> {
        val bestBid = bids.firstOrNull()?.price ?: return 0.0 to 0.0
        val bestAsk = asks.firstOrNull()?.price ?: return 0.0 to 0.0
        val mid = (bestBid + bestAsk) / 2
        val bidDepth = bids.filter { it.price >= mid * (1 - percent / 100) }.sumOf { it.price * it.quantity }
        val askDepth = asks.filter { it.price <= mid * (1 + percent / 100) }.sumOf { it.price * it.quantity }
        return bidDepth to askDepth
    }
}

@Serializable
data class PriceLevel(val price: Double, val quantity: Double)
