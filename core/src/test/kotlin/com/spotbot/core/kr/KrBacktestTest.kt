package com.spotbot.core.kr

import com.spotbot.core.kr.KrConfig.Companion.M15
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KrBacktestTest {
    private val cfg = KrConfig()

    private fun input(vararg setups: Pair<String, Scenario.Setup>, terms: Map<String, SymbolTerms> = emptyMap()): KrBacktestInput {
        val len = setups.maxOf { it.second.bars.size }
        val btc = Scenario.warmup(len, start = 50_000.0, step = 0.0002)
        val m15 = setups.associate { it.first to it.second.bars.toList() } + ("BTCUSDT" to btc)
        return KrBacktestInput(m15, m15.mapValues { Trend4h(Trend4h.aggregate(it.value), cfg) }, { m15.keys }, terms, "test")
    }

    @Test
    fun `one setup gives one trade entered at the next open after the retest close`() {
        val s = Scenario.setup()
        val inp = input("XUSDT" to s)
        val start = Scenario.T0 + Scenario.W * M15
        val end = s.bars.last().closeTime
        val r = KrBacktest(cfg).run(inp, start, end)
        println("FUNNEL " + r.funnel)
        assertEquals(1, r.trades.size)
        val t = r.trades.single()
        val entryBar = s.bars[s.retestIndex + 1]
        assertEquals(entryBar.openTime, t.entryTime)
        val leg = cfg.costs.legFor(null, null)
        assertEquals(entryBar.open * (1 + leg.priceImpact), t.entryPrice, 1e-9)
        assertEquals(ExitReason.TARGET, t.reason)
        assertTrue(t.exitTime > t.entryTime)
        assertTrue(t.netPnl > 0 && t.netPnl < t.grossPnl)
        assertEquals(1, r.funnel["Giriş yapıldı"])
        // Aynı veriyle yeniden üretim birebir aynı.
        val again = KrBacktest(cfg).run(inp, start, end)
        assertEquals(r.trades, again.trades)
        assertEquals(r.endEquity, again.endEquity, 0.0)
    }

    @Test
    fun `a rejected candidate does not use up a free position slot`() {
        val x = Scenario.setup()
        val y = Scenario.setup(start = 50.0)
        val one = cfg.copy(risk = cfg.risk.copy(maxPositions = 1))
        // Y'nin komisyonu %2: maliyet/R:R süzgecine takılır; X yine girer.
        val inp = input("XUSDT" to x, "YUSDT" to y, terms = mapOf("YUSDT" to SymbolTerms(takerFee = 0.02)))
        val r = KrBacktest(one).run(inp, Scenario.T0 + Scenario.W * M15, x.bars.last().closeTime)
        assertEquals(listOf("XUSDT"), r.trades.map { it.symbol })
        assertTrue(r.funnel.keys.any { it.startsWith("Giriş: ") })
    }

    @Test
    fun `doubled costs lower the result and outside-universe coins are not traded`() {
        val s = Scenario.setup()
        val inp = input("XUSDT" to s)
        val start = Scenario.T0 + Scenario.W * M15
        val normal = KrBacktest(cfg).run(inp, start, s.bars.last().closeTime)
        val doubled = KrBacktest(cfg, cfg.costs.doubled()).run(inp, start, s.bars.last().closeTime)
        assertTrue(doubled.trades.sumOf { it.netPnl } < normal.trades.sumOf { it.netPnl })
        val outside = KrBacktestInput(inp.m15, inp.h4, { setOf("BTCUSDT") }, emptyMap(), "dış")
        assertEquals(0, KrBacktest(cfg).run(outside, start, s.bars.last().closeTime).trades.size)
    }
}
