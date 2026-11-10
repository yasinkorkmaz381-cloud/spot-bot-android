package com.spotbot.core.research

import com.spotbot.core.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class V3EngineTest {
    private val start = 1_577_836_800_000L // 2020-01-01

    /** Rejim değiştiren rastgele yürüyüş (tohumlu): yükseliş, düşüş ve yatay dönemler içerir. */
    private fun series(symbol: String, seed: Int, bars: Int = 6 * 900, quoteVolume: Double = 2e6): H4Dataset {
        val rnd = Random(seed)
        var p = 100.0
        val out = ArrayList<Candle>()
        for (i in 0 until bars) {
            val drift = when ((i / 900) % 3) { 0 -> 0.002; 1 -> -0.0015; else -> 0.0 }
            val r = drift + rnd.nextDouble(-0.02, 0.02)
            val o = p
            val c = p * exp(r)
            val h = maxOf(o, c) * (1 + rnd.nextDouble(0.0, 0.01))
            val l = minOf(o, c) * (1 - rnd.nextDouble(0.0, 0.01))
            val t = start + i * H4_MS
            out += Candle(t, o, h, l, c, quoteVolume / c * rnd.nextDouble(0.5, 2.5), t + H4_MS, quoteVolume * rnd.nextDouble(0.5, 2.5))
            p = c
        }
        return H4Dataset(symbol, out)
    }

    private val data = listOf(series("BTCUSDT", 1), series("AAAUSDT", 2), series("BBBUSDT", 3), series("CCCUSDT", 4))
    private val u = Universe.of(data)
    private val from = start + 250 * DAY_MS
    private val to = start + 880 * DAY_MS

    @Test
    fun `daily candles are built only from complete UTC days`() {
        val d = u.btc
        assertEquals(900, d.days.size)
        assertTrue(d.days.all { it.closeTime - it.openTime == DAY_MS && it.openTime % DAY_MS == 0L })
        val partial = Universe.of(listOf(H4Dataset("BTCUSDT", data[0].h4.drop(3)))).btc
        assertEquals(899, partial.days.size)
    }

    @Test
    fun `tradability needs history and volume`() {
        assertFalse(u.btc.tradable(start + 100 * DAY_MS, 1e6))
        assertTrue(u.btc.tradable(start + 300 * DAY_MS, 1e6))
        val thin = Universe.of(listOf(series("BTCUSDT", 1, quoteVolume = 50_000.0))).btc
        assertFalse(thin.tradable(start + 300 * DAY_MS, 1e6))
    }

    @Test
    fun `every strategy trades and accounting is exact`() {
        for ((_, opts) in V3Strategies.families) {
            val s = opts[1]
            val r = V3Engine(u, V3Config(from, to)).run(s)
            assertTrue("${s.name} işlem açmadı", r.trades > 0)
            val c = CostScenario.MEDIUM
            r.tradeList.forEach { t ->
                // Net sonuç = satış dolumu − alış dolumu − iki yönün ücreti.
                assertEquals(t.netPnl, (t.exitPrice - t.entryPrice) * t.quantity - t.fees, 1e-6)
                assertTrue(t.exitTime >= t.entryTime)
                assertEquals(t.fees, (t.entryPrice + t.exitPrice) * t.quantity * c.fee, 1e-6)
            }
            // Sermaye değişimi işlem sonuçlarının toplamına eşit.
            assertEquals(r.netReturnPercent, r.tradeList.sumOf { it.netPnl } / 10.0, 1e-6)
            // Eş zamanlı en fazla 3 pozisyon.
            val events = r.tradeList.flatMap { listOf(it.entryTime to 1, it.exitTime to -1) }.sortedWith(compareBy({ it.first }, { it.second }))
            var open = 0
            events.forEach { open += it.second; assertTrue(open <= 3) }
        }
    }

    @Test
    fun `removing data after the cutoff does not change earlier decisions (no look-ahead)`() {
        val cutoff = start + 600 * DAY_MS
        val truncated = Universe.of(data.map { d -> d.copy(h4 = d.h4.filter { it.closeTime <= cutoff }) })
        for ((_, opts) in V3Strategies.families) {
            val s = opts[1]
            val a = V3Engine(u, V3Config(from, cutoff)).run(s).tradeList
            val b = V3Engine(truncated, V3Config(from, cutoff)).run(s).tradeList
            assertEquals(s.name, a.map { it.symbol to it.entryTime }, b.map { it.symbol to it.entryTime })
            assertEquals(s.name, a.map { it.exitTime }, b.map { it.exitTime })
            a.zip(b).forEach { (x, y) -> assertTrue(abs(x.netPnl - y.netPnl) < 1e-9) }
        }
    }

    @Test
    fun `higher cost scenario never improves the result of the same trades`() {
        val s = V3Strategies.families[3].second[1]
        val low = V3Engine(u, V3Config(from, to, CostScenario.OPTIMISTIC)).run(s)
        val high = V3Engine(u, V3Config(from, to, CostScenario.PESSIMISTIC)).run(s)
        assertTrue(high.friction / high.trades > low.friction / low.trades)
    }

    @Test
    fun `top-N universe trades only the most liquid pairs of that day`() {
        val mixed = Universe.of(
            listOf(series("BTCUSDT", 1, quoteVolume = 9e6), series("AAAUSDT", 2, quoteVolume = 8e6), series("BBBUSDT", 3, quoteVolume = 1.5e6), series("CCCUSDT", 4, quoteVolume = 1.4e6)),
        )
        val s1 = RelativeStrength(30)
        val all = V3Engine(mixed, V3Config(from, to)).run(s1)
        val top2 = V3Engine(mixed, V3Config(from, to, maxUniverse = 2)).run(s1)
        assertTrue(all.tradeList.any { it.symbol in setOf("BBBUSDT", "CCCUSDT") })
        assertTrue(top2.tradeList.isNotEmpty())
        assertTrue(top2.tradeList.all { it.symbol in setOf("BTCUSDT", "AAAUSDT") })
        // Sınır etkisizse (N ≥ parite sayısı) sonuç değişmez.
        assertEquals(all.tradeList, V3Engine(mixed, V3Config(from, to, maxUniverse = 10)).run(s1).tradeList)
    }

    @Test
    fun `tiered costs charge more on low-volume pairs only`() {
        val s1 = RelativeStrength(30)
        val flat = V3Engine(u, V3Config(from, to, CostScenario.MEDIUM)).run(s1)
        // Bütün pariteler 20M altında: kademeli senaryo daha pahalı.
        val tiered = V3Engine(u, V3Config(from, to, CostScenario.TIERED_MEDIUM)).run(s1)
        assertTrue(tiered.friction / tiered.trades > flat.friction / flat.trades)
        // Hepsi 20M üstünde: kademeli senaryo düz orta senaryoyla aynı.
        val liquid = Universe.of(data.map { series(it.symbol, listOf("BTCUSDT", "AAAUSDT", "BBBUSDT", "CCCUSDT").indexOf(it.symbol) + 1, quoteVolume = 50e6) })
        assertEquals(
            V3Engine(liquid, V3Config(from, to, CostScenario.MEDIUM)).run(s1).tradeList,
            V3Engine(liquid, V3Config(from, to, CostScenario.TIERED_MEDIUM)).run(s1).tradeList,
        )
    }
}
