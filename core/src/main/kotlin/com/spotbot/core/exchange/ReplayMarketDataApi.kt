package com.spotbot.core.exchange

import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe

/**
 * Geçmiş veriyi canlı veri gibi sunan sahte borsa (yalnız simülasyon; ağ ve emir yok). [clock] ilerletildikçe yalnız o
 * ana kadar kapanmış mumlar görünür. Canlı karar motorunu geçmişte doğrulanmış bir sinyalle uçtan uca denemek içindir.
 */
class ReplayMarketDataApi(
    private val data: Map<String, HistoricalDataset>,
    private val info: Map<String, SymbolInfo>,
    private val clock: () -> Long,
    /** Alış-satış farkı (oran); backtest'in varsayılan yarım spread'inin iki katı. */
    private val spread: Double = 0.0005,
) : MarketDataApi {
    private fun candles(symbol: String, tf: Timeframe): List<Candle> {
        val d = data[symbol] ?: return emptyList()
        val all = when (tf) { Timeframe.M15 -> d.m15; Timeframe.H1 -> d.h1; Timeframe.H4 -> d.h4; else -> emptyList() }
        val now = clock()
        return all.filter { it.closeTime <= now }
    }

    private fun price(symbol: String): Double? = candles(symbol, Timeframe.M15).lastOrNull()?.close

    override suspend fun serverTime(): Long = clock()
    override suspend fun exchangeInfo(): List<SymbolInfo> = info.values.toList()
    override suspend fun klines(symbol: String, timeframe: Timeframe, limit: Int, startTime: Long?, endTime: Long?): List<Candle> =
        candles(symbol, timeframe).filter { (startTime == null || endTime == null || it.openTime >= startTime) && (endTime == null || it.openTime <= endTime) }.takeLast(limit)

    override suspend fun tickers24h(): List<Ticker24h> = data.keys.mapNotNull { s ->
        val h1 = candles(s, Timeframe.H1).takeLast(25)
        if (h1.size < 25) return@mapNotNull null
        val last = h1.last().close
        Ticker24h(s, last, (last / h1.first().close - 1) * 100, 0.0, 0.0, 0.0, h1.drop(1).sumOf { it.quoteVolume }, last, last)
    }

    override suspend fun bookTickers(): List<BookTicker> = data.keys.mapNotNull { s -> runCatching { bookTicker(s) }.getOrNull() }
    override suspend fun bookTicker(symbol: String): BookTicker {
        val p = price(symbol) ?: error("$symbol için veri yok")
        return BookTicker(symbol, p * (1 - spread / 2), 1e9, p * (1 + spread / 2), 1e9, clock())
    }

    override suspend fun orderBook(symbol: String, limit: Int): OrderBook {
        val p = price(symbol) ?: error("$symbol için veri yok")
        return OrderBook(symbol, listOf(PriceLevel(p * (1 - spread / 2), 1e9)), listOf(PriceLevel(p * (1 + spread / 2), 1e9)))
    }
}
