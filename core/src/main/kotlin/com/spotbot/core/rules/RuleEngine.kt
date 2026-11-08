package com.spotbot.core.rules

/** Zorunlu kurallar hep uygulanır; kalite kuralları ağırlıklı puan (0–100) üretir. */
class RuleEngine(val config: RuleConfig = RuleConfig.default()) {

    private val settings = config.builtIn.associateBy { it.id }
    private val customRules = config.custom.filter { it.enabled }.map { rule ->
        rule to runCatching { RuleExpression.parse(rule.condition) }
    }

    /** Yapılandırmadaki hatalı özel kural ifadeleri (panelde gösterilir). */
    val configErrors: List<String> = customRules.mapNotNull { (rule, parsed) ->
        parsed.exceptionOrNull()?.let { "${rule.id}: ${it.message}" }
    }

    val activeRuleCount: Int
        get() = BuiltInRules.all.count { it.group == RuleGroup.MANDATORY || settings[it.id]?.enabled != false } + customRules.size

    fun evaluate(context: RuleContext): RuleReport {
        val outcomes = buildList {
            BuiltInRules.all.forEach { rule ->
                val setting = settings[rule.id]
                if (rule.group == RuleGroup.QUALITY && setting?.enabled == false) return@forEach
                val (passed, detail) = runCatching { rule.check(context) }.getOrElse { false to "Hesaplanamadı: ${it.message}" }
                add(RuleOutcome(rule.id, rule.title, rule.group, passed, detail, setting?.weight ?: rule.defaultWeight))
            }
            customRules.forEach { (rule, parsed) ->
                val (passed, detail) = parsed.fold(
                    onSuccess = { expr -> runCatching { expr.evaluate(context) }.getOrElse { false to "Hesaplanamadı: ${it.message}" } },
                    onFailure = { false to "Koşul hatalı: ${it.message}" },
                )
                add(RuleOutcome(rule.id, rule.title, rule.group, passed, detail, rule.weight))
            }
        }
        val quality = outcomes.filter { it.group == RuleGroup.QUALITY }
        val total = quality.sumOf { it.weight }
        val score = if (total > 0) quality.filter { it.passed }.sumOf { it.weight } / total * 100 else 100.0
        return RuleReport(outcomes, score, config.minQualityScore)
    }
}
