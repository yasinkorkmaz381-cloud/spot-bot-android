package com.spotbot.android

import com.spotbot.android.data.AppSettings
import com.spotbot.android.data.ArchiveRepository
import com.spotbot.android.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Eski (V4) motorun bir kapanmış işlemi: A stratejisi, 100 → 110, 1 adet, ücretler 0,1 + 0,11. */
    private val oldPortfolio = """
        {"initialCapital":1000.0,"cash":1009.79,"closedTrades":[{"position":{"id":"1","symbol":"ETHUSDT","strategy":"BREAKOUT_RETEST",
        "openedAt":1700000000000,"entryPrice":100.0,"quantity":1.0,"entryFee":0.1,"stopPrice":95.0,"targetPrice":110.0,"realizedPnl":0.0},
        "closedAt":1700100000000,"exitPrice":110.0,"exitFee":0.11,"reason":"TARGET","note":""}]}
    """.trimIndent()

    @Test
    fun `old engine files are archived, not deleted, and the bot preference is kept`() {
        val dir = tmp.root
        File(dir, "settings.json").writeText("""{"engine":{"enabledStrategies":["TREND_PULLBACK"]},"botEnabled":true,"schemaVersion":2}""")
        File(dir, "portfolio.json").writeText(oldPortfolio)
        File(dir, "shadows.json").writeText("""{"BREAKOUT_RETEST":$oldPortfolio}""")
        File(dir, "decisions").mkdirs(); File(dir, "decisions/2026-11-10.json").writeText("[]")
        val repo = SettingsRepository(dir)
        assertEquals(AppSettings(botEnabled = true, schemaVersion = SettingsRepository.SCHEMA), repo.settings.value)
        val archive = File(dir, SettingsRepository.ARCHIVE)
        for (name in listOf("portfolio.json", "shadows.json", "decisions", "settings-eski.json", "BILGI.txt")) {
            assertTrue("$name arşivde olmalı", File(archive, name).exists())
        }
        assertFalse(File(dir, "portfolio.json").exists())
        assertTrue(File(archive, "decisions/2026-11-10.json").exists())
        // İkinci açılış bir şey değiştirmez.
        val again = SettingsRepository(dir)
        assertEquals(repo.settings.value, again.settings.value)
        assertTrue(File(archive, "portfolio.json").readText() == oldPortfolio)
    }

    @Test
    fun `archived trades are readable without the old classes`() {
        val dir = tmp.root
        File(dir, "portfolio.json").writeText(oldPortfolio)
        File(dir, "shadows.json").writeText("""{"BREAKOUT_RETEST":$oldPortfolio}""")
        SettingsRepository(dir)
        val a = ArchiveRepository(dir).load()
        assertEquals(2, a.trades.size)
        val t = a.trades.first()
        assertEquals("ETHUSDT", t.symbol)
        assertEquals("A", t.strategy)
        assertEquals(110.0 - 0.11 - (100.0 + 0.1), t.netPnl, 1e-9)
        assertTrue(a.note!!.contains("silinmedi"))
    }

    @Test
    fun `a fresh install has nothing to archive`() {
        val repo = SettingsRepository(tmp.root)
        assertEquals(SettingsRepository.SCHEMA, repo.settings.value.schemaVersion)
        assertFalse(File(tmp.root, SettingsRepository.ARCHIVE).exists())
        assertTrue(ArchiveRepository(tmp.root).load().trades.isEmpty())
    }
}
