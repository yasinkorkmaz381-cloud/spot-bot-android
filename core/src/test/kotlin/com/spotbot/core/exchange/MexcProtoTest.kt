package com.spotbot.core.exchange

import com.spotbot.core.exchange.mexc.MexcChannels
import com.spotbot.core.exchange.mexc.MexcProto
import com.spotbot.core.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MexcProtoTest {

    /** 8 Kasım 2026'da wss://wbs-api.mexc.com/ws üzerinden alınmış gerçek mesaj. */
    private val realBookTicker = Base64.getDecoder().decode(
        "CjRzcG90QHB1YmxpYy5hZ2dyZS5ib29rVGlja2VyLnYzLmFwaS5wYkAxMDBtc0BCVENVU0RUGgdCVENVU0RUMNzzjuibNNoTPQoINzU3MjguNTUS" +
            "CjUuNzY0OTk2MDcaCDc1NzI4LjU2IgcwLjAwNjI3Kgs4NTc3ODE0NjgxOTCG847omzQ=",
    )

    /** Resmi .proto dosyalarından üretilen Python koduyla kodlanmış kline mesajı. */
    private val generatedKline = Base64.getDecoder().decode(
        "CilzcG90QHB1YmxpYy5rbGluZS52My5hcGkucGJARVRIVVNEVEBNaW4xNRoHRVRIVVNEVDDzxYnRxDKiE0UKBU1pbjE1EISL/rsGGgYzMjUwLjUi" +
            "BzMyNjEuMjUqBDMyNzAyBjMyNDUuMToFODEyLjRCCjI2NDUwMDAuNzVIiJL+uwY=",
    )

    @Test
    fun `decodes a real aggregated book ticker push`() {
        val message = MexcProto.decode(realBookTicker)

        assertEquals("spot@public.aggre.bookTicker.v3.api.pb@100ms@BTCUSDT", message.channel)
        assertEquals("BTCUSDT", message.symbol)
        assertEquals(1794172500444L, message.sendTime)
        val ticker = MexcChannels.toBookTicker(message, receivedAt = 0)!!
        assertEquals(75728.55, ticker.bid, 0.0)
        assertEquals(5.76499607, ticker.bidQty, 0.0)
        assertEquals(75728.56, ticker.ask, 0.0)
        assertEquals(0.00627, ticker.askQty, 0.0)
        assertEquals(1794172500444L, ticker.time)
    }

    @Test
    fun `decodes a kline push`() {
        val message = MexcProto.decode(generatedKline)
        val body = message.body as MexcProto.Body.KlineBody

        assertEquals("ETHUSDT", message.symbol)
        assertEquals(Timeframe.M15, MexcProto.timeframeOf(body.interval))
        with(body.candle) {
            assertEquals(1736410500000L, openTime)
            assertEquals(1736411400000L, closeTime)
            assertEquals(3250.5, open, 0.0)
            assertEquals(3270.0, high, 0.0)
            assertEquals(3245.1, low, 0.0)
            assertEquals(3261.25, close, 0.0)
            assertEquals(812.4, volume, 0.0)
            assertEquals(2645000.75, quoteVolume, 0.0)
        }
    }

    @Test
    fun `truncated message fails loudly instead of returning garbage`() {
        val truncated = realBookTicker.copyOf(realBookTicker.size - 10)
        assertTrue(runCatching { MexcProto.decode(truncated) }.isFailure)
    }
}
