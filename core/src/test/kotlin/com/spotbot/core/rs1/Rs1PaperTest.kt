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

class Rs1PaperTest {
    private val cfg = V5_RULES
    private val days = 400
    private val crashDay = 300
    private val data = mapOf(
        BTC to series(days) { 0.002 },
        "AAAUSDT" to series(days) { if (it == crashDay) -0.25 else 0.01 },
        "BBBUSDT" to series(days) { if (it in 330..345) -0.01 else if (it % 30 == 15) -0.12 else 0.008 },
        "CCCUSDT" to series(days) { if (it % 40 == 25) -0.12 else 0.009 },
        // Periyodik sert düşüşler: stop, yeniden giriş ve haftalık çıkış üretir.
        "DDDUSDT" to series(days) { if (it % 35 == 20) -0.14 else 0.012 },
    )
    private val start = T0 + 200 * DAY_MS
    private val end = T0 + days * DAY_MS

    private fun market() = FakeMarket(data.keys.map { info(it) }, data)

    private fun permissive(monday: Long, at: Long) = WeeklySnapshot(monday, at, data.keys.associateWith { LiveCheck(0.02, 1e9, 1e9) })

    /** Paper trading'i gün gün ilerletir: pazartesi 00:20'de canlı ölçüm, her gün 12:00'de güncelleme. */
    private fun simulate(m: FakeMarket, paper: Rs1Paper, until: Long, skipMonday: Long? = null) = runBlocking {
        var day = start
        while (day < until) {
            if (isWeekday(day, 1) && day != skipMonday) {
                m.clock = day + 20 * 60_000L
                assertTrue(paper.recordSnapshot(permissive(day, m.clock)))
            }
            m.clock = day + 12 * HOUR_MS
            paper.refresh()
            day += DAY_MS
        }
        m.clock = until + 60_000L
        paper.refresh()
    }

    @Test
    fun `paper trading makes exactly the backtest decisions on the same data`() {
        val m = market()
        val paper = Rs1Paper(m, InMemoryPaperStore(), TimeSource { m.clock }, cfg)
        m.clock = start + 60_000L
        runBlocking { paper.refresh() }
        simulate(m, paper, end)
        val p = paper.state.value!!
        assertNull(p.lastError)
        // 1) Paper sonucu = aynı motorun kaydedilmiş girdilerle (ölçümler, 1H mumlar, ücretler) yeniden çalıştırılması.
        val series = data.mapValues { (s, c) -> DailySeries(s, c, cfg) }
        val replay = Rs1Engine(series, cfg, p.feeTable(), p.intraday).run(start, end, SnapshotGate(p.snapshots, cfg), closeAtEnd = false)
        assertEquals(replay.trades, p.result!!.trades)
        assertEquals(replay.open, p.result!!.open)
        // 2) Backtest ile aynı kararlar ve fiyatlar; tek fark gün içi çözümün işareti (paper 1H, backtest günlük).
        val bt = Rs1Engine(series, cfg).run(start, end, BacktestGate, closeAtEnd = false)
        assertTrue("işlem oluşmalı: ${bt.trades.size}", bt.trades.size >= 3)
        assertEquals(bt.trades.map { it.copy(intradayResolved = false) }, p.result!!.trades.map { it.copy(intradayResolved = false) })
        assertEquals(bt.open.map { it.key to it.stop }, p.result!!.open.map { it.key to it.stop })
        assertEquals(bt.weekly.map { it.time to it.entered }, p.result!!.weekly.map { it.time to it.entered })
    }

    @Test
    fun `an intraday stop is recorded before the day closes, at the same price as the daily result`() {
        val m = market()
        val paper = Rs1Paper(m, InMemoryPaperStore(), TimeSource { m.clock }, cfg)
        m.clock = start + 60_000L
        runBlocking { paper.refresh() }
        val crash = T0 + crashDay * DAY_MS
        simulate(m, paper, crash)
        assertTrue("AAA çöküşten önce portföyde olmalı", paper.state.value!!.result!!.open.any { it.symbol == "AAAUSDT" })
        m.clock = crash + 10 * HOUR_MS // düşük 6. saatte görüldü, gün henüz kapanmadı
        runBlocking { paper.refresh() }
        val intraday = paper.state.value!!.result!!.trades.single { it.symbol == "AAAUSDT" && it.exitTime == crash }
        val bt = Rs1Engine(data.mapValues { (s, c) -> DailySeries(s, c, cfg) }, cfg).run(start, end, BacktestGate)
        assertEquals(bt.trades.single { it.symbol == "AAAUSDT" && it.exitTime == crash }, intraday.copy(intradayResolved = false))
        assertTrue(intraday.intradayResolved)
    }

    @Test
    fun `a missed weekly evaluation blocks entries that week and is reported as an access problem`() {
        val m = market()
        val paper = Rs1Paper(m, InMemoryPaperStore(), TimeSource { m.clock }, cfg)
        val firstMonday = (0..7).map { start + it * DAY_MS }.first { isWeekday(it, 1) }
        m.clock = start + 60_000L
        runBlocking { paper.refresh() }
        simulate(m, paper, firstMonday + 2 * DAY_MS, skipMonday = firstMonday)
        val w = paper.state.value!!.result!!.weekly.single { it.time == firstMonday }
        assertTrue(w.entered.isEmpty())
        assertTrue(w.noEntryReason!!.lowercase().contains("canlı değerlendirme"))
    }

    @Test
    fun `restarting the app keeps positions and history`() {
        val m = market()
        val store = InMemoryPaperStore()
        val paper = Rs1Paper(m, store, TimeSource { m.clock }, cfg)
        m.clock = start + 60_000L
        runBlocking { paper.refresh() }
        simulate(m, paper, T0 + 290 * DAY_MS)
        val before = paper.state.value!!
        assertTrue(before.result!!.open.isNotEmpty())
        val restarted = Rs1Paper(m, store, TimeSource { m.clock }, cfg)
        assertEquals(before, restarted.state.value)
        val again = runBlocking { restarted.refresh() }
        assertEquals(before.result!!.trades, again.result!!.trades)
        assertEquals(before.result!!.open.map { it.key }, again.result!!.open.map { it.key })
        assertEquals(before.startedAt, again.startedAt)
    }

    @Test
    fun `live checks decide entries - wide spread or thin book blocks the candidate`() {
        val m = market()
        val paper = Rs1Paper(m, InMemoryPaperStore(), TimeSource { m.clock }, cfg)
        val firstMonday = (0..7).map { start + it * DAY_MS }.first { isWeekday(it, 1) }
        m.clock = start + 60_000L
        runBlocking {
            paper.refresh()
            m.clock = firstMonday + 20 * 60_000L
            val bad = data.keys.associateWith { if (it == "AAAUSDT") LiveCheck(0.40, 1e9, 1e9) else LiveCheck(0.02, 100.0, 1e9) }
            paper.recordSnapshot(WeeklySnapshot(firstMonday, m.clock, bad))
            m.clock = firstMonday + 2 * HOUR_MS
            paper.refresh()
        }
        val w = paper.state.value!!.result!!.weekly.single { it.time == firstMonday }
        assertTrue(w.entered.isEmpty())
        assertEquals(listOf(Rs1Condition.SPREAD), w.liveRejections["AAAUSDT"])
        assertEquals(listOf(Rs1Condition.DEPTH), w.liveRejections["BBBUSDT"])
    }

    @Test
    fun `snapshots outside the Monday window are refused`() = runBlocking {
        val m = market()
        val paper = Rs1Paper(m, InMemoryPaperStore(), TimeSource { m.clock }, cfg)
        val monday = (0..7).map { start + it * DAY_MS }.first { isWeekday(it, 1) }
        m.clock = start
        paper.refresh()
        assertTrue(!paper.recordSnapshot(permissive(monday, monday + 5 * 60_000L))) // 00:15 öncesi
        assertTrue(!paper.recordSnapshot(permissive(monday, monday + 5 * HOUR_MS))) // 04:00 sonrası
        assertTrue(paper.recordSnapshot(permissive(monday, monday + HOUR_MS)))
        assertTrue(!paper.recordSnapshot(permissive(monday, monday + 2 * HOUR_MS))) // haftada bir kez
    }

    @Test
    fun `entries fill at the live order book price recorded at evaluation, not at the stale open`() {
        val m = market()
        val paper = Rs1Paper(m, InMemoryPaperStore(), TimeSource { m.clock }, cfg)
        val monday = (0..7).map { start + it * DAY_MS }.first { isWeekday(it, 1) }
        runBlocking {
            m.clock = start + 60_000L; paper.refresh()
            m.clock = monday + 2 * HOUR_MS
            val vwap = 1.0 // gerçek fiyattan çok farklı: dolumun buradan geldiği açıkça görülsün
            val checks = data.keys.associateWith { LiveCheck(0.02, 1e9, 1e9, askVwap = vwap * 101, mid = 100.0, takerFee = 0.0008, quantityStep = 0.01, minNotional = 1.0) }
            assertTrue(paper.recordSnapshot(WeeklySnapshot(monday, m.clock, checks)))
            m.clock = monday + 3 * HOUR_MS; paper.refresh()
        }
        val p = paper.state.value!!.result!!.open
        assertTrue(p.isNotEmpty())
        for (pos in p) {
            assertEquals(101.0 * (1 + cfg.costs.slippage), pos.entryPrice, 1e-9)
            assertEquals(monday + 2 * HOUR_MS, pos.entryTime)
            assertEquals(0.0, Math.round(pos.quantity * 100) - pos.quantity * 100, 1e-6) // miktar adımı 0,01
            assertEquals(pos.entryPrice * pos.quantity * 0.0008, pos.entryFee, 1e-9) // parite ücreti
        }
    }

    @Test
    fun `untracked intervals are recorded when the app was not running`() = runBlocking {
        val m = market()
        val paper = Rs1Paper(m, InMemoryPaperStore(), TimeSource { m.clock }, cfg)
        m.clock = start + HOUR_MS; paper.refresh()
        m.clock = start + 2 * HOUR_MS; paper.refresh() // 1 saat > 20 dk: takip edilmeyen aralık
        m.clock = start + 30 * HOUR_MS; paper.refresh()
        val s = paper.state.value!!
        assertEquals(listOf(Interval(start + HOUR_MS, start + 2 * HOUR_MS), Interval(start + 2 * HOUR_MS, start + 30 * HOUR_MS)), s.untracked)
        assertTrue(s.untrackedAt(start + 10 * HOUR_MS))
        assertTrue(!s.untrackedAt(start + 31 * HOUR_MS))
    }
}
