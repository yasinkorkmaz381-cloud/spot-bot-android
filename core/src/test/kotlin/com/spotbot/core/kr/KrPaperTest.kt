package com.spotbot.core.kr

import com.spotbot.core.kr.KrConfig.Companion.M15
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KrPaperTest {
    private val setup = Scenario.setup()
    private val retestClose = setup.bars[setup.retestIndex].closeTime
    private val btc = Scenario.warmup(setup.bars.size, start = 50_000.0, step = 0.0002)

    private inner class Env(start: Long) {
        val market = FakeKrMarket(mapOf("XUSDT" to setup.bars, "BTCUSDT" to btc), listOf(Scenario.info("XUSDT"), Scenario.info("BTCUSDT")), start)
        val clock = TimeSource { market.clock }
        val store = MemoryKrStore()
        val cmc = CmcSource {
            CmcList(market.clock, market.clock, listOf(Scenario.asset(1, "BTC", 1, market.price("BTCUSDT")), Scenario.asset(2, "X", 2, market.price("XUSDT"))))
        }
        fun paper() = KrPaper(market, cmc, store, clock)
    }

    @Test
    fun `entry is taken live once and a restart does not open it again`() = runTest {
        val env = Env(retestClose + 15_000)
        env.paper().cycle(BotMode.FULL)
        val s = env.store.state!!
        assertEquals(1, s.book.positions.size)
        val p = s.book.positions.single()
        assertEquals("XUSDT", p.symbol)
        assertEquals(env.market.price("XUSDT") * 1.0001, p.entryPrice, 1e-9)
        assertEquals(2, s.scan!!.included)
        // Yeniden başlatma: aynı kapanış yeniden taranırsa (lastScanBar sıfırlanmış olsa bile) ikinci işlem yok.
        env.store.state = s.copy(lastScanBar = 0)
        env.market.clock += 20_000
        env.paper().cycle(BotMode.FULL)
        assertEquals(1, env.store.state!!.book.positions.size + env.store.state!!.book.trades.size)
    }

    @Test
    fun `decision time missed while the bot was not running is not traded`() = runTest {
        val env = Env(retestClose + 6 * 60_000)
        env.paper().cycle(BotMode.FULL)
        val s = env.store.state!!
        assertEquals(0, s.book.positions.size)
        assertTrue(s.scan!!.funnel.keys.any { it.contains(Rejection.LATE.title) })
    }

    @Test
    fun `protect-only mode keeps watching the stop but opens nothing`() = runTest {
        val env = Env(retestClose + 15_000)
        env.paper().cycle(BotMode.PROTECT_ONLY)
        assertEquals(0, env.store.state!!.book.positions.size)
        assertEquals(retestClose + 15_000, env.store.state!!.lastMonitorAt)
    }

    @Test
    fun `live monitoring closes at the target and keeps the fees fixed at entry`() = runTest {
        val env = Env(retestClose + 15_000)
        env.paper().cycle(BotMode.FULL)
        val entry = env.store.state!!.book.positions.single()
        // Ücret tablosu sonradan değişse de işlem kaydı giriş anındaki ücretle kapanır.
        env.market.infos = listOf(Scenario.info("XUSDT", fee = 0.01), Scenario.info("BTCUSDT", fee = 0.01))
        env.store.state = env.store.state!!.copy(termsAt = 0)
        val paper = env.paper()
        var steps = 0
        while (env.store.state!!.book.trades.isEmpty() && steps < 240) {
            env.market.clock += 60_000
            paper.cycle(BotMode.FULL)
            steps++
        }
        val t = env.store.state!!.book.trades.single()
        assertEquals(ExitReason.TARGET, t.reason)
        assertEquals(ExitSource.LIVE, t.source)
        assertEquals(entry.exitLeg, t.exitLeg)
        assertEquals(0.0005, t.exitLeg.fee, 0.0)
        assertTrue(env.store.state!!.untracked.isEmpty())
        // Hedef, giriş sonrası 1 dakikalık mum aralığında tespit edildi; çıkış zamanı gün başına yazılmadı.
        assertTrue(t.exitWindowTo - t.exitWindowFrom <= 60_000 && t.exitTime > t.entryTime)
    }

    @Test
    fun `a monitoring gap is recorded and an exit inside it is marked as backfilled`() = runTest {
        val env = Env(retestClose + 15_000)
        env.paper().cycle(BotMode.FULL)
        env.market.clock = retestClose + 3 * 60 * 60_000L
        env.paper().cycle(BotMode.FULL)
        val s = env.store.state!!
        assertEquals(1, s.untracked.size)
        val t = s.book.trades.single()
        assertEquals(ExitSource.BACKFILLED, t.source)
        assertTrue(t.exitTime < env.market.clock && t.exitTime >= t.entryTime)
        assertTrue(s.untracked.single().let { it.from <= t.exitWindowFrom && t.exitWindowTo <= it.to })
    }

    @Test
    fun `stale CMC list stops new entries but not protection`() = runTest {
        val env = Env(retestClose + 15_000)
        val old = KrPaper.LIST_VALIDITY + M15
        val staleCmc = CmcSource {
            CmcList(env.market.clock - old, env.market.clock, listOf(Scenario.asset(1, "BTC", 1, env.market.price("BTCUSDT")), Scenario.asset(2, "X", 2, env.market.price("XUSDT"))))
        }
        KrPaper(env.market, staleCmc, env.store, env.clock).cycle(BotMode.FULL)
        val s = env.store.state!!
        assertEquals(0, s.book.positions.size)
        assertTrue(s.scan!!.funnel.keys.any { it.contains(Rejection.LIST_STALE.title) })
    }
}
