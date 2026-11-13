package com.spotbot.core.kr

import com.spotbot.core.kr.KrConfig.Companion.M15
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 3 dakikalık tarama ve ayrı koruma döngüsü. */
class KrLoopsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val setup = Scenario.setup()
    private val retestClose = setup.bars[setup.retestIndex].closeTime
    private val btc = Scenario.warmup(setup.bars.size, start = 50_000.0, step = 0.0002)
    private val cmcList = { m: FakeKrMarket -> CmcList(m.clock, m.clock, listOf(Scenario.asset(1, "BTC", 1, m.price("BTCUSDT")), Scenario.asset(2, "X", 2, m.price("XUSDT")))) }

    private fun market(clock: Long) = FakeKrMarket(mapOf("XUSDT" to setup.bars, "BTCUSDT" to btc), listOf(Scenario.info("XUSDT"), Scenario.info("BTCUSDT")), clock)

    @Test
    fun `three-minute scans never confirm on an unclosed candle and never repeat a setup`() = runTest {
        val m = market(retestClose - 2 * 60_000)
        val store = MemoryKrStore()
        val paper = KrPaper(m, CmcSource { cmcList(m) }, store, TimeSource { m.clock })
        // Retest mumu henüz kapanmadı (2 dk kaldı): giriş yok.
        paper.scan(BotMode.FULL, m.clock)
        assertEquals(0, store.state!!.book.positions.size)
        // Kapanıştan sonraki ilk tarama: giriş.
        m.clock = retestClose + KrPaper.SETTLE_MILLIS + 5_000
        paper.scan(BotMode.FULL, m.clock)
        assertEquals(1, store.state!!.book.positions.size)
        val id = store.state!!.book.positions.single().setupId
        // Sonraki 3 dakikalık taramalar aynı kapanmış mumdan yeniden işlem üretmez, yeni mum yoksa mum indirmez.
        val callsBefore = m.calls
        m.clock += KrPaper.SCAN_INTERVAL
        paper.scan(BotMode.FULL, m.clock)
        assertEquals(callsBefore, m.calls)
        assertEquals(1, store.state!!.book.positions.size + store.state!!.book.trades.size)
        assertTrue(id in store.state!!.processedSetups)
        val t = store.state!!.scanTiming
        assertEquals(3, t.count)
        assertTrue(t.lastSuccessAt > 0 && t.nextAt > m.clock)
        assertNull(t.lastError)
    }

    @Test
    fun `a scan does not start while the previous one is still running`() = runTest {
        val m = market(retestClose + 15_000)
        val gate = CompletableDeferred<Unit>()
        val slow = object : com.spotbot.core.exchange.MarketDataApi by m {
            override suspend fun bookTickers(): List<BookTicker> { gate.await(); return m.bookTickers() }
        }
        val store = MemoryKrStore()
        val paper = KrPaper(slow, CmcSource { cmcList(m) }, store, TimeSource { m.clock })
        val first = async { paper.scan(BotMode.FULL, m.clock) }
        testScheduler.runCurrent()
        assertFalse(paper.scan(BotMode.FULL, m.clock))
        assertEquals(1, store.state!!.scanTiming.skippedOverlap)
        gate.complete(Unit)
        assertTrue(first.await())
    }

    @Test
    fun `a stream price through the stop closes immediately without waiting for the 15M close`() = runTest {
        val m = market(retestClose + 15_000)
        val store = MemoryKrStore()
        val paper = KrPaper(m, CmcSource { cmcList(m) }, store, TimeSource { m.clock })
        paper.cycle(BotMode.FULL)
        val p = store.state!!.book.positions.single()
        m.clock += 20_000
        val tick = BookTicker(p.symbol, p.stop * 0.999, 1.0, p.stop * 1.0005, 1.0, m.clock)
        assertTrue(paper.onTick(tick))
        paper.protect()
        val t = store.state!!.book.trades.single()
        assertEquals(ExitReason.STOP, t.reason)
        assertEquals(ExitSource.LIVE, t.source)
        assertEquals(tick.bid * (1 - p.exitLeg.slippage), t.exitPrice, 1e-9)
        assertEquals("Fiyat akışı", store.state!!.protection.lastPriceSource)
    }

    @Test
    fun `after a disconnection events are applied in the order they happened`() {
        val leg = Leg(0.0005, 0.0005, 0.0005)
        val t0 = Scenario.T0 + 100 * M15
        val p = KrPosition("p", "XUSDT", "s", t0, 100.0, 100.0, 1.0, stop = 98.0, target = 104.0, level = 99.5, invalidation = 99.4,
            entryLeg = leg, exitLeg = leg, entryFee = 0.05, plannedNetRewardRisk = 1.5, riskAmount = 2.0, highest = 100.0, checkedUntil = t0)
        fun bar(min: Int, o: Double, h: Double, l: Double, c: Double) = Candle(t0 + min * 60_000L, o, h, l, c, 1.0, t0 + (min + 1) * 60_000L, 1.0)
        // 15. dakikada kapanış seviyenin altında (tez iptali) → bir sonraki dakikanın açılışında çıkış.
        // 30. dakikada fiyat stopun altına inse de önce gerçekleşen tez iptalidir.
        val bars = (0 until 14).map { bar(it, 100.0, 100.2, 99.8, 100.0) } + bar(14, 100.0, 100.0, 99.2, 99.3) +
            bar(15, 99.35, 99.4, 99.1, 99.2) + (16 until 30).map { bar(it, 99.2, 99.3, 99.0, 99.1) } + bar(30, 99.0, 99.0, 97.0, 97.5)
        val r = KrExits.replay(p, bars, null, t0 + 31 * 60_000L, KrConfig()) { a, _ -> a >= t0 + 5 * 60_000L }
        val t = r.trade!!
        assertEquals(ExitReason.INVALIDATION, t.reason)
        assertEquals(t0 + 15 * 60_000L, t.exitTime)
        assertEquals(99.35 * (1 - leg.priceImpact), t.exitPrice, 1e-9)
        assertEquals(ExitSource.BACKFILLED, t.source)
    }

    @Test
    fun `candles are cached on disk and a restart only fetches new candles`() = runTest {
        val m = market(retestClose - 2 * 60_000)
        val disk = CandleFileStore(tmp.root)
        KrPaper(m, CmcSource { cmcList(m) }, MemoryKrStore(), TimeSource { m.clock }, candleStore = disk).scan(BotMode.FULL, m.clock)
        assertTrue(disk.load("XUSDT", M15).size >= 100)
        // Yeni süreç (bellek boş), bir mum sonra: 15M için yalnız yeni mum istenir.
        m.clock += M15
        val seen = ArrayList<Pair<Timeframe, Long?>>()
        val spy = object : com.spotbot.core.exchange.MarketDataApi by m {
            override suspend fun klines(symbol: String, timeframe: Timeframe, limit: Int, startTime: Long?, endTime: Long?): List<Candle> {
                if (symbol == "XUSDT") seen += timeframe to startTime
                return m.klines(symbol, timeframe, limit, startTime, endTime)
            }
        }
        KrPaper(spy, CmcSource { cmcList(m) }, MemoryKrStore(), TimeSource { m.clock }, candleStore = disk).scan(BotMode.FULL, m.clock)
        val m15Calls = seen.filter { it.first == Timeframe.M15 }
        assertEquals(1, m15Calls.size)
        assertTrue("başlangıç verilmeli (yalnız yeni mumlar)", m15Calls.single().second != null)
    }
}
