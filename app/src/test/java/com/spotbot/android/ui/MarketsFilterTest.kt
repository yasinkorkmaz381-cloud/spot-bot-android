package com.spotbot.android.ui

import com.spotbot.android.data.market.SimulatedMarketRepository
import com.spotbot.android.ui.markets.filterTickers
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketsFilterTest {

    private val tickers = SimulatedMarketRepository.DEFAULT_TICKERS

    private fun symbols(query: String) = filterTickers(tickers, query).map { it.pair.symbol }

    @Test
    fun `blank query returns everything`() {
        assertEquals(tickers.size, symbols("  ").size)
    }

    @Test
    fun `matches case-insensitively and accepts slash notation`() {
        assertEquals(listOf("BTCUSDT"), symbols("btc"))
        assertEquals(listOf("ETHUSDT"), symbols("eth/usdt"))
        assertEquals(listOf("DOGEUSDT"), symbols("DOGEUSDT"))
    }

    @Test
    fun `no match returns empty list`() {
        assertEquals(emptyList<String>(), symbols("XYZ"))
    }
}
