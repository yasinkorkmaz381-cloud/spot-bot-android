package com.spotbot.cli

import com.spotbot.core.analysis.Regime
import com.spotbot.core.backtest.BacktestConfig
import com.spotbot.core.backtest.BacktestEngine
import com.spotbot.core.backtest.BacktestReport
import com.spotbot.core.backtest.EntryFilters
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.backtest.PerformanceStats
import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.strategy.StrategyId
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

private val studyDay = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
private val studyJson = Json { ignoreUnknownKeys = true }
private val A = StrategyId.BREAKOUT_RETEST
private val B = StrategyId.TREND_PULLBACK

private fun row(label: String, s: PerformanceStats) =
    "  %-34s %4d işlem %3dK/%3dZ kazanma %%%5.1f PF %5.2f ort %+.3fR beklenen %+6.3f net %%%+6.2f düşüş %%%5.2f ücret %6.2f".format(
        label, s.trades, s.wins, s.losses, s.winRatePercent, s.profitFactor, s.averageR, s.expectancy, s.netReturnPercent, s.maxDrawdownPercent, s.fees,
    )

/**
 * Strateji çalışması (önceden tanımlı kurallarla; sonuçlara göre eşik ayarlanmaz):
 * 1) A zararlılarının ortak nedenleri, B kazanan/kaybeden karşılaştırması.
 * 2) Her filtre (F1–F8) A ve B için ayrı gerçek backtest: eğitim (ilk yarı) ve test (ikinci yarı).
 * 3) Seçim kuralı: eğitimde ort. R'yi en çok artıran tek filtre (en az [MIN_TRAIN] işlem). Strateji, seçilen filtreyle
 *    eğitimde ort. R > 0 değilse "doğrulanmış" yapılandırmaya alınmaz.
 * 4) Üç yapılandırma (mevcut A+B, yalnız B, doğrulanmış) standart maliyetle ve ölçülen MEXC spread'leriyle.
 * 5) İleri yürüyen test: 3 ay eğitim → 2 ay test, aynı seçim kuralı her katmanda yeniden uygulanır.
 */
suspend fun study(options: Map<String, String>) {
    val dir = File(options["data"] ?: error("--data klasörü gerekli"))
    val m5 = File(dir, "m5-cache.json").takeIf { it.exists() }
    val data = dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sorted().associate { f ->
        val d = studyJson.decodeFromString(HistoricalDataset.serializer(), f.readText())
        d.symbol to d
    }
    val spreads: Map<String, Double> = File(dir, "spreads.json").takeIf { it.exists() }
        ?.let { studyJson.decodeFromString(MapSerializer(String.serializer(), Double.serializer()), it.readText()) }.orEmpty()
    val info: Map<String, SymbolInfo> = runCatching { MexcRestClient().exchangeInfo().associateBy { it.symbol } }.getOrDefault(emptyMap())
    val start = data.values.maxOf { it.m15.first().openTime } + 2 * 24 * 3_600_000L
    val end = data.values.minOf { it.m15.last().closeTime }
    val split = start + (end - start) / 2
    val base = BacktestConfig(data.keys.toList(), start, end, scanner = ScannerConfig(extraCoins = 0))
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    fun runAll(configs: List<BacktestConfig>): List<BacktestReport> = configs.map { c -> pool.submit<BacktestReport> { BacktestEngine(c).run(data, info) } }.map { it.get() }
    fun cfg(strategies: Set<StrategyId>, filters: Map<StrategyId, Set<String>>, from: Long, to: Long, realSpread: Boolean = false) = base.copy(
        startTime = from, endTime = to, enabledStrategies = strategies,
        strategyParams = base.strategyParams.copy(entryFilters = filters),
        halfSpreadBySymbol = if (realSpread) spreads else emptyMap(),
    )
    println("Veri: ${data.size} coin · ${studyDay.format(Instant.ofEpochMilli(start))} → ${studyDay.format(Instant.ofEpochMilli(end))} · eğitim/test bölmesi ${studyDay.format(Instant.ofEpochMilli(split))}${if (m5 != null) "" else ""}")

    // ---- 1) Mevcut stratejilerin işlem düzeyi incelemesi (tüm dönem, mevcut A+B) ----
    val baseline = runAll(listOf(cfg(setOf(A, B), emptyMap(), start, end))).single()
    val features = baseline.entryFeatures.associateBy { it.symbol to it.signalTime }
    val rows = baseline.closedTrades.mapNotNull { t -> features[t.position.symbol to t.position.setup.signalTime]?.let { Triple(t, it, baseline.excursions[t.position.id]) } }
    val aLosers = rows.filter { it.first.position.strategy == A && it.first.netPnl <= 0 }
    println("\nStrateji A zararlı işlemlerin ortak nedenleri (${aLosers.size} zararlı / ${rows.count { it.first.position.strategy == A }} işlem; bir işlem birden çok gruba girebilir):")
    listOf<Pair<String, (Triple<com.spotbot.core.trading.ClosedTrade, com.spotbot.core.backtest.TradeFeatures, com.spotbot.core.backtest.Excursion?>) -> Boolean>>(
        "Sahte kırılım: lehte 0,3R bile gitmeden döndü" to { (it.third?.mfeR ?: 0.0) < 0.3 },
        "Kırılım tuttu ama hedefe varmadan döndü (≥1R lehte gidip zarar)" to { (it.third?.mfeR ?: 0.0) >= 1.0 },
        "BTC 4H yükseliş trendinde değildi" to { it.second.btcH4Trend != Regime.TREND_UP },
        "4H rejimi yatay (trend yokken kırılım)" to { it.second.h4Trend == Regime.RANGE },
        "4H ve 1H trend uyumsuz" to { !(it.second.h4Trend == Regime.TREND_UP && it.second.h1Trend == Regime.TREND_UP) },
        "Stop dar (< 1 ATR 1H)" to { it.second.stopDistanceAtr1h < 1.0 },
        "Tetik mumunda hacim zayıf (< 1,5× ortalama)" to { it.second.volumeRatio15m < 1.5 },
        "1H ADX < 20 (trend gücü yok)" to { it.second.adx1h < 20 },
        "1H RSI negatif uyumsuzluk" to { it.second.bearishRsiDivergence1h },
    ).forEach { (label, test) ->
        val n = aLosers.count(test)
        val winners = rows.filter { it.first.position.strategy == A && it.first.netPnl > 0 }
        println("  %-62s %3d zararlı (%%%3.0f) · kazananlarda %d/%d".format(label, n, n * 100.0 / aLosers.size.coerceAtLeast(1), winners.count(test), winners.size))
    }
    println("\nStrateji B kazanan ve kaybeden işlemlerin giriş anı ortalamaları:")
    val bRows = rows.filter { it.first.position.strategy == B }
    fun avg(list: List<Triple<com.spotbot.core.trading.ClosedTrade, com.spotbot.core.backtest.TradeFeatures, com.spotbot.core.backtest.Excursion?>>, f: (com.spotbot.core.backtest.TradeFeatures) -> Double) =
        if (list.isEmpty()) 0.0 else list.sumOf { f(it.second) } / list.size
    val (bw, bl) = bRows.partition { it.first.netPnl > 0 }
    listOf<Pair<String, (com.spotbot.core.backtest.TradeFeatures) -> Double>>(
        "1H ADX" to { it.adx1h }, "4H ADX" to { it.adx4h }, "1H RSI" to { it.rsi1h }, "15M hacim oranı" to { it.volumeRatio15m },
        "Stop mesafesi (ATR)" to { it.stopDistanceAtr1h }, "1H ATR %" to { it.atrPercent1h }, "Planlanan R:R" to { it.plannedRewardRisk }, "Kalite puanı" to { it.qualityScore },
        "BTC 4H yükseliş (oran)" to { if (it.btcH4Trend == Regime.TREND_UP) 1.0 else 0.0 }, "4H/1H uyumlu (oran)" to { if (it.h4Trend == Regime.TREND_UP && it.h1Trend == Regime.TREND_UP) 1.0 else 0.0 },
        "RSI uyumsuzluk (oran)" to { if (it.bearishRsiDivergence1h) 1.0 else 0.0 }, "MACD yükseliyor (oran)" to { if (it.macdHistogram1hRising) 1.0 else 0.0 },
    ).forEach { (label, f) -> println("  %-26s kazanan (%d) %7.2f · kaybeden (%d) %7.2f".format(label, bw.size, avg(bw, f), bl.size, avg(bl, f))) }

    // ---- 2) Filtrelerin gerçek yeniden testi: eğitim ve test ayrı ----
    val variants: List<Pair<String, Set<String>>> = listOf("filtresiz" to emptySet<String>()) + EntryFilters.all.map { "${it.id} ${it.description}" to setOf(it.id) }
    val chosen = HashMap<StrategyId, Set<String>>()
    val trainPositive = HashMap<StrategyId, Boolean>()
    for (strategy in listOf(A, B)) {
        val reports = runAll(variants.flatMap { (_, f) -> listOf(cfg(setOf(strategy), mapOf(strategy to f), start, split), cfg(setOf(strategy), mapOf(strategy to f), split, end)) })
        println("\nStrateji ${strategy.code} — her filtre ayrı backtest (eğitim: ilk yarı | test: ikinci yarı)")
        val train = variants.indices.map { reports[2 * it].overall }
        variants.forEachIndexed { i, (label, _) ->
            val tr = train[i]; val te = reports[2 * i + 1].overall
            println("  %-44s eğitim %3d işlem %+.3fR PF %4.2f net %%%+5.2f | test %3d işlem %+.3fR PF %4.2f net %%%+5.2f".format(
                label, tr.trades, tr.averageR, tr.profitFactor, tr.netReturnPercent, te.trades, te.averageR, te.profitFactor, te.netReturnPercent))
        }
        val best = variants.indices.filter { train[it].trades >= MIN_TRAIN }.maxByOrNull { train[it].averageR } ?: 0
        chosen[strategy] = variants[best].second
        trainPositive[strategy] = train[best].averageR > 0
        println("  → Eğitimde seçilen: ${variants[best].first} (ort %+.3fR, %d işlem) · eğitimde pozitif mi: ${if (train[best].averageR > 0) "EVET" else "HAYIR"} · aynı seçimin testi: %+.3fR, %d işlem".format(
            train[best].averageR, train[best].trades, reports[2 * best + 1].overall.averageR, reports[2 * best + 1].overall.trades))
    }
    val validated = listOf(A, B).filter { trainPositive[it] == true }.toSet()

    // ---- 3) Üç yapılandırma: aynı veri, sermaye ve maliyet ----
    data class Setup(val label: String, val strategies: Set<StrategyId>, val filters: Map<StrategyId, Set<String>>)
    val setups = listOf(
        Setup("A · Mevcut A + B", setOf(A, B), emptyMap()),
        Setup("B · Yalnız mevcut B", setOf(B), emptyMap()),
        Setup("C · Eğitimde seçilen (${if (validated.isEmpty()) "hiçbiri pozitif değil" else validated.joinToString("+") { it.code + chosen[it]!!.joinToString(",", "[", "]") }})", validated, chosen.filterKeys { it in validated }),
        Setup("(bilgi) A+B, seçilen filtrelerle", setOf(A, B), chosen),
    )
    for (real in listOf(false, true)) {
        if (real && spreads.isEmpty()) continue
        println("\nYapılandırma karşılaştırması — ${if (real) "ölçülen MEXC spread'leriyle (10.11.2026 ölçümü geçmişe uygulanır; spread > %0,20 olan coin canlıdaki gibi elenir)" else "standart maliyet (spread %0,05)"}")
        val reports = runAll(setups.flatMap { s -> if (s.strategies.isEmpty()) emptyList() else listOf(cfg(s.strategies, s.filters, start, end, real), cfg(s.strategies, s.filters, start, split, real), cfg(s.strategies, s.filters, split, end, real)) })
        var k = 0
        setups.forEach { s ->
            println(s.label)
            if (s.strategies.isEmpty()) { println("  İşlem yok: hiçbir strateji eğitimde pozitif beklenen değer göstermedi (USDT'de beklenir, getiri %0)."); return@forEach }
            println(row("Tüm dönem", reports[k].overall)); println(row("Eğitim (ilk yarı)", reports[k + 1].overall)); println(row("Test (ikinci yarı)", reports[k + 2].overall))
            if (k == 0) println("  BTC al-tut: tüm dönem %s · eğitim %s · test %s".format(reports[0].btcBuyAndHoldPercent?.let { "%%%+.2f".format(it) }, reports[1].btcBuyAndHoldPercent?.let { "%%%+.2f".format(it) }, reports[2].btcBuyAndHoldPercent?.let { "%%%+.2f".format(it) }))
            k += 3
        }
    }

    // ---- 4) İleri yürüyen test ----
    val month = 30L * 24 * 3_600_000
    println("\nİleri yürüyen test (3 ay eğitim → 2 ay test; her katmanda strateji başına en iyi tek filtre eğitimde seçilir, eğitimde ort. R ≤ 0 olan strateji o katmanda işlem yapmaz)")
    var oosStart = start + 3 * month
    val oosTrades = ArrayList<com.spotbot.core.trading.ClosedTrade>()
    val baseTrades = ArrayList<com.spotbot.core.trading.ClosedTrade>()
    while (oosStart < end - 7 * 24 * 3_600_000L) {
        val oosEnd = minOf(end, oosStart + 2 * month)
        val trainReports = runAll(listOf(A, B).flatMap { st -> variants.map { (_, f) -> cfg(setOf(st), mapOf(st to f), oosStart - 3 * month, oosStart) } })
        val pick = HashMap<StrategyId, Set<String>>()
        val notes = ArrayList<String>()
        listOf(A, B).forEachIndexed { si, st ->
            val tr = variants.indices.map { trainReports[si * variants.size + it].overall }
            val best = variants.indices.filter { tr[it].trades >= MIN_TRAIN_WF }.maxByOrNull { tr[it].averageR }
            if (best != null && tr[best].averageR > 0) { pick[st] = variants[best].second; notes += "${st.code}: ${variants[best].first} (%+.2fR, %d)".format(tr[best].averageR, tr[best].trades) }
            else notes += "${st.code}: işlem yok (eğitim en iyi ${best?.let { "%+.2fR".format(tr[it].averageR) } ?: "yetersiz işlem"})"
        }
        val tests = runAll(listOfNotNull(cfg(setOf(A, B), emptyMap(), oosStart, oosEnd), if (pick.isNotEmpty()) cfg(pick.keys, pick, oosStart, oosEnd) else null))
        baseTrades += tests[0].closedTrades
        val sel = tests.getOrNull(1)
        sel?.let { oosTrades += it.closedTrades }
        println("  ${studyDay.format(Instant.ofEpochMilli(oosStart))} → ${studyDay.format(Instant.ofEpochMilli(oosEnd))} · ${notes.joinToString(" · ")}")
        println("      test: seçilen %s | mevcut A+B %d işlem %+.3fR net %%%+.2f".format(
            sel?.overall?.let { "%d işlem %+.3fR net %%%+.2f".format(it.trades, it.averageR, it.netReturnPercent) } ?: "işlem yok (%0)", tests[0].overall.trades, tests[0].overall.averageR, tests[0].overall.netReturnPercent))
        oosStart = oosEnd
    }
    println(row("İleri yürüyen: seçilen (birleşik)", PerformanceStats.of(oosTrades, base.initialCapital)))
    println(row("Aynı dönemlerde mevcut A+B", PerformanceStats.of(baseTrades, base.initialCapital)))
    if (oosTrades.size < 30) println("  ⚠ Seçilen yapılandırmanın görülmemiş dönem işlem sayısı (${oosTrades.size}) istatistiksel değerlendirme için yetersiz.")
    pool.shutdown()
}

private const val MIN_TRAIN = 15
private const val MIN_TRAIN_WF = 8

/** −%20,99 (sabit spread) ile ölçülen spread sonucunun farkını işlem evreni, maliyet ve yol etkisine ayırır. */
suspend fun costSplit(options: Map<String, String>) {
    val dir = File(options["data"] ?: error("--data klasörü gerekli"))
    val data = dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sorted().associate { f ->
        val d = studyJson.decodeFromString(HistoricalDataset.serializer(), f.readText()); d.symbol to d
    }
    val spreads: Map<String, Double> = studyJson.decodeFromString(MapSerializer(String.serializer(), Double.serializer()), File(dir, "spreads.json").readText())
    val info: Map<String, SymbolInfo> = runCatching { MexcRestClient().exchangeInfo().associateBy { it.symbol } }.getOrDefault(emptyMap())
    val start = data.values.maxOf { it.m15.first().openTime } + 2 * 24 * 3_600_000L
    val end = data.values.minOf { it.m15.last().closeTime }
    val base = BacktestConfig(data.keys.toList(), start, end, scanner = ScannerConfig(extraCoins = 0))
    val std = BacktestEngine(base).run(data, info)
    val mea = BacktestEngine(base.copy(halfSpreadBySymbol = spreads)).run(data, info)
    val excluded = spreads.filterValues { it * 200 > base.scanner.maxSpreadPercent }.keys.sorted()
    fun key(t: com.spotbot.core.trading.ClosedTrade) = t.position.symbol to t.position.setup.signalTime
    val sMap = std.closedTrades.associateBy(::key)
    val mMap = mea.closedTrades.associateBy(::key)
    val common = sMap.keys intersect mMap.keys
    val onlyStd = std.closedTrades.filter { key(it) !in mMap }
    val onlyMea = mea.closedTrades.filter { key(it) !in sMap }
    println("Sabit spread: ${std.trades} işlem, net %%%+.2f · ölçülen spread: ${mea.trades} işlem, net %%%+.2f · fark %+.2f puan".format(std.netReturnPercent, mea.netReturnPercent, mea.netReturnPercent - std.netReturnPercent))
    println("Spread > %%%.2f nedeniyle ölçülen senaryoda elenen pariteler: %s".format(base.scanner.maxSpreadPercent, excluded.joinToString { "%s (%%%.3f)".format(it, spreads.getValue(it) * 200) }))
    val stdExcluded = onlyStd.filter { it.position.symbol in excluded }
    val stdOther = onlyStd.filter { it.position.symbol !in excluded }
    val commonStd = common.sumOf { sMap.getValue(it).netPnl }
    val commonMea = common.sumOf { mMap.getValue(it).netPnl }
    println("\nAyrıştırma (USDT; sermaye 1000):")
    println("  1) Elenen paritelerdeki işlemler (yalnız sabit senaryoda): %3d işlem, net %+8.2f".format(stdExcluded.size, stdExcluded.sumOf { it.netPnl }))
    println("  2) Ortak işlemler (aynı parite ve sinyal): %3d işlem · sabit %+8.2f · ölçülen %+8.2f · maliyet farkı %+7.2f".format(common.size, commonStd, commonMea, commonMea - commonStd))
    println("  3) Yol etkisi — yalnız sabit senaryoda açılan diğer işlemler: %3d işlem, net %+8.2f".format(stdOther.size, stdOther.sumOf { it.netPnl }))
    println("  4) Yol etkisi — yalnız ölçülen senaryoda açılan işlemler (boşalan yerler): %3d işlem, net %+8.2f".format(onlyMea.size, onlyMea.sumOf { it.netPnl }))
    println("  Kontrol: sabit = 1+2+3 = %+.2f (rapor %+.2f) · ölçülen = 2+4 = %+.2f (rapor %+.2f)".format(
        stdExcluded.sumOf { it.netPnl } + commonStd + stdOther.sumOf { it.netPnl }, std.finalEquity - std.initialCapital,
        commonMea + onlyMea.sumOf { it.netPnl }, mea.finalEquity - mea.initialCapital))
    println("\nParite bazında (sabit → ölçülen):")
    data.keys.sorted().forEach { s ->
        val a = std.closedTrades.filter { it.position.symbol == s }
        val b = mea.closedTrades.filter { it.position.symbol == s }
        if (a.isNotEmpty() || b.isNotEmpty()) println("  %-10s %3d işlem %+8.2f → %3d işlem %+8.2f   spread %%%.3f".format(s, a.size, a.sumOf { it.netPnl }, b.size, b.sumOf { it.netPnl }, (spreads[s] ?: 0.0) * 200))
    }
    println("\nİşlem başına maliyet: sabit %.3f USDT ücret + %.3f USDT spread/kayma · ölçülen %.3f + %.3f".format(
        std.totalFees / std.trades, std.spreadAndSlippageCost / std.trades, mea.totalFees / mea.trades, mea.spreadAndSlippageCost / mea.trades))
}
