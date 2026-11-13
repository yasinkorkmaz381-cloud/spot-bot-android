package com.spotbot.core.kr

import com.spotbot.core.kr.Scenario.asset
import com.spotbot.core.kr.Scenario.info
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniverseTest {
    private fun list(vararg a: CmcAsset) = CmcList(0, 0, a.toList())

    @Test
    fun `stablecoins and tokenized assets are excluded, missing pairs reported, conflicts separated`() {
        val l = list(
            asset(1, "BTC", 1, 70_000.0),
            asset(825, "USDT", 2, 1.0, listOf("stablecoin")),
            asset(3, "XAUT", 3, 4000.0, listOf("tokenized-gold")),
            asset(4, "ABC", 4, 2.0),
            asset(5, "NOPE", 5, 1.0),
            asset(6, "WEIRD", 6, 10.0, name = "Wrapped Weird"),
            asset(7, "LST", 7, 1.0),
        )
        val mexc = listOf(info("BTCUSDT"), info("XAUTUSDT"), info("ABCUSDT"), info("WEIRDUSDT"), info("LSTUSDT", st = true), info("BTC3LUSDT"))
        val prices = mapOf("BTCUSDT" to 70_100.0, "XAUTUSDT" to 4000.0, "ABCUSDT" to 0.05, "WEIRDUSDT" to 10.0, "LSTUSDT" to 1.0)
        val u = UniverseBuilder.build(l, mexc, prices, 0)
        val by = u.entries.associateBy { it.asset.symbol }
        assertEquals(UniverseStatus.INCLUDED, by.getValue("BTC").status)
        assertEquals(UniverseStatus.EXCLUDED_CATEGORY, by.getValue("USDT").status)
        assertEquals(UniverseStatus.EXCLUDED_CATEGORY, by.getValue("XAUT").status)
        // Aynı sembol, fiyatı 40 kat farklı → farklı varlık.
        assertEquals(UniverseStatus.SYMBOL_CONFLICT, by.getValue("ABC").status)
        assertEquals(UniverseStatus.NOT_ON_MEXC, by.getValue("NOPE").status)
        assertEquals(UniverseStatus.EXCLUDED_CATEGORY, by.getValue("WEIRD").status)
        assertEquals(UniverseStatus.NOT_TRADABLE, by.getValue("LST").status)
        assertEquals(setOf("BTCUSDT"), u.symbols)
        assertEquals(7, u.top)
    }

    @Test
    fun `contract address match accepts even without a price, and the list is never filled past 300`() {
        val assets = (1..320).map { asset(it, "C$it", it, 1.0) }.toMutableList()
        assets[0] = assets[0].copy(tokenAddress = "0xABC")
        val mexc = (1..320).map { info("C${it}USDT", contract = if (it == 1) "0xabc" else "") }
        val prices = (2..320).associate { "C${it}USDT" to 1.0 }
        val u = UniverseBuilder.build(list(*assets.toTypedArray()), mexc, prices, 0)
        assertEquals(300, u.top)
        assertEquals(300, u.symbols.size)
        assertTrue("C301USDT" !in u.symbols)
        assertEquals("Sözleşme adresi aynı", u.entries.first().note)
    }

    @Test
    fun `two top-300 assets on the same ticker keep only the closer price`() {
        val u = UniverseBuilder.build(list(asset(1, "DUP", 1, 1.0), asset(2, "DUP", 2, 1.05)), listOf(info("DUPUSDT")), mapOf("DUPUSDT" to 1.04), 0)
        assertEquals(UniverseStatus.SYMBOL_CONFLICT, u.entries[0].status)
        assertEquals(UniverseStatus.INCLUDED, u.entries[1].status)
        assertNull(UniverseBuilder.excluded(asset(9, "SOL", 5, 100.0)))
    }
}
