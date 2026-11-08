package com.spotbot.core.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Kural yapılandırması (JSON). Varsayılanı `rules/default-rules.json` kaynağındadır.
 *
 * - [builtIn]: yerleşik kalite kurallarının açık/kapalı durumu ve ağırlığı. Zorunlu kurallar kapatılamaz.
 * - [custom]: kod yazmadan eklenen kurallar; koşul ifadesi [RuleExpression] dilindedir.
 */
@Serializable
data class RuleConfig(
    val version: Int = 1,
    val minQualityScore: Double = 0.0,
    val builtIn: List<RuleSetting> = emptyList(),
    val custom: List<CustomRule> = emptyList(),
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun parse(text: String): RuleConfig = json.decodeFromString(serializer(), text)

        fun default(): RuleConfig = parse(
            RuleConfig::class.java.getResourceAsStream("/rules/default-rules.json")!!.bufferedReader().readText(),
        )
    }
}

@Serializable
data class RuleSetting(val id: String, val enabled: Boolean = true, val weight: Double? = null)

@Serializable
data class CustomRule(
    val id: String,
    val title: String,
    val group: RuleGroup,
    /** Örn. `h4.adx >= 25 and m15.rsi < 70` ya da `h4.regime == TREND_UP`. */
    val condition: String,
    val weight: Double = 1.0,
    val enabled: Boolean = true,
    /** Kuralın kaynağı / açıklaması (örn. "60 kural listesi, madde 12"). */
    val source: String? = null,
)
