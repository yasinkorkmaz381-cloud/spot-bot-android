package com.spotbot.core.security

import com.spotbot.core.engine.EngineConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Güvenlik: bu sürüm yalnız sanal işlem yapar. Kaynak kodda gerçek emir gönderen imzalı uç nokta, API anahtarı
 * ya da imza kodu bulunmamalıdır; istemci yalnız herkese açık GET uç noktalarını kullanır.
 */
class SecurityTest {

    private val roots = listOf("src/main", "../app/src/main", "../cli/src/main").map { File(it) }.filter { it.exists() }
    private val sources = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "xml", "json", "properties") } }

    @Test
    fun `no signed order endpoints or api key handling exist in the sources`() {
        assertTrue("Kaynak dosyalar bulunamadı", sources.size > 20)
        val forbidden = listOf("/api/v3/order", "X-MEXC-APIKEY", "HmacSHA256", "secretKey", "apiSecret")
        val hits = sources.flatMap { file -> forbidden.filter { file.readText().contains(it) }.map { "${file.path}: $it" } }
        assertTrue("Yasak ifade bulundu: $hits", hits.isEmpty())
    }

    @Test
    fun `rest client only issues GET requests`() {
        val client = File("src/main/kotlin/com/spotbot/core/exchange/mexc/MexcRestClient.kt").readText()
        assertTrue(client.contains(".get()"))
        listOf(".post(", ".put(", ".delete(", ".patch(").forEach { assertTrue("İstemci $it içermemeli", !client.contains(it)) }
    }

    @Test
    fun `default configuration is paper trading with unchanged risk defaults`() {
        val config = EngineConfig()
        assertTrue(config.risk.riskPerTradePercent == 0.5)
        assertTrue(config.risk.dailyLossLimitPercent == 1.5)
        assertTrue(config.risk.maxOpenPositions == 3)
        assertTrue(config.risk.minNetRiskReward == 1.5)
        assertTrue(config.risk.atrStopMode == com.spotbot.core.risk.AtrStopMode.OFF)
        assertTrue(config.profile == com.spotbot.core.trading.StrategyProfile.ADAPTIVE)
        assertTrue(config.execution == com.spotbot.core.trading.ExecutionConfig())
    }
}
