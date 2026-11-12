package com.spotbot.core.security

import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Costs
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
    fun `default configuration matches the pre-registered and frozen RS-1 parameters`() {
        val c = Rs1Config()
        assertEquals(100, c.emaTrend); assertEquals(50, c.emaExit); assertEquals(14, c.atrPeriod)
        assertEquals(3.0, c.minRelativeStrengthPoints, 0.0); assertEquals(0.15, c.maxSpreadPercent, 0.0)
        assertEquals(5_000_000.0, c.minMedianVolume, 0.0); assertEquals(2.5, c.maxRangeAtr, 0.0); assertEquals(2, c.candidates)
        assertEquals(1_000.0, c.capital, 0.0); assertEquals(0.5, c.riskPercent, 0.0); assertEquals(2, c.maxPositions)
        assertEquals(25.0, c.maxPositionPercent, 0.0); assertEquals(50.0, c.maxTotalPercent, 0.0); assertEquals(1.5, c.dailyLossPercent, 0.0)
        assertEquals(2.5, c.initialStopAtr, 0.0); assertEquals(1, c.entryWeekday); assertEquals(Rs1Costs.MEDIUM, c.costs)
        // V6'da dondurulan seçimler (docs/RS1-V6-ONKAYIT.md, commit 15271c7): bağımsız testten önce sabitlendi.
        assertEquals(250, c.minHistoryDays)
        assertFalse(c.entryEveryDay || c.exitTrailing || c.exitEma || c.exitIneligible || c.useTrendFilter || c.useReturn30)
        assertTrue(c.exitBtc && c.useBtcFilter && c.useReturn7 && c.useRelativeStrength && c.useRangeFilter && c.useVolumeFilter)
    }

    /** Tek strateji: eski A/B/C motoru ve strateji sınıfları derlenmiş kodda yok; kaynaklar onlara başvurmuyor. */
    @Test
    fun `old strategies and the old trading engine cannot run`() {
        val removed = listOf(
            "com.spotbot.core.engine.TradingEngine", "com.spotbot.core.strategy.StrategyId",
            "com.spotbot.core.strategy.BreakoutRetestStrategy", "com.spotbot.core.strategy.TrendPullbackStrategy",
            "com.spotbot.core.strategy.RangeStrategy", "com.spotbot.core.trading.DecisionPipeline",
            "com.spotbot.core.backtest.BacktestEngine", "com.spotbot.core.research.V3Engine",
        )
        for (name in removed) {
            val present = runCatching { Class.forName(name) }.isSuccess
            assertFalse("$name hâlâ derlenmiş kodda", present)
        }
        val references = listOf("StrategyId", "TradingEngine", "BREAKOUT_RETEST", "TREND_PULLBACK", "DecisionPipeline", "BacktestEngine")
        val hits = sources.filter { it.extension == "kt" }.flatMap { f -> references.filter { f.readText().contains(it) }.map { "${f.path}: $it" } }
        assertTrue("Eski strateji başvurusu: $hits", hits.isEmpty())
    }
}
