package com.spotbot.core.rules

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.Trend
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.risk.RiskReward
import com.spotbot.core.risk.SizingResult
import com.spotbot.core.scanner.CoinScan
import com.spotbot.core.scanner.CoinStatus
import com.spotbot.core.scanner.MarketState
import com.spotbot.core.strategy.TradeSetup
import kotlinx.serialization.Serializable

@Serializable
enum class RuleGroup {
    /** Eksikse işlem kesinlikle yasak. */
    MANDATORY,

    /** İşlemin puanını ve önceliğini belirler. */
    QUALITY,
}

@Serializable
data class RuleOutcome(
    val id: String,
    val title: String,
    val group: RuleGroup,
    val passed: Boolean,
    val detail: String,
    val weight: Double,
)

@Serializable
data class RuleReport(val outcomes: List<RuleOutcome>, val qualityScore: Double, val minQualityScore: Double) {
    val mandatoryFailures: List<RuleOutcome> get() = outcomes.filter { it.group == RuleGroup.MANDATORY && !it.passed }
    val allowed: Boolean get() = mandatoryFailures.isEmpty() && qualityScore >= minQualityScore
}

/** Kuralların değerlendirildiği an itibarıyla bilinen her şey. */
class RuleContext(
    val setup: TradeSetup,
    val mtf: MultiTimeframe,
    val btc: MultiTimeframe?,
    val scan: CoinScan?,
    val market: MarketState,
    val riskConfig: RiskConfig,
    val riskReward: RiskReward,
    val sizing: SizingResult,
    val strategyEnabled: Boolean,
    val strategySuitableRegimes: Set<Regime>,
    val now: Long,
    val openSymbols: Set<String>,
    val openPositionCount: Int,
    /** Gün başı özsermayeye göre bugünkü kâr/zarar (gerçekleşmemiş dahil), %. */
    val dailyPnlPercent: Double,
    /** Bu coinle ilişkili açık pozisyonların riski + yeni işlemin riski, özsermayenin %'si. */
    val correlatedRiskPercent: Double,
    /** Coin dinlenmedeyse nedeni (dinlenme kapalıysa ya da koşul yoksa null). */
    val cooldownReason: String? = null,
)

class BuiltInRule(
    val id: String,
    val title: String,
    val group: RuleGroup,
    val defaultWeight: Double,
    val check: (RuleContext) -> Pair<Boolean, String>,
)

/**
 * Yalnızca tasarım notunda açıkça yazan kurallar. "60 kural" metni doğrulandıktan sonra
 * yapılandırma dosyasına [CustomRule] olarak eklenecek; burada varsayımla kural uydurulmaz.
 */
object BuiltInRules {
    val all: List<BuiltInRule> = listOf(
        mandatory("Z01", "Sinyal kapanmış mumdan üretildi") { c ->
            (c.setup.signalTime <= c.now && c.mtf.m15.candle.closeTime <= c.now) to "Tetik mumu kapanışı: ${utc(c.setup.signalTime)}"
        },
        mandatory("Z02", "Stop-loss tanımlı ve girişin altında") { c ->
            (c.setup.stopPrice > 0 && c.setup.stopPrice < c.setup.entryPrice) to "Stop ${c.setup.stopPrice.short()} / giriş ${c.setup.entryPrice.short()}"
        },
        mandatory("Z03", "Net risk/getiri en az asgari oran") { c ->
            (c.riskReward.ratio >= c.riskConfig.minNetRiskReward) to "Net R:R %.2f (asgari %.1f)".format(c.riskReward.ratio, c.riskConfig.minNetRiskReward)
        },
        mandatory("Z04", "4H ana trend düşüşte değil") { c ->
            (c.mtf.h4.regime != Regime.TREND_DOWN) to "4H rejimi: ${c.mtf.h4.regime}"
        },
        mandatory("Z05", "Strateji mevcut piyasa rejimine uygun") { c ->
            (c.mtf.h4.regime in c.strategySuitableRegimes) to "Rejim ${c.mtf.h4.regime}; uygun: ${c.strategySuitableRegimes.joinToString()}"
        },
        mandatory("Z06", "Strateji etkin (test edilmeden açılmaz)") { c ->
            c.strategyEnabled to "${c.setup.strategy.title} ${if (c.strategyEnabled) "etkin" else "kapalı"}"
        },
        mandatory("Z07", "En az 2 bağımsız teyit (tek göstergeyle işlem yok)") { c ->
            (c.setup.confirmations.size >= 2) to "${c.setup.confirmations.size} teyit: ${c.setup.confirmations.joinToString()}"
        },
        mandatory("Z08", "Likidite ve işlem koşulları uygun") { c ->
            when {
                c.scan == null -> false to "Tarama verisi yok"
                c.scan.status == CoinStatus.ELIMINATED -> false to c.scan.reasons.joinToString()
                else -> true to "Tarama filtrelerini geçti"
            }
        },
        mandatory("Z09", "Manipülasyon riski işareti yok") { c ->
            when {
                c.scan == null -> false to "Tarama verisi yok"
                c.scan.status == CoinStatus.FLAGGED -> false to c.scan.reasons.joinToString()
                else -> true to "İşaret yok"
            }
        },
        mandatory("Z10", "BTC / genel piyasa filtresi alıma izin veriyor") { c ->
            c.market.allowsLongs to c.market.reasons.joinToString()
        },
        mandatory("Z11", "Aynı coinde açık pozisyon yok (zararda ek alım yasak)") { c ->
            (c.setup.symbol !in c.openSymbols) to if (c.setup.symbol in c.openSymbols) "Zaten açık pozisyon var" else "Açık pozisyon yok"
        },
        mandatory("Z12", "Eş zamanlı işlem sınırı") { c ->
            (c.openPositionCount < c.riskConfig.maxOpenPositions) to "${c.openPositionCount}/${c.riskConfig.maxOpenPositions} açık işlem"
        },
        mandatory("Z13", "Günlük kayıp limiti aşılmadı") { c ->
            (c.dailyPnlPercent > -c.riskConfig.dailyLossLimitPercent) to "Bugün %%%.2f (limit -%%%.1f)".format(c.dailyPnlPercent, c.riskConfig.dailyLossLimitPercent)
        },
        mandatory("Z14", "İlişkili coinlerde toplam risk sınır içinde") { c ->
            (c.correlatedRiskPercent <= c.riskConfig.maxCorrelatedRiskPercent + 1e-9) to
                "İlişkili toplam risk %%%.2f (sınır %%%.1f)".format(c.correlatedRiskPercent, c.riskConfig.maxCorrelatedRiskPercent)
        },
        mandatory("Z15", "Pozisyon büyüklüğü borsa kurallarına uygun") { c ->
            when (val s = c.sizing) {
                is SizingResult.Sized -> true to "Miktar ${s.quantity}, tutar %.2f USDT, risk %%%.2f".format(s.notional, s.riskPercent)
                is SizingResult.Rejected -> false to s.reason
            }
        },
        mandatory("Z16", "Coin üst üste zarar nedeniyle dinlenmede değil") { c ->
            (c.cooldownReason == null) to (c.cooldownReason ?: "Dinlenme yok")
        },
        quality("K01", "Net R:R tercih edilen seviyede (2+)", 3.0) { c ->
            (c.riskReward.ratio >= c.riskConfig.preferredRiskReward) to "Net R:R %.2f".format(c.riskReward.ratio)
        },
        quality("K02", "4H trend gücü yüksek (ADX ≥ 25)", 2.0) { c ->
            (c.mtf.h4.indicators.adx >= 25) to "4H ADX %.1f".format(c.mtf.h4.indicators.adx)
        },
        quality("K03", "Tetik mumunda hacim teyidi", 2.0) { c ->
            val s = c.mtf.m15.indicators
            (s.volumeAverage > 0 && s.volume >= 1.5 * s.volumeAverage) to "Hacim / ortalama %.2f".format(s.volume / s.volumeAverage)
        },
        quality("K04", "15M RSI sağlıklı bölgede (45–70)", 1.0) { c ->
            val rsi = c.mtf.m15.indicators.rsi
            (rsi in 45.0..70.0) to "15M RSI %.1f".format(rsi)
        },
        quality("K05", "15M MACD histogramı yükseliyor", 1.0) { c ->
            val now = c.mtf.m15.indicators.macdHistogram
            val prev = c.mtf.m15.previous?.macdHistogram ?: Double.NaN
            (now > prev) to "Histogram %.4f (önceki %.4f)".format(now, prev)
        },
        quality("K06", "Fiyat 1H VWAP üzerinde", 1.0) { c ->
            val s = c.mtf.h1.indicators
            (s.close > s.vwap) to "1H kapanış ${s.close.short()} / VWAP ${s.vwap.short()}"
        },
        quality("K07", "Giriş bölgesinde çoklu yapısal uyum", 2.0) { c ->
            (c.setup.confluences.size >= 2) to c.setup.confluences.joinToString()
        },
        quality("K08", "BTC 4H trendi yükselişte", 2.0) { c ->
            (c.btc?.h4?.regime == Regime.TREND_UP) to "BTC 4H: ${c.btc?.h4?.regime}"
        },
        quality("K09", "Spread çok düşük (< %0,05)", 1.0) { c ->
            val spread = c.scan?.spreadPercent
            (spread != null && spread < 0.05) to "Spread %s".format(spread?.let { "%%%.3f".format(it) } ?: "-")
        },
        quality("K10", "BTC'ye göre göreceli güç (7 gün)", 1.0) { c ->
            val rs = c.scan?.relativeStrength7dPercent
            (rs != null && rs > 0) to "Göreceli güç %s".format(rs?.let { "%+.1f%%".format(it) } ?: "-")
        },
        quality("K11", "1H piyasa yapısı yükselişte (HH/HL)", 2.0) { c ->
            (c.mtf.h1.structure.trend == Trend.UP) to "1H yapı: ${c.mtf.h1.structure.trend}"
        },
    )

    private fun mandatory(id: String, title: String, check: (RuleContext) -> Pair<Boolean, String>) =
        BuiltInRule(id, title, RuleGroup.MANDATORY, 0.0, check)

    private fun quality(id: String, title: String, weight: Double, check: (RuleContext) -> Pair<Boolean, String>) =
        BuiltInRule(id, title, RuleGroup.QUALITY, weight, check)
}

private val UTC_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC)

internal fun utc(epochMillis: Long): String = UTC_TIME.format(java.time.Instant.ofEpochMilli(epochMillis))

internal fun Double.short(): String = if (this >= 100) "%.2f".format(this) else if (this >= 1) "%.4f".format(this) else "%.6f".format(this)
