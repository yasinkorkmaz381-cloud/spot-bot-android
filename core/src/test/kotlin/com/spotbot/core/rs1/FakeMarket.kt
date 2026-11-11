package com.spotbot.core.rs1

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe
import kotlin.math.exp

/** Testler için sahte MEXC: [clock] ilerledikçe yalnız o ana kadar oluşmuş günlük ve saatlik mumlar görünür. */
class FakeMarket(
    var info: List<SymbolInfo>,
    private val daily: Map<String, List<Candle>>,
    var clock: Long = 0,
    var spreadPercent: Double = 0.02,
    var depthUsdt: Double = 1e6,
) : MarketDataApi {
    var calls = 0

    /** Günlük mumdan 24 saatlik mum: açılıştan 6. saatte düşüğe, sonra kapanışa doğrusal yol (yüksek son saatte). */
    private fun hours(d: Candle): List<Candle> = (0 until 24).map { h ->
        fun at(x: Double) = when {
            x <= 6 -> d.open + (d.low - d.open) * x / 6
            else -> d.low + (d.close - d.low) * (x - 6) / 18
        }
        val o = at(h.toDouble())
        val c = at(h + 1.0)
        val t = d.openTime + h * HOUR_MS
        val hi = if (h == 23) maxOf(d.high, o, c) else maxOf(o, c)
        val lo = if (h == 5) d.low else minOf(o, c)
        Candle(t, o, hi, lo, c, d.volume / 24, t + HOUR_MS, d.quoteVolume / 24)
    }

    override suspend fun serverTime() = clock
    override suspend fun exchangeInfo() = info
    override suspend fun klines(symbol: String, timeframe: Timeframe, limit: Int, startTime: Long?, endTime: Long?): List<Candle> {
        calls++
        val all = daily[symbol] ?: throw IllegalStateException("$symbol yok")
        val list = when (timeframe) {
            Timeframe.D1 -> all.filter { it.openTime <= clock }
            Timeframe.H1 -> all.filter { it.openTime <= clock && it.openTime + DAY_MS > clock - DAY_MS }.flatMap { hours(it) }
                .filter { it.openTime + HOUR_MS <= clock }
            else -> emptyList()
        }
        return list.filter { (startTime == null || it.openTime >= startTime) && (endTime == null || it.openTime <= endTime) }.takeLast(limit)
    }

    private fun last(symbol: String) = daily.getValue(symbol).last { it.openTime + DAY_MS <= clock }
    override suspend fun tickers24h() = daily.keys.filter { s -> daily.getValue(s).any { it.openTime + DAY_MS <= clock } }.map { s ->
        val l = last(s); Ticker24h(s, l.close, 1.0, l.high, l.low, l.volume, l.quoteVolume, l.close, l.close)
    }
    override suspend fun bookTickers() = tickers24h().map { BookTicker(it.symbol, it.lastPrice * (1 - spreadPercent / 200), 1.0, it.lastPrice * (1 + spreadPercent / 200), 1.0, clock) }
    override suspend fun bookTicker(symbol: String) = bookTickers().first { it.symbol == symbol }
    override suspend fun orderBook(symbol: String, limit: Int): OrderBook {
        val p = last(symbol).close
        return OrderBook(symbol, listOf(PriceLevel(p * 0.999, depthUsdt / p)), listOf(PriceLevel(p * 1.001, depthUsdt / p)))
    }

    companion object {
        /** 2024-01-01, pazartesi. */
        const val T0 = 1_704_067_200_000L

        fun series(days: Int, volume: Double = 20e6, drift: (Int) -> Double): List<Candle> {
            var p = 100.0
            return (0 until days).map { i ->
                val o = p
                val c = p * exp(drift(i))
                p = c
                val t = T0 + i * DAY_MS
                Candle(t, o, maxOf(o, c) * 1.01, minOf(o, c) * 0.99, c, volume / c, t + DAY_MS, volume)
            }
        }

        fun info(symbol: String, sectors: List<String> = emptyList(), st: Boolean = false) = SymbolInfo(
            symbol = symbol, baseAsset = symbol.removeSuffix("USDT"), quoteAsset = "USDT", pricePrecision = 4, quantityPrecision = 4,
            minQuantity = 0.0001, minNotional = 1.0, makerFee = 0.0, takerFee = 0.0005, isOnline = true, isSpotTradingAllowed = true,
            orderTypes = listOf("LIMIT", "MARKET"), riskFlagged = st, tradeSideType = 1, sectors = sectors,
        )
    }
}
