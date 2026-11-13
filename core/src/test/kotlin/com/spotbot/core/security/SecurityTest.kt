package com.spotbot.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `default configuration matches the pre-registered KR parameters`() {
        val c = com.spotbot.core.kr.KrConfig()
        assertEquals(50, c.emaTrend4h); assertEquals(6, c.emaSlopeBars); assertEquals(3, c.swingSide); assertEquals(72, c.levelLookbackHours)
        assertEquals(1.5, c.breakoutVolumeMultiple, 0.0); assertEquals(8, c.retestBars); assertEquals(0.25, c.retestToleranceAtr, 0.0)
        assertEquals(0.75, c.maxChaseAtr, 0.0); assertEquals(0.25, c.stopBufferAtr, 0.0); assertEquals(1.5, c.targetAtr1h, 0.0)
        assertEquals(1.2, c.minNetRewardRisk, 0.0); assertEquals(0.40, c.maxCostShareOfTarget, 0.0); assertEquals(8, c.progressBars)
        assertEquals(24, c.maxHoldBars); assertEquals(2.0, c.btcShockPercent, 0.0); assertEquals(1_000_000.0, c.minQuoteVolume24h, 0.0)
        val r = c.risk
        assertEquals(0.25, r.riskPercent, 0.0); assertEquals(1.0, r.maxOpenRiskPercent, 0.0); assertEquals(4, r.maxPositions)
        assertEquals(20.0, r.maxPositionPercent, 0.0); assertEquals(70.0, r.maxTotalPercent, 0.0); assertEquals(2.0, r.dailyLossPercent, 0.0)
    }

    /** CMC anahtarı kaynak koda gömülmez: istemci anahtarı yalnız çalışma anında sağlayıcıdan alır. */
    @Test
    fun `no CMC key is embedded in the sources`() {
        val keyLike = Regex("""X-CMC_PRO_API_KEY"\s*,\s*"[0-9a-f-]{20,}""", RegexOption.IGNORE_CASE)
        val hits = sources.filter { keyLike.containsMatchIn(it.readText()) || Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(it.readText()) }
        assertTrue("Anahtar benzeri değer: $hits", hits.isEmpty())
    }

    /** Tek strateji: eski A/B/C motoru ve strateji sınıfları derlenmiş kodda yok; kaynaklar onlara başvurmuyor. */
    @Test
    fun `old strategies and the old trading engine cannot run`() {
        val removed = listOf(
            "com.spotbot.core.engine.TradingEngine", "com.spotbot.core.strategy.StrategyId",
            "com.spotbot.core.strategy.BreakoutRetestStrategy", "com.spotbot.core.strategy.TrendPullbackStrategy",
            "com.spotbot.core.strategy.RangeStrategy", "com.spotbot.core.trading.DecisionPipeline",
            "com.spotbot.core.backtest.BacktestEngine", "com.spotbot.core.research.V3Engine",
            "com.spotbot.core.rs1.Rs1Engine", "com.spotbot.core.rs1.Rs1Paper", "com.spotbot.core.rs1.Rs1Config",
        )
        for (name in removed) {
            val present = runCatching { Class.forName(name) }.isSuccess
            assertFalse("$name hâlâ derlenmiş kodda", present)
        }
        val references = listOf("StrategyId", "TradingEngine", "BREAKOUT_RETEST", "TREND_PULLBACK", "DecisionPipeline", "BacktestEngine", "core.rs1", "Rs1Engine", "Rs1Paper")
        val hits = sources.filter { it.extension == "kt" }.flatMap { f -> references.filter { f.readText().contains(it) }.map { "${f.path}: $it" } }
        assertTrue("Eski strateji başvurusu: $hits", hits.isEmpty())
    }
}
