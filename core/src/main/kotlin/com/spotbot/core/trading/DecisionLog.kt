package com.spotbot.core.trading

import com.spotbot.core.rules.RuleGroup
import com.spotbot.core.rules.RuleReport
import com.spotbot.core.strategy.StrategyId
import kotlinx.serialization.Serializable

/** Kararda kullanılan verilerin zamanı (epoch ms) — eski veriyle karar verilmediğini göstermek için. */
@Serializable
data class DataStamp(
    val m15Close: Long? = null,
    val h1Close: Long? = null,
    val h4Close: Long? = null,
    val priceTime: Long? = null,
)

@Serializable
data class RuleCheck(val id: String, val title: String, val mandatory: Boolean, val passed: Boolean, val detail: String)

@Serializable
data class StrategyCheck(
    val strategy: StrategyId,
    /** Profil, ayar ve 4H rejimine göre değerlendirmeye alındı mı. */
    val applicable: Boolean,
    val setupFound: Boolean,
    val reasons: List<String>,
    val entry: Double? = null,
    val stop: Double? = null,
    val target: Double? = null,
    val netRiskReward: Double? = null,
    val qualityScore: Double? = null,
    val rulesAllowed: Boolean? = null,
    val rules: List<RuleCheck> = emptyList(),
)

@Serializable
enum class CoinOutcome(val title: String) {
    OPENED("İşlem açıldı"),
    ORDER_PLACED("Limit emir verildi"),
    REJECTED("Kurallar reddetti"),
    NO_SETUP("Kurulum yok"),
    NOT_SELECTED("Aday seçilmedi"),
    ELIMINATED("Taramada elendi"),
    FLAGGED("Riskli işaretli"),
    STALE_DATA("Veri güncel değil"),
    PAUSED("Yeni işlemler duraklatıldı"),
    OBSERVE_ONLY("Yalnız gözlem"),
    ENTRY_FAILED("Emir gerçekleşmedi"),
}

/** Bir coin için bir taramadaki kararın tam kaydı. */
@Serializable
data class CoinDecision(
    val symbol: String,
    val outcome: CoinOutcome,
    val summary: String,
    val reasons: List<String> = emptyList(),
    val preScore: Int? = null,
    val data: DataStamp = DataStamp(),
    val strategies: List<StrategyCheck> = emptyList(),
)

/** Bir 15M taramasının tüm coinler için karar kaydı. */
@Serializable
data class ScanLog(
    val time: Long,
    /** Taramanın dayandığı 15M kapanış zamanı. */
    val boundary: Long,
    val profile: StrategyProfile,
    val marketAllowsLongs: Boolean,
    val marketReasons: List<String>,
    val entriesPaused: Boolean,
    val coins: List<CoinDecision>,
)

/** Karar kayıtlarının saklanması (Android: dönen dosyalar; CLI: dosya; test: bellek). */
interface DecisionLogStore {
    fun append(log: ScanLog)
    fun recent(limit: Int): List<ScanLog>
}

class InMemoryDecisionLogStore(private val capacity: Int = 200) : DecisionLogStore {
    private val logs = ArrayDeque<ScanLog>()

    @Synchronized
    override fun append(log: ScanLog) {
        logs.addLast(log)
        while (logs.size > capacity) logs.removeFirst()
    }

    @Synchronized
    override fun recent(limit: Int): List<ScanLog> = logs.toList().takeLast(limit)
}

object DecisionLogs {
    fun ruleChecks(report: RuleReport?): List<RuleCheck> =
        report?.outcomes?.map { RuleCheck(it.id, it.title, it.group == RuleGroup.MANDATORY, it.passed, it.detail) }.orEmpty()

    fun strategyChecks(evaluations: List<StrategyEvaluation>): List<StrategyCheck> = evaluations.map { e ->
        StrategyCheck(
            strategy = e.strategy,
            applicable = e.applicable,
            setupFound = e.setup != null,
            reasons = e.reasons,
            entry = e.setup?.entryPrice,
            stop = e.setup?.stopPrice,
            target = e.setup?.targetPrice,
            netRiskReward = e.riskReward?.ratio,
            qualityScore = e.report?.qualityScore,
            rulesAllowed = e.report?.allowed,
            rules = ruleChecks(e.report),
        )
    }
}

/** Kararın dayandığı verinin güncelliği (Z17). Canlı motor kullanır; saf fonksiyon olduğu için test edilebilir. */
object DataFreshness {
    /** Güncelse null; değilse nedenleri. [boundary] taramanın 15M kapanış zamanıdır. */
    fun issue(stamp: DataStamp, boundary: Long, now: Long, maxPriceAgeSeconds: Int): String? {
        val issues = buildList {
            if (stamp.m15Close != boundary) add("15M son mum beklenen kapanışta değil")
            if (stamp.h1Close != com.spotbot.core.model.Timeframe.H1.lastCloseAtOrBefore(boundary)) add("1H son mum eski")
            if (stamp.h4Close != com.spotbot.core.model.Timeframe.H4.lastCloseAtOrBefore(boundary)) add("4H son mum eski")
            val age = stamp.priceTime?.let { (now - it) / 1000 }
            if (age == null) add("Canlı fiyat yok") else if (age > maxPriceAgeSeconds) add("Canlı fiyat $age sn eski")
        }
        return issues.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
