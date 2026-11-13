package com.spotbot.core.kr

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.kr.KrConfig.Companion.H4
import com.spotbot.core.kr.KrConfig.Companion.M15
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe
import kotlin.math.exp

/**
 * Sentetik 15M dizileri. Isınma: düzenli yükseliş (fraktal tepe yok). Ardından [W]'den itibaren tek bir kurulum:
 * A saati tepe P → 3 saat düşük yatay (sağ taraf) → kırılım mumu (yüksek hacim) → retest mumu → yükseliş.
 */
object Scenario {
    /** 2026-01-01 00:00 UTC. */
    const val T0 = 1_767_225_600_000L
    const val W = 1200

    fun bar(i: Int, o: Double, h: Double, l: Double, c: Double, vol: Double = 1000.0) =
        Candle(T0 + i * M15, o, h, l, c, vol, T0 + (i + 1) * M15, 50_000.0 * vol / 1000)

    fun warmup(n: Int = W, start: Double = 100.0, step: Double = 0.0004): MutableList<Candle> {
        val out = ArrayList<Candle>()
        var p = start
        for (i in 0 until n) {
            val c = p * exp(step)
            out += bar(i, p, maxOf(p, c) * 1.002, minOf(p, c) * 0.998, c)
            p = c
        }
        return out
    }

    /** Kurulumun anahtar fiyatları. */
    class Setup(val bars: MutableList<Candle>, val level: Double, val breakoutIndex: Int, val retestIndex: Int)

    /**
     * @param breakoutClose kırılım mumu kapanışı (P katı); [retest] false ise retest mumu yerine seviyenin üstünde
     * uzak duran mumlar gelir; [after] retestten sonraki mum sayısı ve adım.
     */
    fun setup(
        breakoutVolume: Double = 3000.0,
        breakoutClose: Double = 1.001,
        retest: Boolean = true,
        retestClose: Double = 1.0015,
        after: Int = 30,
        afterStep: Double = 0.003,
        start: Double = 100.0,
    ): Setup {
        val bars = warmup(start = start)
        val p0 = bars.last().close
        val peak = p0 * 1.01
        var i = W
        // A saati: tepe W+2'de.
        val a = listOf(p0 * 1.002, p0 * 1.004, p0 * 1.006, p0 * 1.004)
        var prev = p0
        for ((k, c) in a.withIndex()) {
            bars += bar(i, prev, if (k == 2) peak else maxOf(prev, c) * 1.0005, minOf(prev, c) * 0.9995, c)
            prev = c; i++
        }
        // B, C, D saatleri: tepe altında yatay.
        repeat(12) { k ->
            val c = p0 * (1.003 + (k % 2) * 0.001)
            bars += bar(i, prev, maxOf(prev, c) * 1.001, minOf(prev, c) * 0.999, c)
            prev = c; i++
        }
        // E: kırılım (W+16).
        val breakout = i
        bars += bar(i, prev, peak * (breakoutClose + 0.001), prev * 0.999, peak * breakoutClose, breakoutVolume)
        prev = peak * breakoutClose; i++
        val retestIndex = i
        if (retest) {
            bars += bar(i, prev, peak * (retestClose + 0.0003), peak * 1.0004, peak * retestClose)
            prev = peak * retestClose; i++
        }
        repeat(after) {
            val c = prev * (1 + afterStep)
            bars += bar(i, prev, c * 1.0005, prev * 0.9995, c)
            prev = c; i++
        }
        return Setup(bars, peak, breakout, retestIndex)
    }

    fun info(symbol: String, fee: Double = 0.0005, st: Boolean = false, contract: String = "") = SymbolInfo(
        symbol = symbol, baseAsset = symbol.removeSuffix("USDT"), quoteAsset = "USDT", pricePrecision = 6, quantityPrecision = 6,
        minQuantity = 0.0001, minNotional = 1.0, makerFee = 0.0, takerFee = fee, isOnline = true, isSpotTradingAllowed = true,
        orderTypes = listOf("LIMIT", "MARKET"), riskFlagged = st, tradeSideType = 1, contractAddress = contract,
    )

    fun asset(id: Int, symbol: String, rank: Int, price: Double, tags: List<String> = emptyList(), name: String = symbol) =
        CmcAsset(id, name, symbol, symbol.lowercase(), rank, tags, priceUsd = price)
}

/**
 * Sahte MEXC: [clock]'a kadar kapanmış mumlar görünür. 1 dakikalık mumlar 15M mumdan türetilir (açılış → 5. dk düşük →
 * 10. dk yüksek → kapanış). Anlık fiyat: son kapanmış 1 dakikalık mumun kapanışı ±%0,01.
 */
class FakeKrMarket(val m15: Map<String, List<Candle>>, var infos: List<SymbolInfo>, var clock: Long) : MarketDataApi {
    var calls = 0

    fun minutes(c: Candle): List<Candle> = (0 until 15).map { k ->
        fun at(x: Double) = when {
            x <= 5 -> c.open + (c.low - c.open) * x / 5
            x <= 10 -> c.low + (c.high - c.low) * (x - 5) / 5
            else -> c.high + (c.close - c.high) * (x - 10) / 5
        }
        val o = at(k.toDouble()); val cl = at(k + 1.0)
        val t = c.openTime + k * 60_000L
        Candle(t, o, maxOf(o, cl), minOf(o, cl), cl, c.volume / 15, t + 60_000L, c.quoteVolume / 15)
    }

    private fun closed(sym: String) = m15[sym].orEmpty().filter { it.closeTime <= clock }

    override suspend fun serverTime() = clock
    override suspend fun exchangeInfo() = infos
    override suspend fun klines(symbol: String, timeframe: Timeframe, limit: Int, startTime: Long?, endTime: Long?): List<Candle> {
        calls++
        val list = when (timeframe) {
            Timeframe.M15 -> closed(symbol)
            Timeframe.H4 -> Bars.aggregate(closed(symbol), H4)
            Timeframe.D1 -> Bars.aggregate(closed(symbol), KrConfig.DAY)
            Timeframe.M1 -> m15[symbol].orEmpty().filter { it.openTime <= clock }.flatMap { minutes(it) }.filter { it.closeTime <= clock }
            else -> emptyList()
        }
        return list.filter { (startTime == null || it.openTime >= startTime) && (endTime == null || it.openTime <= endTime) }.takeLast(limit)
    }

    fun price(sym: String): Double = m15[sym].orEmpty().filter { it.openTime <= clock }.flatMap { minutes(it) }.lastOrNull { it.closeTime <= clock }?.close
        ?: m15.getValue(sym).first().open

    override suspend fun tickers24h() = m15.keys.map { s -> val p = price(s); Ticker24h(s, p, 0.0, p, p, 1.0, 1e7, p, p) }
    override suspend fun bookTickers() = m15.keys.map { s -> val p = price(s); BookTicker(s, p * 0.9999, 10.0, p * 1.0001, 10.0, clock) }
    override suspend fun bookTicker(symbol: String) = bookTickers().first { it.symbol == symbol }
    override suspend fun orderBook(symbol: String, limit: Int): OrderBook {
        val p = price(symbol)
        return OrderBook(symbol, listOf(PriceLevel(p * 0.9999, 1e7 / p)), listOf(PriceLevel(p * 1.0001, 1e7 / p)))
    }
}
