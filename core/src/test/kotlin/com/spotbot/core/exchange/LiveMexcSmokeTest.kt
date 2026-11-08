package com.spotbot.core.exchange

import com.spotbot.core.exchange.mexc.ConnectionStatus
import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.exchange.mexc.MexcWebSocketClient
import com.spotbot.core.model.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Gerçek MEXC'ye bağlanan duman testi. Yalnızca `./gradlew :core:test -Plive` ile çalışır. */
class LiveMexcSmokeTest {

    private fun requireLive() = assumeTrue(System.getProperty("mexc.live") == "true")

    @Test
    fun `rest endpoints return consistent closed candles`() = runBlocking {
        requireLive()
        val api = MexcRestClient()
        val now = api.serverTime()
        val candles = api.klines("BTCUSDT", Timeframe.M15, limit = 5)

        assertEquals(5, candles.size)
        candles.zipWithNext().forEach { (a, b) -> assertEquals(Timeframe.M15.millis, b.openTime - a.openTime) }
        candles.forEach { assertEquals(Timeframe.M15.millis, it.closeTime - it.openTime) }
        // Son mum çoğu zaman henüz kapanmamıştır; sinyaller yalnız kapanmış mumlardan üretilmeli.
        assertTrue(candles.dropLast(1).all { it.isClosedAt(now) })
        assertTrue(api.exchangeInfo().any { it.symbol == "BTCUSDT" && it.isSpotTradingAllowed })
    }

    @Test
    fun `websocket delivers book ticker updates`() = runBlocking {
        requireLive()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ws = MexcWebSocketClient(scope)
            val feed = LivePriceFeed(MexcRestClient(), ws, scope)
            feed.watch(setOf("BTCUSDT", "ETHUSDT"))
            feed.start()
            withTimeout(30_000) {
                ws.state.first { it.status == ConnectionStatus.CONNECTED && it.lastMessageAt != null }
                val prices = feed.prices.first { it.keys.containsAll(setOf("BTCUSDT", "ETHUSDT")) }
                assertTrue(prices.getValue("BTCUSDT").ask >= prices.getValue("BTCUSDT").bid)
            }
            feed.stop()
        } finally {
            scope.cancel()
        }
    }
}
