package com.spotbot.android.data

import com.spotbot.android.data.market.SimulatedMarketRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SimulatedMarketRepositoryTest {

    private val initial = SimulatedMarketRepository.DEFAULT_TICKERS

    @Test
    fun `prices move only while someone is listening`() = runTest {
        val repo = SimulatedMarketRepository(backgroundScope, Random(42), tickIntervalMillis = 1_000)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(initial, repo.tickers.value)

        val listener = backgroundScope.launch { repo.tickers.collect {} }
        advanceTimeBy(1_001)
        runCurrent()
        val moved = repo.tickers.value
        assertNotEquals(initial, moved)

        listener.cancel()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(moved, repo.tickers.value)
    }

    @Test
    fun `each step stays within the configured bound`() = runTest {
        val repo = SimulatedMarketRepository(backgroundScope, Random(7), tickIntervalMillis = 1_000)
        backgroundScope.launch { repo.tickers.collect {} }

        var previous = repo.tickers.value
        repeat(50) {
            advanceTimeBy(1_000)
            runCurrent()
            val current = repo.tickers.value
            current.zip(previous).forEach { (now, before) ->
                val step = (now.lastPrice.toDouble() / before.lastPrice.toDouble()) - 1
                assertTrue("step $step out of bounds", kotlin.math.abs(step) <= 0.0025 + 1e-6)
                assertEquals(before.openPrice, now.openPrice)
            }
            previous = current
        }
    }
}
