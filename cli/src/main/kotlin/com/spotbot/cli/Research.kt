package com.spotbot.cli

import com.spotbot.core.backtest.BacktestConfig
import com.spotbot.core.backtest.BacktestEngine
import com.spotbot.core.backtest.BacktestReport
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.backtest.MonteCarlo
import com.spotbot.core.backtest.MonteCarloResult
import com.spotbot.core.backtest.RunSummary
import com.spotbot.core.backtest.ShadowReport
import com.spotbot.core.backtest.Variant
import com.spotbot.core.backtest.WalkForward
import com.spotbot.core.backtest.WalkForwardReport
import com.spotbot.core.backtest.summary
import com.spotbot.core.backtest.with
import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.EntryMode
import com.spotbot.core.trading.ExecutionConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

private val researchJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

private val AB = setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK)
private val B = setOf(StrategyId.TREND_PULLBACK)
private val makerExec = ExecutionConfig(entryMode = EntryMode.LIMIT, limitTakeProfit = true)

/** Sonuçları görmeden önce sabitlenmiş deney seçenekleri (sonradan seçenek eklemek aşırı uyuma yol açar). */
val PRE_REGISTERED_VARIANTS = listOf(
    Variant("V0 Mevcut: A+B, piyasa emri", AB, ExecutionConfig()),
    Variant("V1 Yalnız B, piyasa emri", B, ExecutionConfig()),
    Variant("V2 A+B, limit giriş/hedef (maker)", AB, makerExec),
    Variant("V3 V2 + başa baş 1R", AB, makerExec.copy(breakEvenAtR = 1.0)),
    Variant("V4 V2 + %50 kısmi kâr 1R + başa baş", AB, makerExec.copy(partialTakeProfitAtR = 1.0, breakEvenAtR = 1.0)),
    Variant("V5 V2 + 48 saat süre sınırı", AB, makerExec.copy(maxHoldingHours = 48)),
    Variant("V6 V4 + coin dinlenmesi", AB, makerExec.copy(partialTakeProfitAtR = 1.0, breakEvenAtR = 1.0, cooldownTrades = 3)),
    Variant("V7 Yalnız B + V4", B, makerExec.copy(partialTakeProfitAtR = 1.0, breakEvenAtR = 1.0)),
)

@Serializable
data class VariantResult(val name: String, val inSample: RunSummary, val outOfSample: RunSummary)

@Serializable
data class ResearchReport(
    val symbols: List<String>,
    val start: Long,
    val split: Long,
    val end: Long,
    val variants: List<VariantResult>,
    val walkForward: WalkForwardReport,
    val monteCarlo: Map<String, MonteCarloResult?>,
    val shadow: Map<String, ShadowReport?>,
)

suspend fun research(options: Map<String, String>) {
    val dir = File(options["data"] ?: error("--data klasörü gerekli"))
    val data = dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sorted().associate { f ->
        val d = researchJson.decodeFromString(HistoricalDataset.serializer(), f.readText())
        d.symbol to d
    }
    val info: Map<String, SymbolInfo> = runCatching { MexcRestClient().exchangeInfo().associateBy { it.symbol } }.getOrDefault(emptyMap())
    val symbols = data.keys.toList()
    val firstM15 = data.values.maxOf { it.m15.first().openTime }
    val start = firstM15 + 2 * 24 * 3_600_000L
    val end = data.values.minOf { it.m15.last().closeTime }
    val split = start + (end - start) / 2
    val base = BacktestConfig(symbols, start, end, scanner = ScannerConfig(extraCoins = 0))
    println("Veri: ${symbols.size} coin · ${day.format(Instant.ofEpochMilli(start))} → ${day.format(Instant.ofEpochMilli(end))} · bölme ${day.format(Instant.ofEpochMilli(split))}")

    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    fun <T> parallel(jobs: List<() -> T>): List<T> = jobs.map { pool.submit<T> { it() } }.map { it.get() }

    val t0 = System.currentTimeMillis()
    val halves = parallel(
        PRE_REGISTERED_VARIANTS.flatMap { v ->
            listOf({ BacktestEngine(base.with(v, start, split)).run(data, info) }, { BacktestEngine(base.with(v, split, end)).run(data, info) })
        },
    )
    val variantResults = PRE_REGISTERED_VARIANTS.mapIndexed { i, v -> VariantResult(v.name, halves[2 * i].summary(), halves[2 * i + 1].summary()) }
    println("İlk yarı / ikinci yarı tamamlandı (${(System.currentTimeMillis() - t0) / 1000} sn)\n")
    printVariants(variantResults)

    val month = 30L * 24 * 3_600_000L
    val wf = WalkForward.run(base, PRE_REGISTERED_VARIANTS, data, info, start, end, inSampleMillis = 3 * month, outOfSampleMillis = 2 * month, runner = { parallel(it) })
    println("\n── İleri yürüyen test (3 ay seçim → sonraki 2 ay görülmemiş dönem) ──")
    wf.folds.forEach { f ->
        println(
            "  %s → %s  seçilen: %-38s  IS %+.2fR  →  OOS %3d işlem, %+.2f%%, PF %s, %+.2fR".format(
                day.format(Instant.ofEpochMilli(f.outOfSampleStart)), day.format(Instant.ofEpochMilli(f.outOfSampleEnd)), f.chosen,
                f.inSampleExpectancyR, f.outOfSample.trades, f.outOfSample.netReturnPercent, pf(f.outOfSample.profitFactor), f.outOfSample.expectancyR,
            ),
        )
    }
    println("  Görülmemiş dönemlerin toplamı: ${line(wf.combined)}")

    val full = parallel(
        listOf(PRE_REGISTERED_VARIANTS[0], PRE_REGISTERED_VARIANTS[2]).map { v ->
            { BacktestEngine(base.with(v, start, end).copy(shadowAnalysis = true)).run(data, info) }
        },
    )
    pool.shutdown()
    val monteCarlo = mapOf(
        "Ileri yuruyen test (gorulmemis donemler)" to MonteCarlo.run(wf.combinedPnls, base.initialCapital),
        PRE_REGISTERED_VARIANTS[0].name to MonteCarlo.run(full[0].closedTrades.map { it.netPnl }, base.initialCapital),
        PRE_REGISTERED_VARIANTS[2].name to MonteCarlo.run(full[1].closedTrades.map { it.netPnl }, base.initialCapital),
    )
    println("\n── Monte Carlo (5000 yeniden örnekleme) ──")
    monteCarlo.forEach { (name, r) ->
        if (r == null) println("  $name: yetersiz işlem") else println(
            "  %-40s getiri %%5: %+.1f%%  ortanca: %+.1f%%  %%95: %+.1f%% · maks. düşüş ortanca %.1f%%, %%95 %.1f%% · zarar olasılığı %%%.0f".format(
                name, r.returnP5, r.returnP50, r.returnP95, r.maxDrawdownP50, r.maxDrawdownP95, r.lossProbabilityPercent,
            ),
        )
    }
    val shadows = mapOf(PRE_REGISTERED_VARIANTS[0].name to full[0].shadow, PRE_REGISTERED_VARIANTS[2].name to full[1].shadow)
    shadows.forEach { (name, s) -> s?.let { printShadow(name, it) } }

    val report = ResearchReport(symbols, start, split, end, variantResults, wf, monteCarlo, shadows)
    options["out"]?.let { File(it).writeText(researchJson.encodeToString(ResearchReport.serializer(), report)); println("\nRapor: $it") }
}

private fun pf(v: Double) = if (v.isInfinite()) "∞" else "%.2f".format(v)

private fun line(s: RunSummary) = "%3d işlem · net %+6.2f%% · PF %s · %+.3fR/işlem · kazanma %%%.0f · maks. düşüş %.1f%%".format(
    s.trades, s.netReturnPercent, pf(s.profitFactor), s.expectancyR, s.winRatePercent, s.maxDrawdownPercent,
)

private fun printVariants(results: List<VariantResult>) {
    println("%-40s │ %-72s │ %s".format("Seçenek", "İlk yarı (in-sample)", "İkinci yarı (out-of-sample)"))
    results.forEach { r -> println("%-40s │ %s │ %s".format(r.name, line(r.inSample), line(r.outOfSample))) }
}

private fun printShadow(name: String, s: ShadowReport) {
    println("\n── Gölge işlemler: $name ──")
    println(
        "  %d kurulum simüle edildi · ortalama %+.3fR · kazanma %%%.0f · kabul edilen %d (%s) · reddedilen %d (%s) · limit dolmayan %d".format(
            s.trades, s.avgR, s.winRatePercent, s.acceptedCount, s.acceptedAvgR?.let { "%+.3fR".format(it) } ?: "-",
            s.rejectedCount, s.rejectedAvgR?.let { "%+.3fR".format(it) } ?: "-", s.limitNotFilled,
        ),
    )
    println("  Strateji: " + s.byStrategy.entries.joinToString { "${it.key} %+.3fR".format(it.value) })
    println("  Kalite puanı → sonuç: " + s.scoreBuckets.joinToString { "${it.label}: ${it.count} kurulum, %+.3fR, kazanma %%%.0f".format(it.avgR, it.winRatePercent) })
    println("  Kural katkısı (geçince ort. R − kalmayınca ort. R):")
    s.rules.forEach { r ->
        println("    %+.3fR  %-4s %-55s geçti %4d (%+.3fR) · kaldı %4d (%+.3fR)".format(r.lift, r.id, r.title.take(55), r.passCount, r.passAvgR, r.failCount, r.failAvgR))
    }
}
