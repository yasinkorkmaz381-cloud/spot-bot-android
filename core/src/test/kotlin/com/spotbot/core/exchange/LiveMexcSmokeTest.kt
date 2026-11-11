package com.spotbot.core.exchange

import com.spotbot.core.exchange.mexc.MexcRestClient
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

}
