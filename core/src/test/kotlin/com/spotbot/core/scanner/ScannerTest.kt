package com.spotbot.core.scanner

import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.support.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerTest {

    private val config = ScannerConfig()

    private fun ticker(symbol: String, volume: Double, change: Double = 1.0) = Ticker24h(symbol, 10.0, change, 0.0, 0.0, 0.0, volume, 9.99, 10.01)

    @Test
    fun `universe keeps the watchlist and adds the most liquid eligible pairs`() {
        val infos = listOf("BTC", "ETH", "AAA", "BBB", "CCC", "USDC", "ETH3L", "GOLD(XAUT)").map { TestData.symbolInfo("${it}USDT", it) } +
            TestData.symbolInfo("RISKYUSDT", "RISKY").copy(riskFlagged = true)
        val tickers = listOf(
            ticker("AAA" + "USDT", 5e6), ticker("BBB" + "USDT", 9e6), ticker("CCC" + "USDT", 0.5e6), ticker("USDCUSDT", 50e6),
            ticker("ETH3LUSDT", 30e6), ticker("GOLD(XAUT)USDT", 20e6), ticker("RISKYUSDT", 40e6),
        )
        val universe = Scanner.selectUniverse(infos, tickers, config)

        assertEquals(listOf("BTCUSDT", "ETHUSDT", "BBBUSDT", "AAAUSDT"), universe)
    }

    @Test
    fun `illiquid, wide spread and shallow coins are eliminated with reasons`() {
        val mtf = TestData.mtf("XUSDT")
        val coin = CoinMarketData(
            TestData.symbolInfo("XUSDT", "X"), ticker("XUSDT", 100_000.0),
            BookTicker("XUSDT", 10.0, 1.0, 10.1, 1.0, 0), OrderBook("XUSDT", listOf(PriceLevel(10.0, 1.0)), listOf(PriceLevel(10.1, 1.0))), mtf,
        )
        val scan = Scanner.scan(listOf(coin), mtf, config, 0).coins.single()

        assertEquals(CoinStatus.ELIMINATED, scan.status)
        assertTrue(scan.reasons.any { it.startsWith("24s hacim") })
        assertTrue(scan.reasons.any { it.startsWith("Spread") })
        assertTrue(scan.reasons.any { it.startsWith("Emir defteri") })
    }

    @Test
    fun `extreme daily move flags manipulation risk`() {
        val mtf = TestData.mtf("YUSDT")
        val deep = OrderBook("YUSDT", listOf(PriceLevel(9.99, 10_000.0)), listOf(PriceLevel(10.01, 10_000.0)))
        val coin = CoinMarketData(TestData.symbolInfo("YUSDT", "Y"), ticker("YUSDT", 5e6, change = 80.0), BookTicker("YUSDT", 9.99, 1.0, 10.01, 1.0, 0), deep, mtf)
        val scan = Scanner.scan(listOf(coin), mtf, config, 0).coins.single()

        assertEquals(CoinStatus.FLAGGED, scan.status)
        assertTrue(scan.reasons.single().startsWith("24s değişim aşırı"))
    }

    @Test
    fun `market filter blocks longs when BTC data is missing`() {
        val state = Scanner.marketState(null, emptyList(), config)
        assertFalse(state.allowsLongs)
    }

    @Test
    fun `at most top N passed coins become candidates`() {
        val mtf = TestData.mtf()
        val deep = { s: String -> OrderBook(s, listOf(PriceLevel(9.99, 10_000.0)), listOf(PriceLevel(10.01, 10_000.0))) }
        val coins = (1..6).map { i ->
            val s = "C${i}USDT"
            CoinMarketData(TestData.symbolInfo(s, "C$i"), ticker(s, 5e6), BookTicker(s, 9.999, 1.0, 10.001, 1.0, 0), deep(s), TestData.mtf(s))
        }
        val report = Scanner.scan(coins, mtf, config.copy(topCandidates = 2), 0)
        val passed = report.coins.count { it.status == CoinStatus.PASSED || it.status == CoinStatus.CANDIDATE }
        assertEquals(6, passed)
        assertTrue(report.candidates.size <= 2)
        assertTrue(report.coins.filter { it.status == CoinStatus.CANDIDATE }.all { it.preScore > 0 })
    }
}
