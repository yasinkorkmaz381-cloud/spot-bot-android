package com.spotbot.core.rs1

import com.spotbot.core.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class Rs1EngineTest {
    private val cfg = V5_RULES
    /** 2024-01-01 pazartesi. */
    private val t0 = 1_704_067_200_000L
    private val days = 400

    /** Günlük getirisi [drift(i)] olan düzgün seri; açılış = önceki kapanış, aralık fiyatın ±%1'i. */
    private fun series(symbol: String, volume: Double = 20e6, drift: (Int) -> Double): List<Candle> {
        var p = 100.0
        return (0 until days).map { i ->
            val o = p
            val c = p * exp(drift(i))
            p = c
            val t = t0 + i * DAY_MS
            Candle(t, o, maxOf(o, c) * 1.01, minOf(o, c) * 0.99, c, volume / c, t + DAY_MS, volume)
        }
    }

    private fun data(vararg pairs: Pair<String, List<Candle>>) = pairs.associate { (s, c) -> s to DailySeries(s, c, cfg) }
    private val start = t0 + 200 * DAY_MS
    private val end = t0 + days * DAY_MS

    private val btcUp = series(BTC) { 0.002 }
    private val strong = series("AAAUSDT") { 0.01 }

    @Test
    fun `entries happen only on Mondays at the open, within position and capital limits`() {
        val u = data(BTC to btcUp, "AAAUSDT" to strong, "BBBUSDT" to series("BBBUSDT") { 0.008 }, "CCCUSDT" to series("CCCUSDT") { 0.006 })
        val r = Rs1Engine(u, cfg).run(start, end, closeAtEnd = false)
        assertTrue(r.open.isNotEmpty())
        val all = r.trades.map { it.entryTime } + r.open.map { it.entryTime }
        assertTrue(all.all { isWeekday(it, 1) })
        assertTrue(r.open.size <= 2)
        // İlk girişler: en güçlü iki aday (AAA, BBB), CCC değil.
        val first = r.weekly.first { it.entered.isNotEmpty() }
        assertEquals(listOf("AAAUSDT", "BBBUSDT"), first.entered)
        for (p in r.open) {
            val s = u.getValue(p.symbol)
            val i = s.at(p.entryTime)
            assertEquals(s.open[i] * (1 + cfg.costs.markup), p.entryPrice, 1e-9)
            assertTrue(p.entryPrice * p.quantity <= 0.25 * 1_000 * 1.0001)
        }
        assertTrue(r.open.sumOf { it.entryPrice * it.quantity } <= 0.50 * 1_000 * 1.0001)
    }

    @Test
    fun `BTC filter closed means no entries and the reason is recorded`() {
        val btcDown = series(BTC) { -0.002 }
        val r = Rs1Engine(data(BTC to btcDown, "AAAUSDT" to strong), cfg).run(start, end)
        assertEquals(0, r.trades.size)
        assertTrue(r.weekly.isNotEmpty() && r.weekly.all { it.entered.isEmpty() && it.noEntryReason!!.contains("BTC") })
    }

    @Test
    fun `protective stop exits at the stop price, a gap exits at the open`() {
        val crashDay = 260
        val coin = series("AAAUSDT") { if (it == crashDay) -0.30 else 0.01 }
        val u = data(BTC to btcUp, "AAAUSDT" to coin)
        val r = Rs1Engine(u, cfg).run(start, end)
        val t = r.trades.first { it.exitTime == t0 + crashDay * DAY_MS }
        // Açılış önceki kapanış olduğu için boşluk yok; düşük stopu geçti → stop fiyatından çıkış.
        assertTrue(t.reason == ExitReason.STOP || t.reason == ExitReason.TRAIL_STOP)
        val s = u.getValue("AAAUSDT")
        assertTrue(s.low[s.at(t.exitTime)] <= t.exitPrice / (1 - cfg.costs.markup) + 1e-9)

        // Boşluk: açılış stopun altında.
        val gapCoin = coinWithGap(crashDay)
        val g = Rs1Engine(data(BTC to btcUp, "AAAUSDT" to gapCoin), cfg).run(start, end).trades.first { it.exitTime == t0 + crashDay * DAY_MS }
        assertEquals(ExitReason.STOP_GAP, g.reason)
        assertEquals(gapCoin[crashDay].open * (1 - cfg.costs.markup), g.exitPrice, 1e-9)
        // Boşlukta çıkış stopun çok altında olur: kayıp stop mesafesini (≈ 3 ATR ≈ %7) aşar.
        assertTrue(g.exitPrice < gapCoin[crashDay - 1].close * 0.7)
    }

    private fun coinWithGap(day: Int): List<Candle> = series("AAAUSDT") { 0.01 }.mapIndexed { i, c ->
        if (i < day) c else {
            val f = 0.6
            Candle(c.openTime, c.open * f, c.high * f, c.low * f, c.close * f, c.volume, c.closeTime, c.quoteVolume)
        }
    }

    @Test
    fun `trailing stop only moves up and exits are deterministic`() {
        val coin = series("AAAUSDT") { if (it in 240..300) 0.02 else if (it in 301..330) -0.02 else 0.005 }
        val u = data(BTC to btcUp, "AAAUSDT" to coin)
        val a = Rs1Engine(u, cfg).run(start, end)
        val b = Rs1Engine(u, cfg).run(start, end)
        assertEquals(a.trades, b.trades)
        assertTrue(a.trades.any { it.reason == ExitReason.TRAIL_STOP || it.reason == ExitReason.EMA50 || it.reason == ExitReason.WEEKLY })
        val open = Rs1Engine(u, cfg).run(start, t0 + 290 * DAY_MS, closeAtEnd = false).open
        open.forEach { assertTrue(it.stop >= it.initialStop) }
    }

    @Test
    fun `two closes below EMA50 exit at the next open`() {
        // Uzun yükselişten sonra yavaş düşüş: stop tetiklenmeden önce EMA50 altına iner (iz süren stop 3 ATR uzakta).
        val coin = series("AAAUSDT") { if (it < 280) 0.01 else -0.004 }
        val r = Rs1Engine(data(BTC to btcUp, "AAAUSDT" to coin), cfg).run(start, end)
        val ema = r.trades.filter { it.reason == ExitReason.EMA50 }
        val s = DailySeries("AAAUSDT", coin, cfg)
        for (t in ema) {
            val i = s.lastCompleted(t.exitTime)
            assertTrue(s.close[i] < s.emaExit[i] && s.close[i - 1] < s.emaExit[i - 1])
            assertEquals(s.open[s.at(t.exitTime)] * (1 - cfg.costs.markup), t.exitPrice, 1e-9)
        }
    }

    @Test
    fun `weekly evaluation exits a coin that lost eligibility`() {
        // 7 günlük getiri negatife döner ama EMA50 ve stop tetiklenmez.
        val coin = series("AAAUSDT") { if (it in 285..300) -0.002 else 0.01 }
        val r = Rs1Engine(data(BTC to btcUp, "AAAUSDT" to coin), cfg).run(start, end)
        assertTrue(r.trades.any { it.reason == ExitReason.WEEKLY && isWeekday(it.exitTime, 1) })
    }

    @Test
    fun `accounting is exact and the risk per trade is 0_5 percent of equity`() {
        val u = data(BTC to btcUp, "AAAUSDT" to strong, "BBBUSDT" to series("BBBUSDT") { if (it % 50 == 49) -0.15 else 0.008 })
        val r = Rs1Engine(u, cfg).run(start, end)
        assertTrue(r.trades.size >= 2)
        assertEquals(1_000.0 + r.trades.sumOf { it.netPnl }, r.endEquity, 1e-6)
        // Stop fiyatından çıkan işlemde kayıp ≈ 1R (yalnız boyut sınırına takılmadıysa).
        r.trades.filter { it.reason == ExitReason.STOP }.forEach { assertTrue(it.r > -1.05) }
    }

    @Test
    fun `decisions before a cutoff do not depend on later data (no look-ahead)`() {
        val u = data(BTC to btcUp, "AAAUSDT" to strong, "BBBUSDT" to series("BBBUSDT") { if (it > 320) -0.02 else 0.009 })
        val cut = t0 + 300 * DAY_MS
        val full = Rs1Engine(u, cfg).run(start, end)
        val truncated = Rs1Engine(u.mapValues { (s, d) -> DailySeries(s, d.bars.filter { it.openTime + DAY_MS <= cut }, cfg) }, cfg).run(start, cut, closeAtEnd = false)
        val before = full.trades.filter { it.exitTime < cut }
        assertEquals(before, truncated.trades.filter { it.exitTime < cut })
        assertEquals(full.weekly.filter { it.time <= cut - DAY_MS }, truncated.weekly.filter { it.time <= cut - DAY_MS })
    }

    @Test
    fun `new listings are not traded before 150 days of history`() {
        val young = series("NEWUSDT") { 0.015 }.drop(150)
        val r = Rs1Engine(data(BTC to btcUp, "NEWUSDT" to young), cfg).run(start, end)
        val listed = young.first().openTime
        val newTrades = r.trades.filter { it.symbol == "NEWUSDT" }
        assertTrue(newTrades.isNotEmpty())
        assertTrue(newTrades.all { it.entryTime >= listed + 150 * DAY_MS })
    }

    @Test
    fun `thin volume, weak relative strength and wide ranges are not bought`() {
        val thin = series("THINUSDT", volume = 2e6) { 0.012 }
        val weak = series("WEAKUSDT") { 0.0025 } // BTC'ye göre 30 günde ~1,5 puan avantaj
        val r = Rs1Engine(data(BTC to btcUp, "THINUSDT" to thin, "WEAKUSDT" to weak), cfg).run(start, end)
        assertTrue(r.trades.none { it.symbol == "THINUSDT" || it.symbol == "WEAKUSDT" })
        val check = Rs1Rules.coin(DailySeries("WEAKUSDT", weak, cfg), t0 + 300 * DAY_MS, DailySeries(BTC, btcUp, cfg).ret(299, 30), cfg)
        assertTrue(Rs1Condition.RELATIVE_STRENGTH in check.failed)
        assertFalse(Rs1Condition.TREND in check.failed)
    }

    @Test
    fun `a daily loss of 1_5 percent blocks new entries that Monday`() {
        // Pazartesi öncesi gün büyük düşüş: pozisyon zarara girer (stop tetiklenmeden), özsermaye %1,5+ düşer.
        val cfgLoose = cfg.copy(initialStopAtr = 30.0, trailAtr = 30.0, riskPercent = 50.0, maxPositionPercent = 25.0)
        val sunday = (0 until days).first { i -> i > 230 && isWeekday(t0 + (i + 1) * DAY_MS, 1) }
        val coin = series("AAAUSDT") { if (it == sunday) -0.12 else 0.01 }
        val u = data(BTC to btcUp, "AAAUSDT" to coin, "BBBUSDT" to series("BBBUSDT") { 0.007 })
        val r = Rs1Engine(u, cfgLoose).run(start, end)
        val monday = t0 + (sunday + 1) * DAY_MS
        val w = r.weekly.firstOrNull { it.time == monday }
        assertTrue(w!!.noEntryReason!!.contains("Günlük kayıp"))
        assertTrue(w.entered.isEmpty())
    }

    @Test
    fun `weekday helper matches the calendar`() {
        assertTrue(isWeekday(t0, 1)) // 2024-01-01 pazartesi
        assertFalse(isWeekday(t0 + DAY_MS, 1))
        assertTrue(isWeekday(t0 + 7 * DAY_MS, 1))
        assertTrue(abs(cfg.costs.markup - 0.00125) < 1e-12)
    }

    @Test
    fun `an intraday gap through the stop exits at the 4H open, not at the stop`() {
        val crashDay = 260
        val coin = series("AAAUSDT") { if (it == crashDay) -0.30 else 0.01 }
        val day = coin[crashDay]
        // 4H mumlar: ilk dört mum normal, beşinci mum stopun çok altında açılır (gün içi boşluk).
        val h4 = (0 until 6).map { k ->
            val t = day.openTime + k * 4 * HOUR_MS
            val px = if (k < 4) day.open else day.close
            Candle(t, px, px * 1.001, px * 0.999, px, 1.0, t + 4 * HOUR_MS, 1e6)
        }
        val u = data(BTC to btcUp, "AAAUSDT" to coin)
        val daily = Rs1Engine(u, cfg).run(start, end).trades.first { it.symbol == "AAAUSDT" && it.exitTime == day.openTime }
        val intra = Rs1Engine(u, cfg, intraday = mapOf("AAAUSDT" to h4)).run(start, end).trades.first { it.symbol == "AAAUSDT" && it.exitTime == day.openTime }
        assertTrue(daily.reason == ExitReason.STOP || daily.reason == ExitReason.TRAIL_STOP)
        assertEquals(ExitReason.STOP_GAP, intra.reason)
        assertTrue(intra.intradayResolved)
        assertEquals(day.close * (1 - cfg.costs.markup), intra.exitPrice, 1e-9)
        assertTrue("boşlukta gerçekleşme stop fiyatından kötü", intra.exitPrice < daily.exitPrice)
    }

    @Test
    fun `daily evaluation mode enters on non-Mondays and never duplicates a held coin`() {
        val u = data(BTC to btcUp, "AAAUSDT" to strong, "BBBUSDT" to series("BBBUSDT") { if (it % 30 == 15) -0.12 else 0.008 })
        val r = Rs1Engine(u, cfg.copy(entryEveryDay = true)).run(start, end, closeAtEnd = false)
        val entries = r.trades.map { it.entryTime } + r.open.map { it.entryTime }
        assertTrue(entries.any { !isWeekday(it, 1) })
        // Aynı anda aynı coinde iki pozisyon yok.
        val intervals = r.trades.map { Triple(it.symbol, it.entryTime, it.exitTime) } + r.open.map { Triple(it.symbol, it.entryTime, Long.MAX_VALUE) }
        for ((s, a, b) in intervals) assertEquals(1, intervals.count { it.first == s && it.second <= a && a < it.third })
        assertTrue(r.weekly.size > 7 * r.weekly.count { isWeekday(it.time, 1) } / 2)
    }

    @Test
    fun `stop history only rises and open positions are reported as unrealized`() {
        val u = data(BTC to btcUp, "AAAUSDT" to strong)
        val r = Rs1Engine(u, cfg).run(start, end, closeAtEnd = false)
        val p = r.open.single { it.symbol == "AAAUSDT" }
        assertTrue(p.stopChanges.size > 2)
        assertEquals(p.initialStop, p.stopChanges.first().stop, 1e-12)
        assertTrue(p.stopChanges.zipWithNext().all { (a, b) -> b.stop > a.stop && b.time > a.time })
        assertEquals(p.stop, p.stopChanges.last().stop, 1e-12)
        assertEquals(r.realizedPnl + r.unrealizedPnl, r.netPnl, 1e-6)
        assertTrue(r.unrealizedPnl > 0)
    }

    @Test
    fun `per-symbol fees and the double-cost scenario are applied`() {
        val u = data(BTC to btcUp, "AAAUSDT" to series("AAAUSDT") { if (it % 30 == 15) -0.12 else 0.01 })
        val base = Rs1Engine(u, cfg).run(start, end)
        val dear = Rs1Engine(u, cfg, fees = mapOf("AAAUSDT" to 0.002)).run(start, end)
        val t0 = base.trades.first { it.symbol == "AAAUSDT" }
        val t1 = dear.trades.first { it.symbol == "AAAUSDT" && it.entryTime == t0.entryTime }
        assertTrue(t1.fees > t0.fees * 3)
        val doubled = Rs1Engine(u, cfg.copy(costs = cfg.costs.doubled())).run(start, end)
        assertTrue(doubled.friction / doubled.trades.size > 1.9 * base.friction / base.trades.size)
    }

    @Test
    fun `the frozen default configuration exits only on the protective stop or the BTC regime`() {
        val frozen = Rs1Config()
        assertFalse(frozen.exitTrailing || frozen.exitEma || frozen.exitIneligible || frozen.useTrendFilter || frozen.useReturn30 || frozen.entryEveryDay)
        assertTrue(frozen.exitBtc && frozen.useBtcFilter && frozen.useReturn7 && frozen.useRelativeStrength && frozen.useRangeFilter && frozen.useVolumeFilter)
        assertEquals(250, frozen.minHistoryDays)
        // Uzun yükselişten sonra yavaş düşüş: V5 kurallarında EMA50/uygunluk çıkışı olur; dondurulmuş kurallarda olmaz.
        val coin = series("AAAUSDT") { if (it < 300) 0.01 else -0.004 }
        val u = data(BTC to btcUp, "AAAUSDT" to coin)
        val r = Rs1Engine(u.mapValues { (s, d) -> DailySeries(s, d.bars, frozen) }, frozen).run(start, end)
        assertTrue(r.trades.isNotEmpty())
        assertTrue(r.trades.all { it.reason in setOf(ExitReason.STOP, ExitReason.STOP_GAP, ExitReason.BTC_FILTER, ExitReason.PERIOD_END) })
        assertTrue(r.trades.all { it.stopChanges.size == 1 }) // iz süren stop yok: yalnız başlangıç stopu
    }
}
