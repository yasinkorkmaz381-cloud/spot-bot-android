package com.spotbot.core.research

import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.exchange.ReplayMarketDataApi
import com.spotbot.core.model.Candle
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class ForwardTrackerTest {
    private val t0 = 1_577_836_800_000L // 2020-01-01

    private fun series(seed: Int, drift: Double, bars: Int = 6 * 700): List<Candle> {
        val rnd = Random(seed)
        var p = 100.0
        return (0 until bars).map { i ->
            val o = p
            val c = p * exp(drift + rnd.nextDouble(-0.02, 0.02))
            val t = t0 + i * H4_MS
            p = c
            Candle(t, o, maxOf(o, c) * 1.004, minOf(o, c) * 0.996, c, 1e6 / c, t + H4_MS, 1e6)
        }
    }

    private val raw = mapOf(
        "BTCUSDT" to series(1, 0.0003),
        "AAAUSDT" to series(2, 0.0012),
        "BBBUSDT" to series(3, 0.0008),
        "CCCUSDT" to series(4, -0.0005),
    )
    private var now = 0L
    private val api = ReplayMarketDataApi(raw.mapValues { HistoricalDataset(it.key, emptyList(), emptyList(), it.value) }, emptyMap(), { now })

    private fun tracker(store: ForwardStore) =
        ForwardTracker(api, store, TimeSource { now }, symbols = { raw.keys }, warmupMs = 330 * DAY_MS)

    @Test
    fun `forward test equals the research engine on the same data and only trades after its start`() = runBlocking {
        val store = InMemoryForwardStore()
        now = t0 + 400 * DAY_MS + 3_600_000 // 4H kapanışından 1 saat sonra
        val first = tracker(store).refresh()
        val start = t0 + 400 * DAY_MS
        assertEquals(start, first.startedAt)
        assertNull(first.result) // henüz kapanmış yeni mum yok

        now = t0 + 640 * DAY_MS + 5_000
        val later = tracker(store).refreshIfDue() // yeni örnek: başlangıç kalıcı olmalı
        assertEquals(start, later.startedAt)
        assertNull(later.lastError)
        val r = assertNotNull(later.result).let { later.result!! }
        assertTrue("ileri testte işlem oluşmalı", r.trades + r.open.size > 0)
        assertTrue(r.tradeList.all { it.entryTime >= start })
        assertTrue(r.open.all { it.entryTime >= start })

        val end = t0 + 640 * DAY_MS
        val u = Universe.of(raw.map { (s, c) -> H4Dataset(s, c.filter { it.openTime >= start - 330 * DAY_MS && it.openTime + H4_MS <= end }) })
        val expected = V3Engine(u, V3Config(start, end)).run(RelativeStrength(30), closeAtEnd = false)
        assertEquals(expected.trades, r.trades)
        assertEquals(expected.open.size, r.open.size)
        assertTrue(abs(expected.netReturnPercent - r.netReturnPercent) < 1e-9)
        // Açık pozisyonlar dahil hesap: başlangıç sermayesi + kapanan net + açık net = son değer.
        val value = 1_000.0 * (1 + r.netReturnPercent / 100)
        assertEquals(1_000.0 + r.tradeList.sumOf { it.netPnl } + r.open.sumOf { it.unrealizedPnl }, value, 1e-6)
    }

    @Test
    fun `a data failure is reported as an error, not as a rule decision`() = runBlocking {
        now = t0 + 500 * DAY_MS
        val noBtc = ReplayMarketDataApi(raw.filterKeys { it != "BTCUSDT" }.mapValues { HistoricalDataset(it.key, emptyList(), emptyList(), it.value) }, emptyMap(), { now })
        val broken = ForwardTracker(noBtc, InMemoryForwardStore(), TimeSource { now }, symbols = { listOf("AAAUSDT") })
        val s = broken.refresh()
        assertNotNull(s.lastError)
        assertNull(s.result)
    }

    @Test
    fun `tracked symbols only grow and a universe change restarts the forward test`() = runBlocking {
        val store = InMemoryForwardStore()
        var list = listOf("AAAUSDT")
        now = t0 + 400 * DAY_MS
        val a = ForwardTracker(api, store, TimeSource { now }, symbols = { list }, universeName = { "Geniş evren" }, warmupMs = 330 * DAY_MS).refresh()
        assertEquals(listOf("AAAUSDT", "BTCUSDT"), a.trackedSymbols)
        list = listOf("BBBUSDT")
        now += 2 * DAY_MS
        val b = ForwardTracker(api, store, TimeSource { now }, symbols = { list }, universeName = { "Geniş evren" }, warmupMs = 330 * DAY_MS).refresh()
        assertEquals(listOf("AAAUSDT", "BBBUSDT", "BTCUSDT"), b.trackedSymbols)
        assertEquals(a.startedAt, b.startedAt)
        val c = ForwardTracker(api, store, TimeSource { now }, symbols = { list }, warmupMs = 330 * DAY_MS).refresh()
        assertEquals(DEFAULT_UNIVERSE, c.universe)
        assertEquals(now / H4_MS * H4_MS, c.startedAt)
    }
}
