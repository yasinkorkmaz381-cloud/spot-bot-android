package com.spotbot.core.kr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KrRulesTest {
    private val cfg = KrConfig()
    private val leg = Leg(fee = 0.0005, halfSpread = 0.0005, slippage = 0.0005)

    private fun pos(stop: Double = 99.0, target: Double = 102.0) = KrPosition(
        id = "p", symbol = "XUSDT", setupId = "s", entryTime = 0, refPrice = 100.0, entryPrice = 100.0, quantity = 1.0,
        stop = stop, target = target, level = 99.5, invalidation = 99.3, entryLeg = leg, exitLeg = leg, entryFee = 0.05,
        plannedNetRewardRisk = 1.5, riskAmount = 1.2, highest = 100.0,
    )

    @Test
    fun `stop wins when stop and target are both inside one candle and the trade is marked`() {
        val hit = KrExits.intrabar(pos(), open = 100.0, high = 103.0, low = 98.0)!!
        assertEquals(ExitReason.STOP, hit.reason)
        assertTrue(hit.ambiguous)
        assertEquals(99.0 * (1 - leg.priceImpact), hit.price, 1e-9)
    }

    @Test
    fun `a gap below the stop fills at the open, not at the stop`() {
        val hit = KrExits.intrabar(pos(), open = 97.0, high = 97.5, low = 96.0)!!
        assertEquals(97.0 * (1 - leg.priceImpact), hit.price, 1e-9)
        assertFalse(hit.ambiguous)
    }

    @Test
    fun `target needs the bid to reach it (half spread above)`() {
        assertNull(KrExits.intrabar(pos(), 100.0, 102.0 * (1 + leg.halfSpread / 2), 99.5))
        assertEquals(ExitReason.TARGET, KrExits.intrabar(pos(), 100.0, 102.0 * (1 + leg.halfSpread), 99.5)!!.reason)
    }

    @Test
    fun `close rules - invalidation, no progress after 8 bars, max hold`() {
        assertEquals(ExitReason.INVALIDATION, KrExits.onClose(pos().copy(barsHeld = 1), 99.2, cfg))
        assertNull(KrExits.onClose(pos().copy(barsHeld = 7, highest = 100.1), 100.0, cfg))
        assertEquals(ExitReason.NO_PROGRESS, KrExits.onClose(pos().copy(barsHeld = 8, highest = 100.9), 100.0, cfg))
        assertNull(KrExits.onClose(pos().copy(barsHeld = 8, highest = 101.0), 100.0, cfg))
        assertEquals(ExitReason.MAX_HOLD, KrExits.onClose(pos().copy(barsHeld = 24, highest = 101.5), 100.5, cfg))
    }

    @Test
    fun `net reward-risk includes both legs' costs and the target is never pushed further`() {
        val plan = SetupPlan("id", "XUSDT", 0, 0, 99.5, atr15 = 0.4, atr1h = 1.0, stop = 99.0, invalidation = 99.4, maxEntry = 99.8,
            retestClose = 99.7, levelsAbove = listOf(100.5, 103.0), quoteVolume24h = 2e6, return24hPercent = 1.0, cleanSince = 0)
        val q = KrPricing.quote(plan, 99.7, leg, leg, cfg)
        // En yakın yapısal direnç (100,5) volatilite hedefinden (fill + 1,5) yakın → hedef 100,5.
        assertEquals(100.5, q.target, 1e-9)
        val cost = q.fill * 1.0005
        val expected = (100.5 * 0.9995 - cost) / (cost - 99.0 * (1 - leg.priceImpact) * 0.9995)
        assertEquals(expected, q.netRewardRisk, 1e-12)
        val noLevels = KrPricing.quote(plan.copy(levelsAbove = emptyList()), 99.7, leg, leg, cfg)
        assertEquals(noLevels.fill + 1.5, noLevels.target, 1e-9)
    }

    @Test
    fun `sizing - risk per trade, caps, rounding, min notional, max positions`() {
        val terms = SymbolTerms(quantityStep = 0.01, minNotional = 5.0)
        val r = KrSizing.size(100.0, 99.0, leg, leg, 1000.0, 1000.0, 0.0, 0.0, 0, cfg.risk, terms) as KrSizing.Result.Ok
        // 2,5 USDT risk / ~1,15 birim kayıp ≈ 2,17 → %20 sınırı 2 birim.
        assertEquals(2.0, r.quantity, 1e-9)
        val wide = KrSizing.size(100.0, 90.0, leg, leg, 1000.0, 1000.0, 0.0, 0.0, 0, cfg.risk, terms) as KrSizing.Result.Ok
        assertTrue(wide.riskAmount <= 2.5 + 1e-9)
        assertEquals(0.24, wide.quantity, 1e-9)
        assertEquals(Rejection.MAX_POSITIONS, (KrSizing.size(100.0, 99.0, leg, leg, 1000.0, 1000.0, 0.0, 0.0, 4, cfg.risk, terms) as KrSizing.Result.Rejected).reason)
        assertEquals(Rejection.RISK_FULL, (KrSizing.size(100.0, 99.0, leg, leg, 1000.0, 1000.0, 0.0, 10.0, 1, cfg.risk, terms) as KrSizing.Result.Rejected).reason)
        assertEquals(Rejection.CAPITAL_FULL, (KrSizing.size(100.0, 99.0, leg, leg, 1000.0, 1000.0, 700.0, 0.0, 1, cfg.risk, terms) as KrSizing.Result.Rejected).reason)
        assertEquals(Rejection.MIN_NOTIONAL, (KrSizing.size(100.0, 90.0, leg, leg, 1000.0, 1000.0, 0.0, 0.0, 0, cfg.risk, SymbolTerms(quantityStep = 1.0, minNotional = 5.0)) as KrSizing.Result.Rejected).reason)
        // Toplam risk %1: üç açık pozisyonun 7,6 USDT riski varken yalnız kalan 2,4 USDT kullanılabilir.
        val partial = KrSizing.size(100.0, 90.0, leg, leg, 1000.0, 1000.0, 0.0, 7.6, 3, cfg.risk, terms) as KrSizing.Result.Ok
        assertTrue(partial.riskAmount <= 2.4 + 1e-9)
    }

    @Test
    fun `daily loss counts unrealized loss`() {
        val book = KrBook(1000.0).rollDay(Scenario.T0, emptyMap()).open(pos().copy(quantity = 5.0))
        assertFalse(book.dailyLossHit(mapOf("XUSDT" to 100.0), cfg.risk))
        assertTrue(book.dailyLossHit(mapOf("XUSDT" to 96.0), cfg.risk))
    }
}
