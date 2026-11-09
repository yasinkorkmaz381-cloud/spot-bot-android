package com.spotbot.core.trading

import com.spotbot.core.risk.AtrStopMode
import com.spotbot.core.risk.RiskConfig
import kotlinx.serialization.Serializable

/** Panelde gösterilen anlık risk durumu. */
@Serializable
data class RiskStatus(
    val equity: Double = 0.0,
    val openRiskPercent: Double = 0.0,
    val maxTotalOpenRiskPercent: Double = 0.0,
    val dailyPnlPercent: Double = 0.0,
    val dailyLossLimitPercent: Double = 0.0,
    val openPositions: Int = 0,
    val pendingOrders: Int = 0,
    val maxOpenPositions: Int = 0,
    val riskPerTradePercent: Double = 0.0,
    val correlatedPairs: List<String> = emptyList(),
    val profile: StrategyProfile = StrategyProfile.ADAPTIVE,
    val atrStopMode: AtrStopMode = AtrStopMode.OFF,
    /** Yeni işlem şu an neden açılamaz (açılabiliyorsa null). */
    val entriesBlockedReason: String? = null,
) {
    companion object {
        fun of(
            state: PortfolioState,
            equity: Double,
            risk: RiskConfig,
            profile: StrategyProfile,
            entriesPaused: Boolean,
            marketAllowsLongs: Boolean?,
            correlatedPairs: List<String>,
        ): RiskStatus {
            val openRisk = (state.positions.sumOf { it.openRisk } + state.pendingOrders.sumOf { it.riskAmount }) / equity * 100
            val daily = if (state.dayStartEquity > 0) (equity - state.dayStartEquity) / state.dayStartEquity * 100 else 0.0
            val count = state.positions.size + state.pendingOrders.size
            val blocked = when {
                entriesPaused -> "Yeni işlemler duraklatıldı"
                daily <= -risk.dailyLossLimitPercent -> "Günlük kayıp limiti doldu (%%%.2f)".format(daily)
                count >= risk.maxOpenPositions -> "Eş zamanlı işlem sınırı dolu (${risk.maxOpenPositions})"
                openRisk + risk.riskPerTradePercent > risk.maxTotalOpenRiskPercent + 1e-6 -> "Toplam açık risk sınırına yakın (%%%.2f / %%%.1f)".format(openRisk, risk.maxTotalOpenRiskPercent)
                marketAllowsLongs == false -> "BTC / piyasa filtresi alıma izin vermiyor"
                else -> null
            }
            return RiskStatus(
                equity, openRisk, risk.maxTotalOpenRiskPercent, daily, risk.dailyLossLimitPercent, state.positions.size,
                state.pendingOrders.size, risk.maxOpenPositions, risk.riskPerTradePercent, correlatedPairs, profile, risk.atrStopMode, blocked,
            )
        }
    }
}

/** Çalışma süresi hesabı: kaydedilen çevrimdışı süreler pencereden düşülür. */
object Reliability {
    fun uptimePercent(downtimes: List<Downtime>, from: Long, to: Long): Double? {
        if (to <= from) return null
        val down = downtimes.sumOf { d -> maxOf(0L, minOf(d.to, to) - maxOf(d.from, from)) }
        return (1 - down.toDouble() / (to - from)) * 100
    }
}
