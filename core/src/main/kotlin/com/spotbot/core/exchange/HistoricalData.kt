package com.spotbot.core.exchange

import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe

/**
 * [start, end) aralığındaki mumları sayfalayarak indirir. MEXC sayfa başına en fazla 500 mum döndürür ve
 * yalnız `startTime` verilirse onu yok sayar; bu yüzden her istekte iki uç da gönderilir ve bir sonraki
 * sayfa, alınan son mumdan devam eder (sunucu daha az mum döndürse bile boşluk kalmaz).
 */
suspend fun MarketDataApi.klinesBetween(
    symbol: String,
    timeframe: Timeframe,
    start: Long,
    end: Long,
    pageSize: Int = 500,
    onProgress: (loaded: Int) -> Unit = {},
): List<Candle> {
    val result = ArrayList<Candle>()
    var cursor = start
    while (cursor < end) {
        val windowEnd = minOf(end, cursor + timeframe.millis * pageSize)
        val page = klines(symbol, timeframe, limit = pageSize, startTime = cursor, endTime = windowEnd - 1)
            .filter { it.openTime >= cursor && it.closeTime <= end }
        cursor = if (page.isEmpty()) windowEnd else page.last().openTime + timeframe.millis
        if (page.isNotEmpty()) {
            result.addAll(page)
            onProgress(result.size)
        }
    }
    return result.distinctBy { it.openTime }.sortedBy { it.openTime }
}
