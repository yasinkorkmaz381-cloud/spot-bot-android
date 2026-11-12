package com.spotbot.core.rs1

import com.spotbot.core.model.Candle
import com.spotbot.core.rs1.FakeMarket.Companion.T0
import com.spotbot.core.rs1.FakeMarket.Companion.info
import com.spotbot.core.rs1.FakeMarket.Companion.series
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android backtest yolu (Rs1Backtest.prepare + run) ile araştırma motoru aynı girdilerle aynı sonucu verir. */
class Rs1BacktestTest {
    private val days = 400
    private val data = mapOf(
        BTC to series(days) { 0.002 },
        "AAAUSDT" to series(days) { if (it == 300) -0.25 else 0.01 },
        "BBBUSDT" to series(days) { if (it % 30 == 15) -0.12 else 0.008 },
        "CCCUSDT" to series(days) { if (it % 40 == 25) -0.12 else 0.009 },
        "THINUSDT" to series(days, volume = 2e6) { 0.012 },
    )
    private val start = T0 + 200 * DAY_MS
    private val end = T0 + days * DAY_MS
    private val cfg = V5_RULES.copy(exitTrailing = true)

    private fun market() = FakeMarket(data.keys.map { info(it) }, data, clock = end + HOUR_MS)

    @Test
    fun `the Android path equals the research engine with 4H stop resolution`() = runBlocking {
        val m = market()
        val prepared = Rs1Backtest.prepare(m, start, end, BacktestMode.RESEARCH, cfg)
        val viaApp = Rs1Backtest.run(prepared, cfg, start, end)
        // Araştırma: aynı 1D ve 4H verisi, aynı ücretler, doğrudan motor.
        val h4 = data.mapValues { (_, l) -> l.filter { it.openTime >= start && it.openTime + DAY_MS <= end }.flatMap { m.fourHours(it) } }
        val series = data.mapValues { (s, l) -> DailySeries(s, l.filter { it.openTime + DAY_MS <= end }, cfg) }
        val research = Rs1Engine(series, cfg, data.keys.associateWith { 0.0005 }, h4.filterKeys { it in prepared.intraday }).run(start, end, closeAtEnd = false)
        assertTrue(research.trades.size >= 3)
        assertEquals(research.trades, viaApp.trades)
        assertEquals(research.open, viaApp.open)
        assertEquals(research.endEquity, viaApp.endEquity, 1e-9)
        assertTrue(viaApp.trades.filter { it.reason == ExitReason.STOP || it.reason == ExitReason.TRAIL_STOP || it.reason == ExitReason.STOP_GAP }.all { it.intradayResolved })
        // Likit olmayan paritenin 4H verisi indirilmez.
        assertFalse("THINUSDT" in prepared.intraday)
        assertTrue("AAAUSDT" in prepared.intraday && BTC in prepared.intraday)
    }

    @Test
    fun `the approximate mode uses daily bars only and is labelled as such`() = runBlocking {
        val m = market()
        val prepared = Rs1Backtest.prepare(m, start, end, BacktestMode.APPROXIMATE, cfg)
        assertTrue(prepared.intraday.isEmpty())
        val r = Rs1Backtest.run(prepared, cfg, start, end)
        val series = data.mapValues { (s, l) -> DailySeries(s, l.filter { it.openTime + DAY_MS <= end }, cfg) }
        assertEquals(Rs1Engine(series, cfg, data.keys.associateWith { 0.0005 }).run(start, end, closeAtEnd = false).trades, r.trades)
        assertTrue(r.trades.none { it.intradayResolved })
        assertTrue(BacktestMode.APPROXIMATE.description.contains("eşdeğer DEĞİLDİR"))
    }

    @Test
    fun `a stop raised at the daily close is not applied to that same day's earlier low`() {
        // Gün d: önce %3 düşer (eski stopa değmez), sonra %15 yükselerek kapanır. Kapanışta stop, günün düşüğünün
        // üstüne yükselir. Bu yeni stop d gününe uygulanırsa yanlış çıkış olurdu; doğru davranış: d+1'den itibaren geçerli.
        val d = 280
        val coin = series(days) { 0.01 }.toMutableList()
        val prev = coin[d - 1].close
        coin[d] = Candle(coin[d].openTime, prev, prev * 1.16, prev * 0.97, prev * 1.15, coin[d].volume, coin[d].closeTime, coin[d].quoteVolume)
        var p = prev * 1.15
        for (i in d + 1 until days) {
            val o = p; val c = p * 1.01; p = c
            coin[i] = Candle(coin[i].openTime, o, c * 1.01, o * 0.99, c, coin[i].volume, coin[i].closeTime, coin[i].quoteVolume)
        }
        val u = mapOf(BTC to DailySeries(BTC, data.getValue(BTC), cfg), "AAAUSDT" to DailySeries("AAAUSDT", coin, cfg))
        val r = Rs1Engine(u, cfg).run(start, end, closeAtEnd = false)
        val day = T0 + d * DAY_MS
        val pos = (r.open.filter { it.symbol == "AAAUSDT" }.map { it.entryTime to it.stopChanges } + r.trades.filter { it.symbol == "AAAUSDT" }.map { it.entryTime to it.stopChanges })
            .single { (entry, _) -> entry < day && (r.trades.none { it.entryTime == entry } || r.trades.first { it.entryTime == entry }.exitTime > day) }
        val raised = pos.second.single { it.time == day + DAY_MS }
        assertTrue("kapanışta yükseltilen stop d gününün düşüğünün üstünde olmalı", raised.stop > coin[d].low)
        assertTrue("d gününde çıkış olmamalı", r.trades.none { it.symbol == "AAAUSDT" && it.exitTime == day })
    }
}
