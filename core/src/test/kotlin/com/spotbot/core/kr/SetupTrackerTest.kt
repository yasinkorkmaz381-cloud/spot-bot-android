package com.spotbot.core.kr

import com.spotbot.core.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTrackerTest {
    private val cfg = KrConfig()

    private fun run(bars: List<Candle>): List<Pair<Int, TrackerEvent>> {
        val t = SetupTracker("XUSDT", cfg, Trend4h(Trend4h.aggregate(bars), cfg))
        return bars.withIndex().flatMap { (i, b) -> t.onBar(b).map { i to it } }
    }

    @Test
    fun `breakout on a pre-fixed level followed by a retest gives exactly one entry plan`() {
        val s = Scenario.setup()
        val events = run(s.bars)
        val breakouts = events.filter { it.second is TrackerEvent.BreakoutAccepted }
        assertEquals(listOf(s.breakoutIndex), breakouts.map { it.first })
        assertEquals(s.level, (breakouts.single().second as TrackerEvent.BreakoutAccepted).level, 1e-9)
        val retests = events.filter { it.second is TrackerEvent.RetestConfirmed }
        assertEquals(listOf(s.retestIndex), retests.map { it.first })
        val plan = (retests.single().second as TrackerEvent.RetestConfirmed).plan
        assertEquals(s.level, plan.level, 1e-9)
        assertTrue("stop retest düşüğünün altında", plan.stop < s.bars[s.retestIndex].low)
        assertTrue(plan.maxEntry > plan.level && plan.invalidation < plan.level)
        // Kurulum karar anında (retest kapanışı) üretilir; kırılım zamanı plana yazılır.
        assertEquals(s.bars[s.retestIndex].closeTime, plan.decisionTime)
        assertEquals(s.bars[s.breakoutIndex].openTime, plan.breakoutTime)
    }

    @Test
    fun `a level is not known before its right-side bars close`() {
        // Kırılım, tepenin sağındaki 3 saat tamamlanmadan (C saatinde) olursa: seviye henüz yok → kırılım yok.
        val s = Scenario.setup()
        val bars = s.bars.toMutableList()
        val i = Scenario.W + 8
        val b = bars[i]
        bars[i] = b.copy(high = s.level * 1.004, close = s.level * 1.003, volume = 3000.0)
        val events = run(bars.subList(0, Scenario.W + 16))
        assertTrue(events.none { it.second is TrackerEvent.BreakoutAccepted })
    }

    @Test
    fun `a wick above the level is not a breakout`() {
        val s = Scenario.setup(breakoutClose = 0.999)
        val events = run(s.bars.subList(0, s.breakoutIndex + 1))
        assertTrue(events.none { it.second is TrackerEvent.BreakoutAccepted || it.second is TrackerEvent.BreakoutRejected })
    }

    @Test
    fun `low volume breakout is rejected and consumes the level`() {
        val s = Scenario.setup(breakoutVolume = 1200.0)
        val events = run(s.bars)
        val rejected = events.single { it.second is TrackerEvent.BreakoutRejected }.second as TrackerEvent.BreakoutRejected
        assertEquals(TrackerEvent.Reason.LOW_VOLUME, rejected.reason)
        assertTrue(events.none { it.second is TrackerEvent.RetestConfirmed || it.second is TrackerEvent.BreakoutAccepted })
    }

    @Test
    fun `breakout and retest are never the same candle`() {
        // Kırılım mumunun düşüğü seviyeye değse ve yükseliş kapanışı olsa bile o mum retest sayılmaz.
        val s = Scenario.setup()
        val bars = s.bars.toMutableList()
        val b = bars[s.breakoutIndex]
        bars[s.breakoutIndex] = b.copy(low = s.level * 1.0002, open = s.level * 1.0005)
        val events = run(bars.subList(0, s.breakoutIndex + 1))
        assertTrue(events.any { it.second is TrackerEvent.BreakoutAccepted })
        assertTrue(events.none { it.second is TrackerEvent.RetestConfirmed })
    }

    @Test
    fun `close back below the level cancels the setup`() {
        val s = Scenario.setup()
        val bars = s.bars.toMutableList()
        val r = bars[s.retestIndex]
        bars[s.retestIndex] = r.copy(low = s.level * 0.995, close = s.level * 0.998)
        val events = run(bars)
        val ended = events.single { it.second is TrackerEvent.SetupEnded }.second as TrackerEvent.SetupEnded
        assertEquals(TrackerEvent.Reason.CLOSED_BELOW, ended.reason)
        assertTrue(events.none { it.second is TrackerEvent.RetestConfirmed })
    }

    @Test
    fun `no retest within eight candles expires the setup`() {
        val s = Scenario.setup(retest = false, after = 0)
        val bars = s.bars.toMutableList()
        // Kırılımdan sonra fiyat seviyeye hiç yaklaşmaz (düşük > seviye + tolerans).
        repeat(10) { k -> val i = bars.size; bars += Scenario.bar(i, s.level * 1.005, s.level * 1.006, s.level * 1.004, s.level * 1.005) }
        val events = run(bars)
        val ended = events.single { it.second is TrackerEvent.SetupEnded }
        assertEquals(TrackerEvent.Reason.EXPIRED, (ended.second as TrackerEvent.SetupEnded).reason)
        assertEquals(s.breakoutIndex + cfg.retestBars, ended.first)
    }

    @Test
    fun `replaying only the last 500 candles gives the same plan as the full history`() {
        val s = Scenario.setup()
        val upto = s.retestIndex + 1
        val full = run(s.bars.subList(0, upto)).last { it.second is TrackerEvent.RetestConfirmed }.second as TrackerEvent.RetestConfirmed
        val window = s.bars.subList(upto - 500, upto)
        val t = SetupTracker("XUSDT", cfg, Trend4h(Trend4h.aggregate(s.bars.subList(0, upto)), cfg))
        val replay = window.flatMap { t.onBar(it) }.last { it is TrackerEvent.RetestConfirmed } as TrackerEvent.RetestConfirmed
        assertEquals(full.plan.copy(cleanSince = 0, atr1h = 0.0, atr15 = 0.0, stop = 0.0, invalidation = 0.0, maxEntry = 0.0),
            replay.plan.copy(cleanSince = 0, atr1h = 0.0, atr15 = 0.0, stop = 0.0, invalidation = 0.0, maxEntry = 0.0))
        // Wilder ATR'nin başlangıç etkisi 500 mumda sönümlenir.
        assertEquals(full.plan.atr15, replay.plan.atr15, full.plan.atr15 * 1e-6)
        assertEquals(full.plan.stop, replay.plan.stop, full.plan.stop * 1e-8)
    }

    @Test
    fun `stage reports the pending retest`() {
        val s = Scenario.setup()
        val t = SetupTracker("XUSDT", cfg, Trend4h(Trend4h.aggregate(s.bars), cfg))
        s.bars.subList(0, s.breakoutIndex + 1).forEach { t.onBar(it) }
        val (stage, reason) = t.stage()
        assertEquals(SetupStage.AWAITING_RETEST, stage)
        assertNotNull(reason)
        t.onBar(s.bars[s.retestIndex])
        assertEquals(SetupStage.ENTRY_READY, t.stage().first)
    }
}
