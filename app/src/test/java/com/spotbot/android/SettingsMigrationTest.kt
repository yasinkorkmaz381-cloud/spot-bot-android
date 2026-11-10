package com.spotbot.android

import com.spotbot.android.data.AppSettings
import com.spotbot.android.data.SettingsRepository
import com.spotbot.core.engine.EngineConfig
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.strategy.StrategyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
    private val a = StrategyId.BREAKOUT_RETEST
    private val b = StrategyId.TREND_PULLBACK

    @Test
    fun `old settings move strategy A to shadow testing and keep risk settings`() {
        val risk = RiskConfig(riskPerTradePercent = 0.4)
        val old = AppSettings(engine = EngineConfig(enabledStrategies = setOf(a, b), shadowStrategies = emptySet(), risk = risk), botEnabled = true)
        val migrated = SettingsRepository.migrate(old)
        assertEquals(setOf(b), migrated.engine.enabledStrategies)
        assertTrue(a in migrated.engine.shadowStrategies)
        assertEquals(risk, migrated.engine.risk)
        assertTrue(migrated.botEnabled)
        assertEquals(SettingsRepository.SCHEMA, migrated.schemaVersion)
        // İkinci kez uygulamak bir şey değiştirmez; kullanıcı sonradan A'yı açarsa geri kapatılmaz.
        assertEquals(migrated, SettingsRepository.migrate(migrated))
        val reenabled = migrated.copy(engine = migrated.engine.copy(enabledStrategies = setOf(a, b)))
        assertEquals(reenabled, SettingsRepository.migrate(reenabled))
    }

    @Test
    fun `only A enabled falls back to B so the active portfolio is never empty`() {
        val migrated = SettingsRepository.migrate(AppSettings(engine = EngineConfig(enabledStrategies = setOf(a))))
        assertEquals(setOf(b), migrated.engine.enabledStrategies)
    }
}
