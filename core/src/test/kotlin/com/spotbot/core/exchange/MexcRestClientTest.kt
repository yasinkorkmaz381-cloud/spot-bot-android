package com.spotbot.core.exchange

import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.model.Timeframe
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MexcRestClientTest {

    private val server = MockWebServer()
    private lateinit var client: MexcRestClient

    @Before
    fun setUp() {
        server.start()
        client = MexcRestClient(baseUrl = server.url("/").toString(), minRequestIntervalMillis = 0)
    }

    @After
    fun tearDown() = server.close()

    private fun enqueue(body: String, code: Int = 200, retryAfter: String? = null) {
        server.enqueue(
            MockResponse.Builder().code(code).body(body).apply {
                if (retryAfter != null) addHeader("Retry-After", retryAfter)
            }.build(),
        )
    }

    @Test
    fun `parses klines and sends MEXC interval names`() = runBlocking {
        enqueue(
            """[[1794168000000,"75918.44","75934.68","75732.63","75732.64","53.73085051",1794171600000,"4073203.54"]]""",
        )

        val candles = client.klines("BTCUSDT", Timeframe.H1, limit = 2)

        val request = server.takeRequest()
        assertEquals("/api/v3/klines", request.url.encodedPath)
        assertEquals("60m", request.url.queryParameter("interval"))
        assertEquals("2", request.url.queryParameter("limit"))
        with(candles.single()) {
            assertEquals(1794168000000L, openTime)
            assertEquals(1794171600000L, closeTime)
            assertEquals(75918.44, open, 0.0)
            assertEquals(75934.68, high, 0.0)
            assertEquals(75732.63, low, 0.0)
            assertEquals(75732.64, close, 0.0)
            assertEquals(4073203.54, quoteVolume, 0.0)
        }
    }

    @Test
    fun `parses exchange info fields used for order rules`() = runBlocking {
        enqueue(
            """{"symbols":[{"symbol":"ZECUSDT","status":"1","baseAsset":"ZEC","baseAssetPrecision":6,"quoteAsset":"USDT",
               "quotePrecision":2,"orderTypes":["LIMIT","MARKET","LIMIT_MAKER"],"isSpotTradingAllowed":true,
               "quoteAmountPrecision":"1","baseSizePrecision":"0.001","makerCommission":"0","takerCommission":"0.0005",
               "tradeSideType":1,"st":false}]}""",
        )

        val info = client.exchangeInfo().single()

        assertEquals("ZECUSDT", info.symbol)
        assertEquals(2, info.pricePrecision)
        assertEquals(6, info.quantityPrecision)
        assertEquals(0.001, info.minQuantity, 0.0)
        assertEquals(1.0, info.minNotional, 0.0)
        assertEquals(0.0005, info.takerFee, 0.0)
        assertTrue(info.isOnline && info.isSpotTradingAllowed && info.canTradeBothSides)
        assertFalse(info.riskFlagged)
    }

    @Test
    fun `converts 24h change ratio to percent and tolerates nulls`() = runBlocking {
        enqueue(
            """[{"symbol":"ZECUSDT","priceChangePercent":"-0.0222","lastPrice":"1212.37","highPrice":"1257",
               "lowPrice":"1186.18","volume":"1006.2","quoteVolume":null,"bidPrice":"1210.03","askPrice":"1212.24"}]""",
        )

        val ticker = client.tickers24h().single()

        assertEquals(-2.22, ticker.changePercent, 1e-9)
        assertEquals(0.0, ticker.quoteVolume, 0.0)
    }

    @Test
    fun `retries after rate limit using Retry-After`() = runBlocking {
        enqueue("""{"code":429}""", code = 429, retryAfter = "0")
        enqueue("""{"serverTime":1794172110648}""")

        assertEquals(1794172110648L, client.serverTime())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `client errors are not retried`() = runBlocking {
        enqueue("""{"code":-1121,"msg":"Invalid symbol."}""", code = 400)

        val error = runCatching { client.bookTicker("NOPE") }.exceptionOrNull() as ExchangeException
        assertEquals(400, error.httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `history loader continues after the last returned candle so capped pages leave no gaps`() = runBlocking {
        val hour = Timeframe.H1.millis
        fun row(i: Int) = "[${i * hour},\"1\",\"2\",\"0.5\",\"1.5\",\"10\",${(i + 1) * hour},\"15\"]"
        // Sunucu 3 istenmesine rağmen yalnız 2 mum döndürür (MEXC'nin 500 sınırını taklit eder).
        enqueue("[" + (0 until 2).joinToString(",") { row(it) } + "]")
        enqueue("[" + (2 until 4).joinToString(",") { row(it) } + "]")
        enqueue("[" + (4 until 5).joinToString(",") { row(it) } + "]")

        val candles = client.klinesBetween("BTCUSDT", Timeframe.H1, start = 0, end = 5 * hour, pageSize = 3)

        assertEquals((0 until 5).map { it * hour }, candles.map { it.openTime })
        val requests = (1..3).map { server.takeRequest().url }
        assertEquals(listOf("0", "${2 * hour}", "${4 * hour}"), requests.map { it.queryParameter("startTime") })
        assertEquals((3 * hour - 1).toString(), requests.first().queryParameter("endTime"))
    }
}
