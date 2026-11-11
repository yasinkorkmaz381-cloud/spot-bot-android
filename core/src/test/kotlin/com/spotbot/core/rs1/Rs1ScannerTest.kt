package com.spotbot.core.rs1

import com.spotbot.core.rs1.FakeMarket.Companion.T0
import com.spotbot.core.rs1.FakeMarket.Companion.info
import com.spotbot.core.rs1.FakeMarket.Companion.series
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rs1ScannerTest {
    private val cfg = Rs1Config()
    private val days = 300
    private val data = mapOf(
        BTC to series(days) { 0.002 },
        "AAAUSDT" to series(days) { 0.010 },
        "BBBUSDT" to series(days) { 0.008 },
        "CCCUSDT" to series(days) { 0.006 },
        "DOWNUSDT" to series(days) { -0.004 },
        "THINUSDT" to series(days, volume = 2e6) { 0.012 },
        "NEWUSDT" to series(days) { 0.015 }.drop(200),
        "STUSDT" to series(days) { 0.012 },
        "USDCUSDT" to series(days) { 0.0 },
        "BTC3LUSDT" to series(days) { 0.006 },
        "TSLAXUSDT" to series(days) { 0.012 },
    )
    /** Pazartesi 00:20 UTC (gün 294). */
    private val monday = T0 + 294 * DAY_MS
    private val now = monday + 20 * 60_000L

    private fun market() = FakeMarket(
        data.keys.map {
            when (it) {
                "STUSDT" -> info(it, st = true)
                "TSLAXUSDT" -> info(it, listOf("Tokenized Stocks"))
                "AAAUSDT" -> info(it, listOf("MEME"))
                else -> info(it, listOf("Main"))
            }
        },
        data, clock = now,
    )

    @Test
    fun `discovers all pairs, excludes products and applies the RS-1 rules`() = runBlocking {
        val m = market()
        val st = Rs1Scanner(m, clock = TimeSource { m.clock }, cfg = cfg).scan()
        assertNull(st.lastError)
        val r = st.result!!
        assertEquals(11, r.totalPairs)
        assertEquals(3, r.stages[ScanStage.PRODUCT.title]) // USDC, BTC3L, TSLAX
        assertEquals(1, r.stages[ScanStage.NOT_TRADING.title]) // ST
        assertEquals(1, r.stages[ScanStage.DATA.title]) // NEW: 100 gün
        assertEquals(1, r.stages[ScanStage.VOLUME.title]) // THIN
        assertEquals(1, r.stages[ScanStage.TREND.title]) // DOWN
        assertTrue(r.btc.open)
        assertEquals(listOf("AAAUSDT", "BBBUSDT"), r.candidates)
        assertNull(r.noTradeReason)
        assertTrue(r.top.first { it.symbol == "AAAUSDT" }.sectors.contains("MEME"))
    }

    @Test
    fun `scanner candidates equal the engine's choice on the same Monday`() = runBlocking {
        val m = market()
        val r = Rs1Scanner(m, clock = TimeSource { m.clock }, cfg = cfg).scan().result!!
        val tradable = data.filterKeys { it !in setOf("STUSDT", "USDCUSDT", "BTC3LUSDT", "TSLAXUSDT") }
        val series = tradable.mapValues { (s, c) -> DailySeries(s, c.filter { it.openTime + DAY_MS <= monday }, cfg) }
        val btcR30 = series.getValue(BTC).let { it.ret(it.lastCompleted(monday), 30) }
        val engineRank = Rs1Rules.rank(series.values.map { Rs1Rules.coin(it, monday, btcR30, cfg) }).map { it.symbol }
        assertEquals(engineRank.take(2), r.candidates)
        assertEquals(engineRank, r.top.filter { it.eligible }.map { it.symbol })
    }

    @Test
    fun `a thin order book blocks candidates and is counted as a depth failure`() = runBlocking {
        val m = market().apply { depthUsdt = 500.0 } // gerekli: 3 × %25 × 1000 = 750 USDT
        val r = Rs1Scanner(m, clock = TimeSource { m.clock }, cfg = cfg).scan().result!!
        assertTrue(r.candidates.isEmpty())
        assertTrue((r.stages[ScanStage.DEPTH.title] ?: 0) >= 2)
        assertEquals("Bütün şartları geçen coin yok", r.noTradeReason)
        assertTrue(r.top.first { it.symbol == "AAAUSDT" }.failed == listOf(Rs1Condition.DEPTH))
    }

    @Test
    fun `wide spreads fail the 0_15 percent rule`() = runBlocking {
        val m = market().apply { spreadPercent = 0.30 }
        val r = Rs1Scanner(m, clock = TimeSource { m.clock }, cfg = cfg).scan().result!!
        assertTrue(r.candidates.isEmpty())
        assertTrue((r.stages[ScanStage.SPREAD.title] ?: 0) >= 3)
    }

    @Test
    fun `new listings and delistings are detected and daily candles are cached`() = runBlocking {
        val m = market()
        val scanner = Rs1Scanner(m, clock = TimeSource { m.clock }, cfg = cfg)
        scanner.scan()
        val calls = m.calls
        scanner.scan()
        assertEquals("aynı gün 1D mumları yeniden indirilmez", calls, m.calls)
        m.info = m.info.filter { it.symbol != "CCCUSDT" } + info("ZZZUSDT")
        val r = scanner.scan().result!!
        assertEquals(listOf("ZZZUSDT"), r.newListings)
        assertEquals(listOf("CCCUSDT"), r.delisted)
    }

    @Test
    fun `the bot records the weekly live check in the Monday window and paper trading enters`() = runBlocking {
        val m = market()
        val scanner = Rs1Scanner(m, clock = TimeSource { m.clock }, cfg = cfg)
        val paper = Rs1Paper(m, InMemoryPaperStore(), TimeSource { m.clock }, cfg)
        val bot = Rs1Bot(scanner, paper, TimeSource { m.clock }, cfg)
        m.clock = monday - 3 * DAY_MS
        bot.cycle()
        assertTrue(paper.state.value!!.snapshots.isEmpty())
        m.clock = monday + 30 * 60_000L
        bot.cycle()
        assertTrue(bot.status.value.weeklyRecorded)
        m.clock = monday + 2 * HOUR_MS
        paper.refresh()
        val w = paper.state.value!!.result!!.weekly.single { it.time == monday }
        assertEquals(listOf("AAAUSDT", "BBBUSDT"), w.entered)
        assertNull(bot.status.value.lastError)
    }

    @Test
    fun `next weekly evaluation is the coming Monday 00_15 UTC`() {
        assertEquals(monday + 15 * 60_000L, Rs1Scanner.nextWeekly(monday - DAY_MS))
        assertEquals(monday + 7 * DAY_MS + 15 * 60_000L, Rs1Scanner.nextWeekly(monday + 20 * 60_000L))
    }
}
