package com.spotbot.cli

import com.spotbot.core.backtest.BacktestConfig
import com.spotbot.core.backtest.BacktestDataLoader
import com.spotbot.core.backtest.BacktestEngine
import com.spotbot.core.backtest.BacktestReport
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.backtest.runResolvingIntrabar
import com.spotbot.core.engine.EngineConfig
import com.spotbot.core.engine.EngineEvent
import com.spotbot.core.engine.StateStore
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.exchange.mexc.MexcWebSocketClient
import com.spotbot.core.model.Timeframe
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.PortfolioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

fun main(args: Array<String>) = runBlocking {
    val command = args.firstOrNull() ?: "help"
    val options = parseOptions(args.drop(1))
    when (command) {
        "scan" -> scan()
        "backtest" -> backtest(options)
        "paper" -> paper(options)
        "research" -> research(options)
        "compare" -> compare(options)
        "analyze" -> analyze(options)
        "study" -> study(options)
        "v3" -> v3(options)
        "costsplit" -> costSplit(options)
        "forward" -> forward(options)
        "v3concentration" -> v3Concentration(options)
        else -> println(
            """
            CryptoScan Pro — MEXC spot botu (sanal işlem)

            Komutlar:
              scan                         Canlı MEXC verisiyle bir tarama yapar ve sonucu yazdırır
              backtest [seçenekler]        Tarihsel veriyle backtest
                  --days 90                Test süresi (gün)
                  --symbols BTC,ETH,...    Coinler (varsayılan: izleme listesi)
                  --capital 1000           Başlangıç sermayesi (USDT)
                  --strategies A,B         Etkin stratejiler (C yalnızca açıkça istenirse)
                  --out rapor.json         Raporu JSON olarak kaydet
                  --end 2026-11-09         Dönem sonu (UTC gün sonu; varsayılan: son 4H kapanışı)
                  --warmup 45              Gösterge ısınması için ek gün
              paper [--data klasör]        7/24 sanal işlem motorunu çalıştırır (Ctrl+C ile durur)
              research --data klasör       Önceden tanımlı seçenekleri ilk/ikinci yarıda, ileri yürüyen testte,
                       [--out rapor.json]  Monte Carlo ve gölge işlemlerle karşılaştırır
              compare --data klasör        Adaptif / Trend / Seçici profillerini aynı koşullarda karşılaştırır
              v3 --data klasör             Strateji araştırması V3 (4H/1D, çok yıllı, ön kayıtlı protokol)
              study --data klasör          A/B filtrelerini eğitim/test ve ileri yürüyen testle doğrular; 3 yapılandırma
              analyze --data klasör        A ve B ayrı, rejim filtresi açık/kapalı, kayıp analizi ve önceden
                      [--out rapor.json]   tanımlı filtreler (ilk/ikinci yarı ayrı)
            """.trimIndent(),
        )
    }
}

private fun parseOptions(args: List<String>): Map<String, String> =
    args.chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }

private suspend fun scan() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = TradingEngine(MexcRestClient(), MexcWebSocketClient(scope), scope, NoopStore)
    engine.start()
    val report = kotlinx.coroutines.withTimeout(180_000) {
        var r = engine.lastScan.value
        while (r == null) { kotlinx.coroutines.delay(500); r = engine.lastScan.value }
        r
    }
    println("Tarama: ${time.format(Instant.ofEpochMilli(report.time))} UTC")
    println("Piyasa: ${if (report.market.allowsLongs) "alıma uygun" else "ALIM YOK (${report.market.blockKind?.title})"} — ${report.market.reasons.joinToString()}")
    com.spotbot.core.scanner.MarketExplanation.text(report.market)?.let { println("Açıklama: $it") }
    report.market.conditions.forEach { c -> println("  ${if (c.passed) "✓" else "✗"} ${c.id} ${c.title} [${if (c.kind == com.spotbot.core.scanner.MarketBlockKind.DATA) "veri şartı" else "piyasa şartı"}] — ${c.detail}") }
    listOfNotNull(report.market.btcH4, report.market.btcH1).forEach { v ->
        println(
            "  BTC %s · son kapanmış mum %s UTC · kapanış %.2f · EMA21 %.2f · EMA55 %.2f · SuperTrend %s · ADX %.1f (+DI %.1f / −DI %.1f) · yapı %s · rejim %s".format(
                v.timeframe, time.format(Instant.ofEpochMilli(v.lastClose)), v.close, v.emaMid, v.emaSlow, if (v.superTrendUp) "yukarı" else "aşağı",
                v.adx, v.plusDi, v.minusDi, v.structure, v.regime,
            ),
        )
    }
    report.market.breadthPercent?.let { println("Genişlik: 4H EMA55 üzerindeki coin oranı %%%.0f".format(it)) }
    println()
    println("%-12s %-10s %8s %14s %8s %-10s %-8s %s".format("Parite", "Durum", "Puan", "24s hacim", "Spread", "4H", "1H", "Gerekçe"))
    report.coins.forEach { c ->
        println(
            "%-12s %-10s %8d %14s %8s %-10s %-8s %s".format(
                c.symbol, c.status, c.preScore, "%,.0f".format(c.quoteVolume24h),
                c.spreadPercent?.let { "%.3f%%".format(it) } ?: "-", c.h4Regime ?: "-", c.h1Trend ?: "-", c.reasons.joinToString("; "),
            ),
        )
    }
    println()
    println("Ayrıntılı incelenen adaylar: ${report.candidates.joinToString().ifEmpty { "yok (işlem açılmaz)" }}")
    engine.portfolio.value.decisions.takeLast(8).forEach { println("• ${it.summary} ${it.details.joinToString(" | ")}") }
    engine.scanLogs.value.lastOrNull()?.let { log ->
        println("\nKarar günlüğü (${log.coins.size} coin, profil ${log.profile.title}):")
        log.coins.filter { it.strategies.isNotEmpty() }.forEach { coin ->
            val d = coin.data
            println("  ${coin.symbol}: ${coin.outcome.title} — ${coin.summary}")
            println("    veri: 15M ${d.m15Close?.let { time.format(Instant.ofEpochMilli(it)) }} · 1H ${d.h1Close?.let { time.format(Instant.ofEpochMilli(it)) }} · 4H ${d.h4Close?.let { time.format(Instant.ofEpochMilli(it)) }} · fiyat ${d.priceTime?.let { time.format(Instant.ofEpochMilli(it)) }}")
            coin.strategies.forEach { s ->
                println("    ${s.strategy.code}: ${if (!s.applicable) "değerlendirilmedi" else if (!s.setupFound) "kurulum yok" else "kurulum var, kurallar ${if (s.rulesAllowed == true) "geçti" else "reddetti"}"} — ${s.reasons.joinToString("; ")}")
                s.rules.filter { !it.passed }.forEach { r -> println("      ✗ ${r.id} ${r.title}: ${r.detail}") }
            }
        }
        println("  Diğer coinler: " + log.coins.filter { it.strategies.isEmpty() }.groupingBy { it.outcome.title }.eachCount().entries.joinToString { "${it.key} ${it.value}" })
    }
    engine.stop()
    scope.cancel()
}

private suspend fun backtest(options: Map<String, String>) {
    val days = options["days"]?.toInt() ?: 90
    val capital = options["capital"]?.toDouble() ?: 1_000.0
    val scannerConfig = ScannerConfig()
    val symbols = (options["symbols"]?.split(",") ?: scannerConfig.watchlist).map { it.trim().uppercase().let { s -> if (s.endsWith("USDT")) s else s + "USDT" } }
    val strategies = (options["strategies"] ?: "A,B").split(",").mapNotNull { code -> StrategyId.entries.firstOrNull { it.code == code.trim().uppercase() } }.toSet()
    val api = MexcRestClient()
    // --end 2026-11-09 (UTC gün sonu) ile aynı dönem tekrar üretilebilir; verilmezse son kapanmış 4H mum.
    val end = options["end"]?.let { e -> e.toLongOrNull() ?: java.time.LocalDate.parse(e).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
        ?.let { minOf(it, Timeframe.H4.lastCloseAtOrBefore(api.serverTime())) }
        ?: Timeframe.H4.lastCloseAtOrBefore(api.serverTime())
    val start = end - days * 24 * 60 * 60_000L
    val config = BacktestConfig(symbols, start, end, capital, enabledStrategies = strategies)
        .let { c -> options["warmup"]?.toInt()?.let { c.copy(warmupDays = it) } ?: c }
    val loadStart = start - config.warmupDays * 24 * 60 * 60_000L

    val cacheDir = File(System.getProperty("user.home"), ".cryptoscan/cache").apply { mkdirs() }
    val data = (symbols + "BTCUSDT").distinct().associateWith { symbol ->
        val file = File(cacheDir, "$symbol-$loadStart-$end.json")
        if (file.exists()) {
            json.decodeFromString(HistoricalDataset.serializer(), file.readText())
        } else {
            BacktestDataLoader(api).load(listOf(symbol), loadStart, end) { print("\r$it          ") }.getValue(symbol).also {
                file.writeText(json.encodeToString(HistoricalDataset.serializer(), it))
            }
        }
    }
    println("\rVeri hazır: ${data.map { "${it.key}=${it.value.m15.size}" }.joinToString()}")
    val info = api.exchangeInfo().associateBy { it.symbol }
    val started = System.currentTimeMillis()
    val (report, resolved) = BacktestEngine(config).runResolvingIntrabar(data, info, BacktestDataLoader(api)) { print("\rBacktest %%%.0f".format(it * 100)) }
    // İndirilen 5M mumlar önbelleğe yazılır (sonraki çalıştırmada yeniden indirilmez).
    resolved.forEach { (symbol, d) -> if (d.m5.size != data.getValue(symbol).m5.size) File(cacheDir, "$symbol-$loadStart-$end.json").writeText(json.encodeToString(HistoricalDataset.serializer(), d)) }
    println("\rBacktest tamamlandı (${(System.currentTimeMillis() - started) / 1000} sn)")
    printReport(report)
    options["out"]?.let { File(it).writeText(json.encodeToString(BacktestReport.serializer(), report)); println("Rapor: $it") }
}

private fun statsLine(label: String, s: com.spotbot.core.backtest.PerformanceStats) =
    "  %-22s %4d işlem  %3d K / %3d Z  kazanma %%%5.1f  ort.kazanç %+7.2f  ort.kayıp %+7.2f  PF %5.2f  beklenen %+6.3f USDT (%+.2fR)  maks.düşüş %%%.2f  net %+8.2f (%%%+.2f)  ücret %.2f".format(
        label, s.trades, s.wins, s.losses, s.winRatePercent, s.averageWin, s.averageLoss, s.profitFactor, s.expectancy, s.averageR,
        s.maxDrawdownPercent, s.netPnl, s.netReturnPercent, s.fees,
    )

private fun printReport(r: BacktestReport) {
    val o = r.overall
    println(
        """
        ── Backtest raporu ─────────────────────────────
        Dönem          : ${time.format(Instant.ofEpochMilli(r.startTime))} → ${time.format(Instant.ofEpochMilli(r.endTime))} UTC
        Coinler        : ${r.symbols.joinToString()}
        Sermaye        : %.2f → %.2f USDT  (net %+.2f USDT, %+.2f%%; BTC al-tut: %s)
        İşlem sayısı   : %d  (%d kazançlı / %d zararlı; bulunan kurulum: %d)
        Kazanma oranı  : %.1f%%
        Brüt kâr/zarar : %+.2f / %.2f USDT (net kâr/zararlara göre, ücretler dahil)
        Ort. kazanç    : %+.2f USDT   Ort. kayıp: %+.2f USDT
        Profit factor  : %.2f
        Beklenen değer : %+.3f USDT/işlem (%+.2fR ortalama)
        Maks. düşüş    : %.2f%% (piyasa değerli özsermaye)
        Komisyon       : %.2f USDT   Spread+kayma: %.2f USDT
        Piyasada kalma : %.1f%%
        """.trimIndent().format(
            r.initialCapital, r.finalEquity, r.finalEquity - r.initialCapital, r.netReturnPercent, r.btcBuyAndHoldPercent?.let { "%+.2f%%".format(it) } ?: "-",
            r.trades, o.wins, o.losses, r.setupsFound, r.winRatePercent, o.grossProfit, o.grossLoss, r.averageWin, r.averageLoss, r.profitFactor,
            r.expectancy, r.expectancyR, r.maxDrawdownPercent, r.totalFees, r.spreadAndSlippageCost, r.exposurePercent,
        ),
    )
    if (r.perStrategy.isNotEmpty()) {
        println("Strateji bazında (aynı çalıştırma; düşüş yalnız o stratejinin işlem sırasına göre):")
        r.perStrategy.forEach { s -> println(statsLine("${s.strategy.code} ${s.strategy.title}", s.stats)) }
    }
    val ib = r.intrabar
    println("Aynı 15M mumda stop+hedef: %d (5M ile hedef önce %d, stop önce %d; belirsiz: 5M yok %d, aynı 5M mum %d)".format(
        ib.ambiguousBars, ib.resolvedTargetFirst, ib.resolvedStopFirst, ib.unresolvedNoData, ib.unresolvedSameSubBar))
    if (r.entriesSkippedForDrift > 0) println("Fiyat kayması nedeniyle gönderilmeyen giriş: ${r.entriesSkippedForDrift}")
    if (r.monthlyReturns.isNotEmpty()) {
        println("Aylık özsermaye değişimi:")
        r.monthlyReturns.forEach { m -> println("  %s  %9.2f → %9.2f  %%%+6.2f  (%d işlem kapandı, net %+.2f)".format(m.label, m.startEquity, m.endEquity, m.changePercent, m.tradesClosed, m.realizedPnl)) }
    }
    if (r.exitReasons.isNotEmpty()) println("Çıkış nedenleri: ${r.exitReasons.entries.joinToString { "${it.key}=${it.value}" }}")
    if (r.blockedByRule.isNotEmpty()) {
        println("Kurulumları en çok engelleyen zorunlu kurallar:")
        r.blockedByRule.entries.take(6).forEach { println("  ${it.value}× ${it.key}") }
    }
    println("İşlemler (giriş zamanına göre):")
    println("  %-3s %-9s %s %-16s %-16s %12s %12s %9s %7s  %s".format("#", "Coin", "S", "Giriş (UTC)", "Çıkış (UTC)", "Alış", "Satış(ort)", "Net USDT", "R", "Çıkış nedeni"))
    r.closedTrades.sortedBy { it.position.openedAt }.forEachIndexed { k, t ->
        println(
            "  %-3d %-9s %s %-16s %-16s %12.6g %12.6g %+9.2f %+7.2f  %s%s".format(
                k + 1, t.position.symbol, t.position.strategy.code, time.format(Instant.ofEpochMilli(t.position.openedAt)),
                time.format(Instant.ofEpochMilli(t.closedAt)), t.position.entryPrice, t.averageExitPrice, t.netPnl, t.rMultiple, t.reason.title,
                if (t.note.contains("BELİRSİZ")) " [belirsiz]" else if (t.note.contains("5M")) " [5M doğrulandı]" else "",
            ),
        )
    }
    r.warnings.forEach { println("⚠ $it") }
}

private suspend fun paper(options: Map<String, String>) {
    val dir = File(options["data"] ?: "cryptoscan-data").apply { mkdirs() }
    val store = FileStateStore(File(dir, "portfolio.json"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = TradingEngine(MexcRestClient(), MexcWebSocketClient(scope), scope, store, EngineConfig())
    scope.launch {
        engine.events.collect { e ->
            val text = when (e) {
                is EngineEvent.Opened -> "AÇILDI ${e.position.symbol} ${e.position.strategy.title} @ ${e.position.entryPrice} stop ${e.position.stopPrice} hedef ${e.position.targetPrice}"
                is EngineEvent.Closed -> "KAPANDI ${e.trade.position.symbol} ${e.trade.reason.title} net %+.2f USDT".format(e.trade.netPnl)
                is EngineEvent.Problem -> "SORUN ${e.message}"
            }
            println("${time.format(Instant.now())} $text")
        }
    }
    scope.launch {
        var lastCount = 0
        engine.portfolio.collect { p ->
            p.decisions.drop(lastCount.coerceAtMost(p.decisions.size)).forEach { println("${time.format(Instant.ofEpochMilli(it.time))} ${it.summary}") }
            lastCount = p.decisions.size
        }
    }
    scope.launch {
        var seen = 0L
        engine.scanLogs.collect { logs ->
            val log = logs.lastOrNull() ?: return@collect
            if (log.time == seen) return@collect
            seen = log.time
            val d = com.spotbot.core.trading.ScanDiagnostics.of(log, engine.lastScan.value?.market)
            println("${time.format(Instant.ofEpochMilli(log.time))} TEŞHİS tarama ${time.format(Instant.ofEpochMilli(d.boundary))}: taranan ${d.scanned} · elenen ${d.eliminated} · riskli ${d.flagged} · seçilmeyen ${d.notSelected} · kurulum yok ${d.noSetup} · reddedilen ${d.rejected} · eski veri ${d.staleData} · uygun ${d.eligible} · açılan ${d.opened}")
            println("    neden: ${d.shortReason ?: "işlem açıldı"}")
            d.topReasons.take(6).forEach { println("    ${it.second}× ${it.first}") }
            com.spotbot.core.trading.ScanDiagnostics.lastSignal(logs)?.let { s -> println("    son sinyal: ${s.symbol} ${s.strategy} ${time.format(Instant.ofEpochMilli(s.time))} · ${if (s.opened) "işleme dönüştü" else "dönüşmedi: " + s.blockedBy.joinToString("; ")}") }
            val h = engine.health.value
            println("    veri: WS ${h.feed.webSocket} · REST yedeği ${h.feed.restFallbackActive} · yeniden bağlanma ${h.feed.reconnects} · WS/REST sapma ${h.feed.lastMaxDeviationPercent} · saat farkı ${h.serverTimeOffsetMillis} ms · karar gecikmesi ${(log.time - log.boundary) / 1000} sn")
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { engine.stop() })
    println("Sanal işlem motoru başlatıldı. Durum: ${dir.absolutePath}/portfolio.json (Ctrl+C ile durdurun)")
    engine.start()
    awaitCancellation()
}

private object NoopStore : StateStore {
    override fun load(): PortfolioState? = null
    override fun save(state: PortfolioState) = Unit
}

class FileStateStore(private val file: File) : StateStore {
    override fun load(): PortfolioState? = file.takeIf { it.exists() }?.let {
        runCatching { json.decodeFromString(PortfolioState.serializer(), it.readText()) }.getOrNull()
    }

    override fun save(state: PortfolioState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(PortfolioState.serializer(), state))
        tmp.renameTo(file)
    }
}
