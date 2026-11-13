package com.spotbot.core.kr

import com.spotbot.core.kr.KrConfig.Companion.DAY
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KrHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val setup = Scenario.setup()
    private val btc = Scenario.warmup(setup.bars.size, start = 50_000.0, step = 0.0002)
    private val m15 = mapOf("XUSDT" to setup.bars.toList(), "BTCUSDT" to btc.toList())
    private val end = setup.bars.last().closeTime
    private val from = Scenario.T0 + 8 * DAY

    private fun market() = FakeKrMarket(m15, listOf(Scenario.info("XUSDT"), Scenario.info("BTCUSDT")), end)

    /** O günün 00:00 kapanışıyla aynı fiyatı taşıyan CMC listesi. */
    private fun list(day: Long): CmcList {
        fun px(sym: String) = m15.getValue(sym).last { it.closeTime <= day }.close
        return CmcList(day, day, listOf(Scenario.asset(1, "BTC", 1, px("BTCUSDT")), Scenario.asset(2, "X", 2, px("XUSDT"))), CmcList.SOURCE_HISTORICAL)
    }

    private fun history(api: FakeKrMarket, hist: CmcHistorySource?, latest: CmcSource = CmcSource { list(end - Math.floorMod(end, DAY)) }) =
        KrHistory(api, latest, hist, CandleFileStore(tmp.root.resolve("c")), CmcListFileCache(tmp.root.resolve("l")), now = { api.clock }, cmcPaceMillis = 0)

    @Test
    fun `in-app history uses the same engine and gives the same trades as a direct backtest`() = runTest {
        val api = market()
        var cmcCalls = 0
        val out = history(api, CmcHistorySource { cmcCalls++; list(it) }).run(from, end + 1, 1_000.0)
        assertEquals(UniverseSource.HISTORICAL, out.source)
        assertTrue(out.warnings.isEmpty())
        val cfg = KrConfig()
        val direct = KrBacktest(cfg).run(
            KrBacktestInput(m15, m15.mapValues { Trend4h(Trend4h.aggregate(it.value), cfg) }, { setOf("XUSDT", "BTCUSDT") },
                // Sahte borsanın anlık görüntüsüyle aynı: taker %0,05, yarım spread %0,01.
                m15.keys.associateWith { SymbolTerms(0.0005, 0.0001, 0.0001, 1.0) }, "doğrudan"),
            from, end,
        )
        assertEquals(1, out.result.trades.size)
        val a = out.result.trades.single(); val b = direct.trades.single()
        assertEquals(b.entryTime, a.entryTime); assertEquals(b.exitTime, a.exitTime); assertEquals(b.reason, a.reason)
        assertEquals(b.entryPrice, a.entryPrice, 1e-9); assertEquals(b.exitPrice, a.exitPrice, 1e-9); assertEquals(b.netPnl, a.netPnl, 1e-6)
        assertEquals(out.metrics.netPnl, a.netPnl, 1e-9)
        // İkinci çalıştırma: CMC listeleri ve mumlar önbellekten (kredi harcanmaz, mum isteği yok).
        val calls = cmcCalls
        api.calls = 0
        val again = history(api, CmcHistorySource { cmcCalls++; list(it) }).run(from, end + 1, 1_000.0)
        assertEquals(calls, cmcCalls)
        assertTrue("mum isteği: ${api.calls}", api.calls <= 4)
        assertEquals(out.result.trades, again.result.trades)
    }

    @Test
    fun `starting balance is the shared portfolio balance`() = runTest {
        val out = history(market(), CmcHistorySource { list(it) }).run(from, end + 1, 5_000.0)
        assertEquals(5_000.0, out.result.startEquity, 0.0)
        val t = out.result.trades.single()
        // 1.000 USDT'lik hesapta coin başına en fazla 200 USDT; 5.000'de 1.000 USDT'ye kadar.
        assertTrue(t.entryPrice * t.quantity > 300.0)
    }

    @Test
    fun `without historical CMC access the test is labelled as a today-list test`() = runTest {
        val out = history(market(), CmcHistorySource { throw CmcException("CMC planınız bu veriye izin vermiyor", 403, 1006) }).run(from, end + 1, 1_000.0)
        assertEquals(UniverseSource.TODAY, out.source)
        assertTrue(out.warnings.first().startsWith("Bugünkü ilk 300 listesiyle geçmiş test"))
    }

    @Test
    fun `the last unpublished day uses the previous list within 48 hours and stays historical`() = runTest {
        val lastDay = (end - 1) - Math.floorMod(end - 1, DAY)
        val out = history(market(), CmcHistorySource {
            if (it >= lastDay) throw CmcException("Search query is out of range", 400) else list(it)
        }).run(from, end + 1, 1_000.0)
        assertEquals(UniverseSource.HISTORICAL, out.source)
        assertTrue(out.warnings.single().contains("48 saat"))
        assertEquals(1, out.result.trades.size)
    }

    @Test
    fun `cancel stops the run`() = runTest {
        val job = async { history(market(), CmcHistorySource { kotlinx.coroutines.delay(10_000); list(it) }).run(from, end + 1, 1_000.0) }
        testScheduler.advanceTimeBy(5_000)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `key sanitising keeps only the uuid`() {
        assertEquals("b54bcf4d-1bca-4e8e-9a24-22ff2c3d462c", sanitizeCmcKey(" X-CMC_PRO_API_KEY: \"B54BCF4D-1BCA-4E8E-9A24-22FF2C3D462C\"\n"))
        assertEquals("abc", sanitizeCmcKey("  abc \n"))
        assertTrue(CmcException("x", 403, 1006).planLimited)
    }
}
