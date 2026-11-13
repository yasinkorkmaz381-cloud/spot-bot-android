package com.spotbot.core.kr

import com.spotbot.core.kr.KrConfig.Companion.H1
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** Gün sınırları TSİ'ye (Europe/Istanbul) göre. */
val TURKEY: ZoneId = ZoneId.of("Europe/Istanbul")

fun dayKey(time: Long): String = Instant.ofEpochMilli(time).atZone(TURKEY).toLocalDate().toString()

/**
 * Sanal hesap: nakit, açık pozisyonlar ve kapanmış işlemler. Backtest ve paper aynı sınıfı kullanır. Değişmez; her
 * işlem yeni bir kopya döndürür (paper kaydı atomik yazılabilsin).
 */
@Serializable
data class KrBook(
    val cash: Double,
    val positions: List<KrPosition> = emptyList(),
    val trades: List<KrTrade> = emptyList(),
    val day: String? = null,
    val dayStartEquity: Double = cash,
) {
    val openRisk: Double get() = positions.sumOf { it.riskAmount }
    val openNotional: Double get() = positions.sumOf { it.notional }

    /** Piyasa değeri: açık pozisyonlar [marks] fiyatından (yoksa giriş fiyatından). */
    fun equity(marks: Map<String, Double>): Double = cash + positions.sumOf { it.quantity * (marks[it.symbol] ?: it.entryPrice) }

    fun has(symbol: String) = positions.any { it.symbol == symbol }

    /** TSİ günü değiştiyse gün başı özsermayesini yeniler. */
    fun rollDay(time: Long, marks: Map<String, Double>): KrBook {
        val key = dayKey(time)
        return if (key == day) this else copy(day = key, dayStartEquity = equity(marks))
    }

    /** Günlük gerçekleşmiş + gerçekleşmemiş kayıp sınırı aşıldı mı. */
    fun dailyLossHit(marks: Map<String, Double>, risk: KrRisk): Boolean =
        dayStartEquity - equity(marks) >= dayStartEquity * risk.dailyLossPercent / 100

    fun open(p: KrPosition): KrBook = copy(cash = cash - p.notional - p.entryFee, positions = positions + p)

    fun close(t: KrTrade): KrBook = copy(
        cash = cash + t.exitPrice * t.quantity * (1 - t.exitLeg.fee),
        positions = positions.filterNot { it.id == t.id },
        trades = trades + t,
    )

    fun update(p: KrPosition): KrBook = copy(positions = positions.map { if (it.id == p.id) p else it })
}

/** Giriş öncesi piyasa süzgeçleri (P1–P3, P5). Backtest ve paper aynı fonksiyonu kullanır. */
object KrFilters {
    const val BTC = "BTCUSDT"

    fun market(plan: SetupPlan, barCount: Int, btcReturn4h: Double, btcReturn24h: Double, cfg: KrConfig, btcUptrend: Boolean? = null): Rejection? = when {
        barCount < cfg.minBars15 || plan.decisionTime - plan.cleanSince < cfg.dataCleanHours * H1 -> Rejection.DATA
        !(plan.quoteVolume24h >= cfg.minQuoteVolume24h) -> Rejection.LIQUIDITY
        // Çelişki/veri yoksa bekle (kural 58): BTC verisi yoksa giriş yok.
        btcReturn4h.isNaN() || btcReturn4h <= -cfg.btcShockPercent -> Rejection.BTC_SHOCK
        cfg.requireBtcUptrend && btcUptrend != true -> Rejection.BTC_TREND
        // BTC'nin kendine göre gücü tanımsızdır; BTC bu süzgeçten muaftır.
        plan.symbol != BTC && (plan.return24hPercent.isNaN() || btcReturn24h.isNaN() ||
            plan.return24hPercent - btcReturn24h <= cfg.minRelativeStrengthPercent) -> Rejection.WEAK_RS
        else -> null
    }
}
