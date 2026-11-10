package com.spotbot.core.scanner

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe
import com.spotbot.core.research.DAY_MS
import com.spotbot.core.research.H4Dataset
import com.spotbot.core.research.H4_MS
import com.spotbot.core.research.RelativeStrength
import com.spotbot.core.research.Universe
import com.spotbot.core.research.V3Config
import com.spotbot.core.support.TestData
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

class WideScannerTest {
    private val t0 = 1_700_006_400_000L / DAY_MS * DAY_MS
    private val days = 400
    private val now = t0 + days * DAY_MS + 3_600_000

    /** Günlük getirisi [drift] olan, her gün 6 adet 4H mumundan oluşan seri; [fromDay] öncesi veri yok (listeleme). */
    private fun h4(seed: Int, drift: Double, quoteVolume: Double, fromDay: Int = 0): List<Candle> {
        val rnd = Random(seed)
        var p = 10.0
        return (fromDay * 6 until days * 6).map { i ->
            val o = p
            val c = p * exp(drift / 6 + rnd.nextDouble(-0.006, 0.006))
            p = c
            val t = t0 + i * H4_MS
            Candle(t, o, maxOf(o, c) * 1.003, minOf(o, c) * 0.997, c, quoteVolume / 6 / c, t + H4_MS, quoteVolume / 6)
        }
    }

    private fun daily(h4: List<Candle>) = h4.groupBy { it.openTime / DAY_MS }.filterValues { it.size == 6 }.toSortedMap().map { (d, g) ->
        Candle(d * DAY_MS, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close, g.sumOf { it.volume }, (d + 1) * DAY_MS, g.sumOf { it.quoteVolume })
    }

    private val series = mapOf(
        "BTCUSDT" to h4(1, 0.001, 50e6),
        "AAAUSDT" to h4(2, 0.006, 5e6),
        "DDDUSDT" to h4(5, 0.004, 3e6),
        "CCCUSDT" to h4(3, -0.004, 5e6),
        "THINUSDT" to h4(4, 0.006, 600_000.0),
        "NEWUSDT" to h4(6, 0.008, 9e6, fromDay = 300),
        "STUSDT" to h4(7, 0.006, 5e6),
        "USDCUSDT" to h4(8, 0.0, 90e6),
        "BTC3LUSDT" to h4(9, 0.003, 5e6),
        "TSLAXUSDT" to h4(10, 0.003, 5e6),
    )

    private class FakeApi(var info: List<SymbolInfo>, val daily: Map<String, List<Candle>>, val clock: () -> Long) : MarketDataApi {
        var klineCalls = 0
        override suspend fun serverTime() = clock()
        override suspend fun exchangeInfo() = info
        override suspend fun klines(symbol: String, timeframe: Timeframe, limit: Int, startTime: Long?, endTime: Long?): List<Candle> {
            klineCalls++
            require(timeframe == Timeframe.D1)
            return daily[symbol]?.filter { it.openTime <= clock() }?.takeLast(limit) ?: throw IllegalStateException("yok")
        }
        override suspend fun tickers24h() = daily.map { (s, c) -> val l = c.last(); Ticker24h(s, l.close, 1.0, l.high, l.low, l.volume, l.quoteVolume, l.close * 0.9995, l.close * 1.0005) }
        override suspend fun bookTickers() = daily.map { (s, c) -> BookTicker(s, c.last().close * 0.9995, 10.0, c.last().close * 1.0005, 10.0, clock()) }
        override suspend fun bookTicker(symbol: String) = bookTickers().first { it.symbol == symbol }
        override suspend fun orderBook(symbol: String, limit: Int): OrderBook {
            val p = daily.getValue(symbol).last().close
            return OrderBook(symbol, listOf(PriceLevel(p * 0.999, 5_000 / p)), listOf(PriceLevel(p * 1.001, 5_000 / p)))
        }
    }

    private fun info() = series.keys.map { s ->
        val base = s.removeSuffix("USDT")
        TestData.symbolInfo(s, base).copy(
            riskFlagged = s == "STUSDT",
            sectors = when (s) { "TSLAXUSDT" -> listOf("Tokenized Stocks"); "AAAUSDT" -> listOf("MEME"); "DDDUSDT" -> listOf("AI"); else -> listOf("Main") },
        )
    }

    @Test
    fun `discovers pairs dynamically, excludes products and applies the S1 rules in order`() = runBlocking {
        val api = FakeApi(info(), series.mapValues { daily(it.value) }, { now })
        val scanner = WideScanner(api, clock = TimeSource { now })
        val st = scanner.scan()
        assertNull(st.lastError)
        val r = st.result!!
        assertEquals(10, r.totalPairs)
        assertEquals(3, r.productExcluded) // USDC (stable), BTC3L (kaldıraçlı), TSLAX (tokenleştirilmiş hisse)
        val reasons = r.rejections
        assertEquals(3, reasons[WideReject.PRODUCT.title]) // USDC, BTC3L, TSLAX
        assertEquals(1, reasons[WideReject.RISK_FLAG.title]) // ST
        assertEquals(1, reasons[WideReject.SHORT_HISTORY.title]) // NEW: 100 günlük geçmiş
        assertEquals(1, reasons[WideReject.LOW_MEDIAN_VOLUME.title]) // THIN
        assertEquals(1, reasons[WideReject.TREND.title]) // CCC
        assertEquals(setOf("AAAUSDT", "DDDUSDT"), r.top.map { it.symbol }.toSet())
        assertEquals(setOf("AAAUSDT", "DDDUSDT"), r.candidates.toSet())
        assertTrue(r.top.all { it.stopDistancePercent != null && it.bidDepth1Percent != null && it.positionSize!! <= 300.0 })
        assertEquals(mapOf("MEME" to 1, "AI" to 1), r.sectorCounts)

        // Aynı gün ikinci tarama 1D mumlarını yeniden indirmez (önbellek).
        val calls = api.klineCalls
        scanner.scan()
        assertEquals(calls, api.klineCalls)
    }

    @Test
    fun `ranking agrees with the backtest S1 ranking on the same data`() = runBlocking {
        val api = FakeApi(info(), series.mapValues { daily(it.value) }, { now })
        val r = WideScanner(api, clock = TimeSource { now }).scan().result!!
        val u = Universe.of(series.filterKeys { it !in setOf("USDCUSDT", "BTC3LUSDT", "TSLAXUSDT", "STUSDT") }.map { H4Dataset(it.key, it.value) })
        val t = t0 + days * DAY_MS
        val expected = RelativeStrength(30).ranking(u, t, V3Config(t, t)).map { it.first }
        assertEquals(expected, r.top.mapNotNull { c -> c.symbol.takeIf { c.rank != null } })
    }

    @Test
    fun `new listings and delistings are detected between scans`() = runBlocking {
        val api = FakeApi(info(), series.mapValues { daily(it.value) }, { now })
        val scanner = WideScanner(api, clock = TimeSource { now })
        scanner.scan()
        api.info = info().filter { it.symbol != "CCCUSDT" } + TestData.symbolInfo("ZZZUSDT", "ZZZ")
        val r = scanner.scan().result!!
        assertEquals(listOf("ZZZUSDT"), r.newListings)
        assertEquals(listOf("CCCUSDT"), r.delisted)
    }

    @Test
    fun `missing BTC data is a software or data error, not a rule decision`() = runBlocking {
        val api = FakeApi(info(), series.filterKeys { it != "BTCUSDT" }.mapValues { daily(it.value) }, { now })
        val st = WideScanner(api, clock = TimeSource { now }).scan()
        assertTrue(st.lastError!!.contains("BTCUSDT"))
    }
}
