package com.spotbot.core.rules

import com.spotbot.core.analysis.IndicatorSnapshot
import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.TimeframeAnalysis

/**
 * Özel kurallar için küçük koşul dili:
 *
 * ```
 * koşul   := ifade ( ("and" | "ve") ifade )*
 * ifade   := terim ( > | >= | < | <= | == | != ) terim
 * terim   := sayı | değişken | SÖZCÜK
 * ```
 *
 * Değişkenler: `h4.`, `h1.`, `m15.` ve `btc.h4.`, `btc.h1.` ön ekleriyle `close, emaFast, emaMid, emaSlow,
 * emaTrend, rsi, macd, macdSignal, macdHistogram, atr, adx, plusDi, minusDi, superTrend (1/-1), vwap, volume,
 * volumeAverage, regime, trend`; önceki mum için `h1.prev.rsi` gibi. Ayrıca `setup.rr, setup.entry, setup.stop,
 * setup.target, setup.stopDistancePercent, setup.confirmations, setup.confluences, coin.spread, coin.volume24h,
 * coin.change24h, market.breadth`.
 */
class RuleExpression private constructor(private val clauses: List<Clause>) {

    private data class Clause(val left: String, val op: String, val right: String)

    fun evaluate(context: RuleContext): Pair<Boolean, String> {
        val variables = Variables(context)
        val details = clauses.map { clause ->
            val l = variables.resolve(clause.left)
            val r = variables.resolve(clause.right)
            val passed = compare(l, clause.op, r)
            passed to "${clause.left}=${l.display()} ${clause.op} ${r.display()}"
        }
        return details.all { it.first } to details.joinToString("; ") { it.second }
    }

    private fun compare(l: Any?, op: String, r: Any?): Boolean {
        if (l is Double && r is Double) {
            if (l.isNaN() || r.isNaN()) return false
            return when (op) {
                ">" -> l > r
                ">=" -> l >= r
                "<" -> l < r
                "<=" -> l <= r
                "==" -> l == r
                "!=" -> l != r
                else -> false
            }
        }
        val ls = l?.toString()?.uppercase()
        val rs = r?.toString()?.uppercase()
        return when (op) {
            "==" -> ls != null && ls == rs
            "!=" -> ls != null && ls != rs
            else -> false
        }
    }

    private fun Any?.display(): String = when (this) {
        null -> "?"
        is Double -> if (this >= 100 || this <= -100) "%.2f".format(this) else "%.4f".format(this)
        else -> toString()
    }

    private class Variables(private val c: RuleContext) {
        fun resolve(token: String): Any? {
            token.toDoubleOrNull()?.let { return it }
            if (!token.contains('.')) return token
            val parts = token.split('.')
            return when (parts[0]) {
                "h4", "h1", "m15" -> timeframe(c.mtf, parts[0])?.let { field(it, parts.drop(1)) }
                "btc" -> c.btc?.let { btc -> timeframe(btc, parts.getOrNull(1) ?: "")?.let { field(it, parts.drop(2)) } }
                "setup" -> setup(parts.getOrNull(1))
                "coin" -> when (parts.getOrNull(1)) {
                    "spread" -> c.scan?.spreadPercent
                    "volume24h" -> c.scan?.quoteVolume24h
                    "change24h" -> c.scan?.change24hPercent
                    else -> null
                }
                "market" -> if (parts.getOrNull(1) == "breadth") c.market.breadthPercent else null
                else -> null
            }
        }

        private fun timeframe(mtf: MultiTimeframe, name: String): TimeframeAnalysis? = when (name) {
            "h4" -> mtf.h4
            "h1" -> mtf.h1
            "m15" -> mtf.m15
            else -> null
        }

        private fun field(tf: TimeframeAnalysis, path: List<String>): Any? {
            if (path.firstOrNull() == "prev") return tf.previous?.let { snapshotField(it, path.getOrNull(1)) }
            return when (path.firstOrNull()) {
                "regime" -> tf.regime.name
                "trend" -> tf.structure.trend.name
                else -> snapshotField(tf.indicators, path.firstOrNull())
            }
        }

        private fun snapshotField(s: IndicatorSnapshot, name: String?): Double? = when (name) {
            "close" -> s.close
            "emaFast" -> s.emaFast
            "emaMid" -> s.emaMid
            "emaSlow" -> s.emaSlow
            "emaTrend" -> s.emaTrend
            "rsi" -> s.rsi
            "macd" -> s.macd
            "macdSignal" -> s.macdSignal
            "macdHistogram" -> s.macdHistogram
            "atr" -> s.atr
            "adx" -> s.adx
            "plusDi" -> s.plusDi
            "minusDi" -> s.minusDi
            "superTrend" -> if (s.superTrendUp) 1.0 else -1.0
            "vwap" -> s.vwap
            "volume" -> s.volume
            "volumeAverage" -> s.volumeAverage
            else -> null
        }

        private fun setup(name: String?): Double? = with(c.setup) {
            when (name) {
                "rr" -> c.riskReward.ratio
                "entry" -> entryPrice
                "stop" -> stopPrice
                "target" -> targetPrice
                "stopDistancePercent" -> (entryPrice - stopPrice) / entryPrice * 100
                "confirmations" -> confirmations.size.toDouble()
                "confluences" -> confluences.size.toDouble()
                else -> null
            }
        }
    }

    companion object {
        private val operators = listOf(">=", "<=", "==", "!=", ">", "<")

        /** Koşulu ayrıştırır; hatalıysa açıklayıcı bir istisna fırlatır. */
        fun parse(condition: String): RuleExpression {
            val parts = condition.trim().split(Regex("\\s+(?i:and|ve)\\s+"))
            require(parts.isNotEmpty() && parts.all { it.isNotBlank() }) { "Boş koşul" }
            return RuleExpression(
                parts.map { part ->
                    val op = operators.firstOrNull { part.contains(it) } ?: throw IllegalArgumentException("Karşılaştırma işleci yok: '$part'")
                    val (l, r) = part.split(op, limit = 2).map { it.trim() }
                    require(l.isNotEmpty() && r.isNotEmpty()) { "Eksik terim: '$part'" }
                    Clause(l, op, r)
                },
            )
        }
    }
}
