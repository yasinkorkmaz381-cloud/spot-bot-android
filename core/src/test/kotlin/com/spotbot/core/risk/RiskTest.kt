package com.spotbot.core.risk

import com.spotbot.core.support.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskTest {

    private val free = CostModel(takerFee = 0.0, slippage = 0.0, halfSpread = 0.0)
    private val costs = CostModel(takerFee = 0.0005, slippage = 0.0005, halfSpread = 0.00025)

    @Test
    fun `without costs risk reward is the plain price ratio`() {
        val rr = netRiskReward(entry = 100.0, stop = 98.0, target = 104.0, costs = free)
        assertEquals(2.0, rr.riskPerUnit, 1e-12)
        assertEquals(4.0, rr.rewardPerUnit, 1e-12)
        assertEquals(2.0, rr.ratio, 1e-12)
    }

    @Test
    fun `fees spread and slippage reduce the net ratio`() {
        val rr = netRiskReward(100.0, 98.0, 104.0, costs)
        assertTrue(rr.ratio < 2.0)
        // Giriş 100*(1+0.00075)=100.075, ücret 0.0500375; stop çıkış 98*(1-0.00075)=97.9265, ücret 0.04896
        assertEquals(100.075 - 97.9265 + 100.075 * 0.0005 + 97.9265 * 0.0005, rr.riskPerUnit, 1e-9)
    }

    @Test
    fun `size risks the configured share of equity`() {
        val sized = PositionSizer.size(1_000.0, 1_000.0, 100.0, 98.0, 104.0, TestData.symbolInfo(), free, RiskConfig()) as SizingResult.Sized
        // %0,5 risk = 5 USDT; birim risk 2 → 2,5 adet
        assertEquals(2.5, sized.quantity, 1e-9)
        assertEquals(5.0, sized.riskAmount, 1e-9)
        assertEquals(0.5, sized.riskPercent, 1e-9)
        assertEquals(null, sized.cappedBy)
    }

    @Test
    fun `tight stop is capped by maximum position size`() {
        val sized = PositionSizer.size(1_000.0, 1_000.0, 100.0, 99.9, 101.0, TestData.symbolInfo(), free, RiskConfig()) as SizingResult.Sized
        assertEquals(3.0, sized.quantity, 1e-9) // %30 * 1000 / 100
        assertTrue(sized.riskPercent < 0.5)
        assertTrue(sized.cappedBy!!.startsWith("Pozisyon"))
    }

    @Test
    fun `quantity is floored to exchange precision`() {
        val info = TestData.symbolInfo().copy(quantityPrecision = 2)
        val sized = PositionSizer.size(1_000.0, 1_000.0, 100.0, 97.0, 110.0, info, free, RiskConfig()) as SizingResult.Sized
        assertEquals(1.66, sized.quantity, 1e-12) // 5/3 = 1.666… → 1.66
    }

    @Test
    fun `rejects orders below the exchange minimum`() {
        val info = TestData.symbolInfo().copy(minNotional = 500.0)
        val result = PositionSizer.size(1_000.0, 1_000.0, 100.0, 98.0, 104.0, info, free, RiskConfig())
        assertTrue(result is SizingResult.Rejected)
    }

    @Test
    fun `cannot spend more than available quote`() {
        val sized = PositionSizer.size(1_000.0, 50.0, 100.0, 98.0, 104.0, TestData.symbolInfo(), free, RiskConfig()) as SizingResult.Sized
        assertEquals(0.5, sized.quantity, 1e-9)
        assertEquals("Kullanılabilir USDT", sized.cappedBy)
    }

    @Test
    fun `stop above entry is rejected`() {
        assertTrue(PositionSizer.size(1_000.0, 1_000.0, 100.0, 101.0, 104.0, null, free, RiskConfig()) is SizingResult.Rejected)
    }
}
