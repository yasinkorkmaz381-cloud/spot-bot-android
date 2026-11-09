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
import com.spotbot.core.exchange.klinesBetween
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


/** Strateji profillerini aynı koşullarda karşılaştırır (ilk yarı / ikinci yarı ayrımıyla). */
suspend fun compare(options: Map<String, String>) {
    val dir = File(options["data"] ?: error("--data klasörü gerekli"))
    val data = dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sorted().associate { f ->
        val d = researchJson.decodeFromString(HistoricalDataset.serializer(), f.readText())
        d.symbol to d
    }
    val info: Map<String, SymbolInfo> = runCatching { MexcRestClient().exchangeInfo().associateBy { it.symbol } }.getOrDefault(emptyMap())
    val start = data.values.maxOf { it.m15.first().openTime } + 2 * 24 * 3_600_000L
    val end = data.values.minOf { it.m15.last().closeTime }
    val base = BacktestConfig(data.keys.toList(), start, end, scanner = ScannerConfig(extraCoins = 0))
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val results = com.spotbot.core.backtest.StrategyComparison.run(base, data, info, runner = { jobs -> jobs.map { pool.submit<BacktestReport> { it() } }.map { it.get() } })
    pool.shutdown()
    println("Aynı koşullarda profil karşılaştırması · ${data.size} coin · ${day.format(Instant.ofEpochMilli(start))} → ${day.format(Instant.ofEpochMilli(end))}")
    results.forEach { r ->
        println("\n${r.profile.title} — ${r.profile.description}")
        println("  Tüm dönem : ${line(r.full)}")
        println("  İlk yarı  : ${line(r.firstHalf)}")
        println("  İkinci yarı: ${line(r.secondHalf)}")
        println("  Kısmi gerçekleşme: ${r.partialFills} · gerçekleşmeyen emir: ${r.rejectedOrders}")
    }
    options["out"]?.let { File(it).writeText(researchJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.spotbot.core.backtest.ProfileComparison.serializer()), results)); println("\nRapor: $it") }
}

@kotlinx.serialization.Serializable
private data class SubBarCache(
    val candles: Map<String, List<com.spotbot.core.model.Candle>> = emptyMap(),
    /** 5M verisi istenip MEXC'den boş dönen pencereler (tekrar istenmez). */
    val unavailable: Set<String> = emptySet(),
)

@kotlinx.serialization.Serializable
private data class AnalyzeReport(
    val symbols: List<String>,
    val start: Long,
    val split: Long,
    val end: Long,
    val runs: List<com.spotbot.core.backtest.IsolatedRun>,
    val baselineMonthly: List<com.spotbot.core.backtest.PeriodReturn>,
    val regimeShare: Map<String, Double>,
    val losses: com.spotbot.core.backtest.LossAnalysisReport,
)

/**
 * Kayıp analizi ve rejim filtresi deneyi: A+B, yalnız A, yalnız B ve rejim politikaları aynı koşullarda (tüm dönem,
 * ilk ve ikinci yarı); belirsiz mumlar 5M veriyle çözülür; varsayılan çalıştırmanın işlemleri giriş özellikleriyle
 * eşleştirilip dilimlere ayrılır ve önceden tanımlı filtreler iki yarıda ayrı ölçülür.
 */
suspend fun analyze(options: Map<String, String>) {
    val dir = File(options["data"] ?: error("--data klasörü gerekli"))
    val api = MexcRestClient()
    var data = dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sorted().associate { f ->
        val d = researchJson.decodeFromString(HistoricalDataset.serializer(), f.readText())
        d.symbol to d
    }
    val cacheFile = File(dir, "m5-cache.json")
    var cache = if (cacheFile.exists()) researchJson.decodeFromString(SubBarCache.serializer(), cacheFile.readText()) else SubBarCache()
    fun withCache(d: Map<String, HistoricalDataset>) = d.mapValues { (s, ds) -> ds.copy(m5 = cache.candles[s].orEmpty()) }
    data = withCache(data)
    val info: Map<String, SymbolInfo> = runCatching { api.exchangeInfo().associateBy { it.symbol } }.getOrDefault(emptyMap())
    val start = data.values.maxOf { it.m15.first().openTime } + 2 * 24 * 3_600_000L
    val end = data.values.minOf { it.m15.last().closeTime }
    val split = start + (end - start) / 2
    val base = BacktestConfig(data.keys.toList(), start, end, scanner = ScannerConfig(extraCoins = 0))
    val settings = com.spotbot.core.backtest.StrategyIsolation.defaultSettings + listOf(
        com.spotbot.core.backtest.StrategyIsolation.Setting("A + B · rejim: Koruyucu", base.enabledStrategies, com.spotbot.core.analysis.RegimePolicy.PROTECTIVE),
        com.spotbot.core.backtest.StrategyIsolation.Setting("A + B · rejim: Tam", base.enabledStrategies, com.spotbot.core.analysis.RegimePolicy.FULL),
        // F4'ün (stop ≥ 1 ATR) gerçek yeniden testi: mevcut ATR stop tabanı seçeneği aynı eşikle (1,0 × ATR 1H).
        com.spotbot.core.backtest.StrategyIsolation.Setting(
            "A + B · ATR stop tabanı 1,0", base.enabledStrategies,
            risk = base.risk.copy(atrStopMode = com.spotbot.core.risk.AtrStopMode.FLOOR, atrStopMultiple = 1.0),
        ),
    )
    val configs = settings.flatMap { com.spotbot.core.backtest.StrategyIsolation.configs(base, it) }
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    var reports: List<BacktestReport> = emptyList()
    for (pass in 1..4) {
        val current = data
        print("\rGeçiş $pass: ${configs.size} backtest çalışıyor…")
        reports = configs.map { c -> pool.submit<BacktestReport> { BacktestEngine(c).run(current, info) } }.map { it.get() }
        val missing = reports.flatMap { it.missingSubBars }.distinct()
            .filter { "${it.symbol}@${it.openTime}" !in cache.unavailable && cache.candles[it.symbol].orEmpty().none { c -> c.openTime == it.openTime } }
        println(" belirsiz ve 5M verisi eksik mum: ${missing.size}")
        if (missing.isEmpty()) break
        val fetched = HashMap(cache.candles)
        val unavailable = HashSet(cache.unavailable)
        missing.forEach { w ->
            val bars = api.klinesBetween(w.symbol, com.spotbot.core.model.Timeframe.M5, w.openTime, w.openTime + com.spotbot.core.model.Timeframe.M15.millis)
                .filter { it.openTime >= w.openTime && it.openTime < w.openTime + com.spotbot.core.model.Timeframe.M15.millis }
            if (bars.size < 3) unavailable += "${w.symbol}@${w.openTime}"
            else fetched[w.symbol] = (fetched[w.symbol].orEmpty() + bars).distinctBy { it.openTime }.sortedBy { it.openTime }
        }
        cache = SubBarCache(fetched, unavailable)
        cacheFile.writeText(researchJson.encodeToString(SubBarCache.serializer(), cache))
        data = withCache(data)
    }
    pool.shutdown()
    val runs = settings.mapIndexed { i, s -> com.spotbot.core.backtest.StrategyIsolation.summarize(s, reports.subList(3 * i, 3 * i + 3)) }
    val baseline = reports[0]
    val losses = com.spotbot.core.backtest.LossAnalysis.run(baseline, split)
    val btc4h = com.spotbot.core.analysis.Series("BTCUSDT", com.spotbot.core.model.Timeframe.H4, data.getValue("BTCUSDT").h4)
    val regimes = (0 until btc4h.size).filter { btc4h.candles[it].closeTime in start..end }.mapNotNull { com.spotbot.core.analysis.RegimeClassifier.classify(btc4h.analyze(it)) }
    val regimeShare = regimes.groupingBy { it.title }.eachCount().mapValues { it.value * 100.0 / regimes.size }

    fun stats(s: com.spotbot.core.backtest.PerformanceStats) =
        "%4d işlem %3dK/%3dZ kazanma %%%5.1f PF %5.2f ort %+.3fR beklenen %+6.3f net %%%+6.2f maks.düşüş %%%5.2f ücret %6.2f".format(
            s.trades, s.wins, s.losses, s.winRatePercent, s.profitFactor, s.averageR, s.expectancy, s.netReturnPercent, s.maxDrawdownPercent, s.fees,
        )
    println("\nAynı koşullar · ${data.size - 1} işlem coini + BTC · ${day.format(Instant.ofEpochMilli(start))} → ${day.format(Instant.ofEpochMilli(end))} · bölme ${day.format(Instant.ofEpochMilli(split))}")
    println("Maliyet: taker ücreti (MEXC parite bilgisi), kayma %0,05, yarım spread %0,025, mum hacminin %10'u; sermaye 1000 USDT; risk %0,5")
    runs.forEach { r ->
        println("\n${r.label}")
        println("  Tüm dönem  : ${stats(r.full)}")
        println("  İlk yarı   : ${stats(r.firstHalf)}")
        println("  İkinci yarı: ${stats(r.secondHalf)}")
        val ib = r.intrabar
        println("  Aynı mumda stop+hedef: ${ib.ambiguousBars} (5M: hedef önce ${ib.resolvedTargetFirst}, stop önce ${ib.resolvedStopFirst}; belirsiz: 5M yok ${ib.unresolvedNoData}, aynı 5M ${ib.unresolvedSameSubBar})")
    }
    println("\nBTC 4H rejim dağılımı (dönem içi 4H mumların %'si): " + regimeShare.entries.sortedByDescending { it.value }.joinToString { "%s %%%.1f".format(it.key, it.value) })
    println("\nVarsayılan (A + B) aylık özsermaye değişimi:")
    baseline.monthlyReturns.forEach { m -> println("  %s  %%%+6.2f  (%d işlem, net %+.2f)".format(m.label, m.changePercent, m.tradesClosed, m.realizedPnl)) }
    println("\nZararlı işlemlerin profili (varsayılan çalıştırma):")
    losses.lossProfiles.forEach { p ->
        println("  %-20s zararlı %3d · anında başarısız (lehte < 0,3R) %3d · önce ≥1R lehte gidip zarar %3d · stop %3d · yapısal bozulma %3d · medyan süre %d mum · medyan lehte hareket %.2fR".format(
            p.strategy, p.losers, p.immediateFailures, p.gaveBackAfterOneR, p.stopLosses, p.invalidations, p.medianBarsHeld, p.medianMfeR))
    }
    println("\nÖzellik dilimleri (ort. R; ilk yarı / ikinci yarı):")
    losses.buckets.groupBy { it.feature }.forEach { (feature, rows) ->
        println("  $feature")
        rows.forEach { b ->
            println("    %-20s %-26s %4d işlem kazanma %%%5.1f ort %+.2fR  | %3d: %s / %3d: %s".format(
                b.strategy, b.bucket, b.trades, b.winRatePercent, b.averageR,
                b.firstHalfTrades, b.firstHalfAverageR?.let { "%+.2fR".format(it) } ?: "-", b.secondHalfTrades, b.secondHalfAverageR?.let { "%+.2fR".format(it) } ?: "-"))
        }
    }
    println("\nÖnceden tanımlı filtreler (sonradan alt küme; aday = iki yarıda da ort. R artıyor ve her yarıda ≥ ${com.spotbot.core.backtest.LossAnalysis.MIN_TRADES_PER_HALF} işlem):")
    losses.filters.forEach { f ->
        println("  %s %-44s %-20s kalan %3d/%3d ort %+.2fR (temel %+.2fR, çıkarılan %s) · ilk yarı %s (%d) · ikinci yarı %s (%d)%s".format(
            f.id, f.description, f.strategy, f.keptTrades, f.baselineTrades, f.keptAverageR, f.baselineAverageR, f.removedAverageR?.let { "%+.2fR".format(it) } ?: "-",
            f.firstHalfDeltaR?.let { "%+.2fR".format(it) } ?: "-", f.firstHalfKept, f.secondHalfDeltaR?.let { "%+.2fR".format(it) } ?: "-", f.secondHalfKept,
            if (f.candidate) "  ← aday" else ""))
    }
    options["out"]?.let {
        File(it).writeText(researchJson.encodeToString(AnalyzeReport.serializer(), AnalyzeReport(data.keys.toList(), start, split, end, runs, baseline.monthlyReturns, regimeShare, losses)))
        println("\nRapor: $it")
    }
}
