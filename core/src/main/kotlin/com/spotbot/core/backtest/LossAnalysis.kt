package com.spotbot.core.backtest

import com.spotbot.core.analysis.MarketRegime
import com.spotbot.core.analysis.Regime
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.ClosedTrade
import com.spotbot.core.trading.ExitReason
import kotlinx.serialization.Serializable

/** Bir özellik dilimindeki işlemlerin sonucu (ilk ve ikinci yarı ayrı: bir etki iki yarıda da görülmeli). */
@Serializable
data class BucketStats(
    val feature: String,
    val bucket: String,
    val strategy: String,
    val trades: Int,
    val winRatePercent: Double,
    val averageR: Double,
    val totalR: Double,
    val firstHalfTrades: Int,
    val firstHalfAverageR: Double?,
    val secondHalfTrades: Int,
    val secondHalfAverageR: Double?,
)

/**
 * Önceden tanımlanmış bir filtrenin işlem listesine etkisi (sonradan alt küme: serbest kalan pozisyon yerinin
 * başka işleme gitmesi hesaba katılmaz; kesin etki için filtre gerçek backtest'te ayrıca çalıştırılmalıdır).
 */
@Serializable
data class FilterTest(
    val id: String,
    val description: String,
    val strategy: String,
    val baselineTrades: Int,
    val baselineAverageR: Double,
    val keptTrades: Int,
    val keptAverageR: Double,
    val removedAverageR: Double?,
    val firstHalfKept: Int,
    val firstHalfDeltaR: Double?,
    val secondHalfKept: Int,
    val secondHalfDeltaR: Double?,
    /** İki yarıda da ortalama R'yi artırıyor ve her yarıda en az [LossAnalysis.MIN_TRADES_PER_HALF] işlem kalıyor. */
    val candidate: Boolean,
)

/** Zararlı işlemlerin nasıl kaybettiği (işlem süresince fiyat hareketine göre). */
@Serializable
data class LossProfile(
    val strategy: String,
    val losers: Int,
    /** Lehte en fazla 0,3R gitmeden zararla kapanan (anında başarısız: sahte kırılım / tutmayan dönüş). */
    val immediateFailures: Int,
    /** Önce en az 1R lehte gidip sonra zararla kapanan (kâr geri verildi). */
    val gaveBackAfterOneR: Int,
    val stopLosses: Int,
    val invalidations: Int,
    val medianBarsHeld: Int,
    val medianMfeR: Double,
)

@Serializable
data class LossAnalysisReport(
    val split: Long,
    val buckets: List<BucketStats>,
    val filters: List<FilterTest>,
    val lossProfiles: List<LossProfile>,
)

object LossAnalysis {
    const val MIN_TRADES_PER_HALF = 15

    private fun bucket(value: Double, low: Double, high: Double, unit: String = ""): String = when {
        value < low -> "< $low$unit"
        value < high -> "$low–$high$unit"
        else -> "≥ $high$unit"
    }

    /** İncelenen özellikler (kullanıcının saydığı kayıp nedenleri; dilim sınırları sonuçlardan önce sabitlendi). */
    val features: List<Pair<String, (TradeFeatures) -> String>> = listOf(
        "BTC 4H trendi" to { f -> f.btcH4Trend?.name ?: "veri yok" },
        "Piyasa rejimi (BTC, 5 sınıf)" to { f -> f.marketRegime?.title ?: "veri yok" },
        "Coin 4H rejimi (5 sınıf)" to { f -> f.coinRegime?.title ?: "veri yok" },
        "4H/1H trend uyumu" to { f -> if (f.h4Trend == Regime.TREND_UP && f.h1Trend == Regime.TREND_UP) "ikisi de yükseliş" else "uyumsuz" },
        "1H ADX" to { f -> bucket(f.adx1h, 20.0, 25.0) },
        "15M hacim / ortalama" to { f -> bucket(f.volumeRatio15m, 1.2, 2.0, "×") },
        "1H RSI negatif uyumsuzluk" to { f -> if (f.bearishRsiDivergence1h) "var" else "yok" },
        "1H MACD histogramı" to { f -> if (f.macdHistogram1hRising) "yükseliyor" else "düşüyor" },
        "Stop mesafesi (1H ATR)" to { f -> bucket(f.stopDistanceAtr1h, 0.75, 1.5, " ATR") },
        "Volatilite (1H ATR %)" to { f -> bucket(f.atrPercent1h, 0.6, 1.2, "%") },
        "Saat (UTC)" to { f -> (f.hourUtc / 6 * 6).let { "%02d–%02d".format(it, it + 6) } },
        "Kalite puanı" to { f -> bucket(f.qualityScore, 40.0, 60.0) },
        "1H yapı" to { f -> if (f.h1StructureUp) "HH/HL" else "yükseliş yapısı yok" },
    )

    /** Önceden tanımlanmış filtre adayları (sonuçlara bakılarak eklenmedi ya da ayarlanmadı). */
    val filters: List<Triple<String, String, (TradeFeatures) -> Boolean>> get() = EntryFilters.all.map { Triple(it.id, it.description, it.keep) }

    private class Row(val trade: ClosedTrade, val features: TradeFeatures, val firstHalf: Boolean)

    fun run(report: BacktestReport, split: Long): LossAnalysisReport {
        val byKey = report.entryFeatures.associateBy { it.symbol to it.signalTime }
        val rows = report.closedTrades.mapNotNull { t ->
            byKey[t.position.symbol to t.position.setup.signalTime]?.let { Row(t, it, t.position.setup.signalTime < split) }
        }
        val groups = listOf("Tümü" to rows) + StrategyId.entries.mapNotNull { id ->
            rows.filter { it.trade.position.strategy == id }.takeIf { it.isNotEmpty() }?.let { "${id.code} ${id.title}" to it }
        }
        fun avgR(list: List<Row>) = if (list.isEmpty()) null else list.sumOf { it.trade.rMultiple } / list.size

        val buckets = groups.flatMap { (label, list) ->
            features.flatMap { (name, key) ->
                list.groupBy { key(it.features) }.toSortedMap().map { (b, members) ->
                    val first = members.filter { it.firstHalf }
                    val second = members.filterNot { it.firstHalf }
                    BucketStats(
                        name, b, label, members.size, members.count { it.trade.netPnl > 0 } * 100.0 / members.size,
                        avgR(members)!!, members.sumOf { it.trade.rMultiple }, first.size, avgR(first), second.size, avgR(second),
                    )
                }
            }
        }
        val filterTests = groups.flatMap { (label, list) ->
            filters.map { (id, description, keep) ->
                val kept = list.filter { keep(it.features) }
                val removed = list.filterNot { keep(it.features) }
                fun half(first: Boolean): Pair<Int, Double?> {
                    val all = list.filter { it.firstHalf == first }
                    val k = all.filter { keep(it.features) }
                    val base = avgR(all)
                    val withFilter = avgR(k)
                    return k.size to if (base != null && withFilter != null) withFilter - base else null
                }
                val (firstKept, firstDelta) = half(true)
                val (secondKept, secondDelta) = half(false)
                FilterTest(
                    id, description, label, list.size, avgR(list) ?: 0.0, kept.size, avgR(kept) ?: 0.0, avgR(removed),
                    firstKept, firstDelta, secondKept, secondDelta,
                    candidate = (firstDelta ?: 0.0) > 0 && (secondDelta ?: 0.0) > 0 && firstKept >= MIN_TRADES_PER_HALF && secondKept >= MIN_TRADES_PER_HALF,
                )
            }
        }
        val profiles = groups.map { (label, list) ->
            val losers = list.filter { it.trade.netPnl <= 0 }
            val excursions = losers.mapNotNull { report.excursions[it.trade.position.id] }
            LossProfile(
                strategy = label,
                losers = losers.size,
                immediateFailures = excursions.count { it.mfeR < 0.3 },
                gaveBackAfterOneR = excursions.count { it.mfeR >= 1.0 },
                stopLosses = losers.count { it.trade.reason == ExitReason.STOP_LOSS },
                invalidations = losers.count { it.trade.reason == ExitReason.INVALIDATION },
                medianBarsHeld = excursions.map { it.barsHeld }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] },
                medianMfeR = excursions.map { it.mfeR }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] },
            )
        }
        return LossAnalysisReport(split, buckets, filterTests, profiles)
    }
}

/** Aynı veri, maliyet, sermaye ve dönemde ayrı çalıştırılan strateji seti / rejim politikası sonucu. */
@Serializable
data class IsolatedRun(
    val label: String,
    val full: PerformanceStats,
    val firstHalf: PerformanceStats,
    val secondHalf: PerformanceStats,
    val intrabar: IntrabarStats,
)

/**
 * A ve B'yi (ve isteğe bağlı rejim politikalarını) **aynı koşullarda ayrı ayrı** çalıştırır: aynı veri, ücret,
 * kayma, başlangıç sermayesi ve dönem. Dönem ikiye bölünür; tutarlılık iki yarıda da aranır.
 */
object StrategyIsolation {
    data class Setting(
        val label: String,
        val strategies: Set<StrategyId>,
        val regimePolicy: com.spotbot.core.analysis.RegimePolicy = com.spotbot.core.analysis.RegimePolicy.OFF,
        /** Yalnız deney için risk ayarı değişikliği (ör. ATR stop tabanı); null = temel ayar. */
        val risk: com.spotbot.core.risk.RiskConfig? = null,
    )

    val defaultSettings = listOf(
        Setting("A + B (varsayılan)", setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK)),
        Setting("Yalnız A", setOf(StrategyId.BREAKOUT_RETEST)),
        Setting("Yalnız B", setOf(StrategyId.TREND_PULLBACK)),
    )

    fun configs(base: BacktestConfig, setting: Setting): List<BacktestConfig> {
        val split = base.startTime + (base.endTime - base.startTime) / 2
        val config = base.copy(
            enabledStrategies = setting.strategies,
            strategyParams = base.strategyParams.copy(regimePolicy = setting.regimePolicy),
            risk = setting.risk ?: base.risk,
            // Profil seçimi stratejileri yeniden daraltmasın diye: Adaptif profil, etkin stratejileri olduğu gibi kullanır.
            profile = com.spotbot.core.trading.StrategyProfile.ADAPTIVE,
        )
        return listOf(config, config.copy(endTime = split), config.copy(startTime = split))
    }

    fun summarize(setting: Setting, reports: List<BacktestReport>): IsolatedRun {
        val (full, first, second) = reports
        return IsolatedRun(setting.label, full.overall, first.overall, second.overall, full.intrabar)
    }

    fun run(
        base: BacktestConfig,
        data: Map<String, HistoricalDataset>,
        info: Map<String, SymbolInfo>,
        settings: List<Setting> = defaultSettings,
        runner: (List<() -> BacktestReport>) -> List<BacktestReport> = { jobs -> jobs.map { it() } },
        onProgress: (Double) -> Unit = {},
    ): List<IsolatedRun> {
        val jobs = settings.flatMap { s -> configs(base, s).map { c -> { BacktestEngine(c).run(data, info) } } }
        var done = 0
        val reports = runner(jobs.map { job -> { job().also { synchronized(this) { done++; onProgress(done.toDouble() / jobs.size) } } } })
        return settings.mapIndexed { i, s -> summarize(s, reports.subList(3 * i, 3 * i + 3)) }
    }
}

/** Önceden tanımlı bir giriş filtresi: özellikler sinyal anında bilinen değerlerdir. */
class EntryFilter(val id: String, val description: String, val detail: (TradeFeatures) -> String, val keep: (TradeFeatures) -> Boolean)

/**
 * Giriş filtresi adayları. Eşikler ölçümden önce sabitlendi; hem sonradan alt küme analizinde hem de gerçek yeniden
 * testte (StrategyParams.entryFilters) aynı tanımlar kullanılır.
 */
object EntryFilters {
    val all: List<EntryFilter> = listOf(
        EntryFilter("F1", "BTC 4H trendi yükselişte", { "BTC 4H: ${it.btcH4Trend}" }) { it.btcH4Trend == Regime.TREND_UP },
        EntryFilter("F2", "1H ADX ≥ 20", { "1H ADX %.1f".format(it.adx1h) }) { it.adx1h >= 20 },
        EntryFilter("F3", "15M hacim ≥ 1,5× ortalama", { "Hacim %.2f×".format(it.volumeRatio15m) }) { it.volumeRatio15m >= 1.5 },
        EntryFilter("F4", "Stop mesafesi ≥ 1 ATR (1H)", { "Stop %.2f ATR".format(it.stopDistanceAtr1h) }) { it.stopDistanceAtr1h >= 1.0 },
        EntryFilter("F5", "4H ve 1H trend uyumlu (ikisi de yükseliş)", { "4H ${it.h4Trend} / 1H ${it.h1Trend}" }) { it.h4Trend == Regime.TREND_UP && it.h1Trend == Regime.TREND_UP },
        EntryFilter("F6", "1H RSI negatif uyumsuzluk yok", { if (it.bearishRsiDivergence1h) "Uyumsuzluk var" else "Uyumsuzluk yok" }) { !it.bearishRsiDivergence1h },
        EntryFilter("F7", "Piyasa rejimi güçlü ya da zayıf yükseliş", { "Rejim: ${it.marketRegime?.title ?: "veri yok"}" }) { it.marketRegime == MarketRegime.STRONG_UP || it.marketRegime == MarketRegime.WEAK_UP },
        EntryFilter("F8", "1H MACD histogramı yükseliyor", { if (it.macdHistogram1hRising) "Yükseliyor" else "Düşüyor" }) { it.macdHistogram1hRising },
    )
}
