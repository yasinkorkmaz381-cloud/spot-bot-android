package com.spotbot.core.trading

import com.spotbot.core.model.Candle
import kotlinx.serialization.Serializable

@Serializable
enum class EntryMode {
    /** Sinyal sonrası piyasa emri (taker): spread + kayma + taker komisyonu. */
    MARKET,

    /** Sinyal kapanış fiyatına limit (maker) emir; fiyat bu seviyenin altına inerse dolar, aksi halde iptal. */
    LIMIT,
}

/**
 * Emir ve pozisyon yönetimi seçenekleri. Varsayılanlar mevcut davranıştır (piyasa emri, sabit stop/hedef);
 * diğer seçenekler backtest'te ayrı dönemde doğrulanmadan etkinleştirilmemelidir.
 */
@Serializable
data class ExecutionConfig(
    val entryMode: EntryMode = EntryMode.MARKET,
    /** Limit giriş emrinin geçerli kalacağı 15M mum sayısı. */
    val limitEntryMaxBars: Int = 2,
    /** Kâr hedefine limit (maker) emirle çıkılır; yalnız fiyat hedefin üzerinden işlem görürse dolar. */
    val limitTakeProfit: Boolean = false,
    /** Fiyat giriş + bu kadar R'ye ulaşınca stop giriş fiyatına çekilir. */
    val breakEvenAtR: Double? = null,
    /** Fiyat giriş + bu kadar R'ye ulaşınca pozisyonun [partialFraction] kadarı kapatılır. */
    val partialTakeProfitAtR: Double? = null,
    val partialFraction: Double = 0.5,
    /** Pozisyon bu kadar saatten uzun açık kalırsa kapatılır. */
    val maxHoldingHours: Int? = null,
    /** Bir coinde son [cooldownTrades] işlemin toplamı −[cooldownLossR] R veya daha kötüyse coin [cooldownDays] gün dinlenir. */
    val cooldownTrades: Int? = null,
    val cooldownLossR: Double = 3.0,
    val cooldownDays: Int = 7,
)

sealed interface ExitAction {
    data class MoveStop(val price: Double, val note: String) : ExitAction
    data class PartialClose(val fraction: Double, val price: Double, val maker: Boolean, val note: String) : ExitAction
    data class Close(val price: Double, val reason: ExitReason, val maker: Boolean, val note: String) : ExitAction
}

/** Stop, hedef, başa baş, kısmi kâr ve süre çıkışlarını belirler. Canlı motor ve backtest aynı mantığı kullanır. */
object ExitManager {
    private const val HOUR = 60 * 60_000L

    private fun riskDistance(p: Position) = (p.entryPrice - p.initialStopPrice).coerceAtLeast(p.entryPrice * 1e-6)

    private fun stopReason(p: Position) = if (p.breakEvenSet) ExitReason.BREAK_EVEN else ExitReason.STOP_LOSS

    /** Canlı fiyat güncellemesi (en iyi alış fiyatı). */
    fun onPrice(p: Position, bid: Double, now: Long, cfg: ExecutionConfig): List<ExitAction> {
        if (bid <= p.stopPrice) return listOf(ExitAction.Close(bid, stopReason(p), maker = false, note = "Fiyat stop seviyesine indi (yazılım stopu)"))
        if (bid >= p.targetPrice) {
            return listOf(
                if (cfg.limitTakeProfit) ExitAction.Close(p.targetPrice, ExitReason.TAKE_PROFIT, maker = true, note = "Hedef limit emri doldu")
                else ExitAction.Close(bid, ExitReason.TAKE_PROFIT, maker = false, note = "Fiyat hedefe ulaştı"),
            )
        }
        val actions = mutableListOf<ExitAction>()
        val r = riskDistance(p)
        cfg.partialTakeProfitAtR?.let { atR ->
            val trigger = p.entryPrice + atR * r
            if (!p.partialTaken && bid >= trigger) {
                actions += if (cfg.limitTakeProfit) ExitAction.PartialClose(cfg.partialFraction, trigger, true, "+%.1fR kısmi kâr (limit)".format(atR))
                else ExitAction.PartialClose(cfg.partialFraction, bid, false, "+%.1fR kısmi kâr".format(atR))
            }
        }
        cfg.breakEvenAtR?.let { atR ->
            if (!p.breakEvenSet && bid >= p.entryPrice + atR * r) actions += ExitAction.MoveStop(p.entryPrice, "+%.1fR görüldü, stop girişe çekildi".format(atR))
        }
        cfg.maxHoldingHours?.let { hours ->
            if (now - p.openedAt >= hours * HOUR) actions += ExitAction.Close(bid, ExitReason.TIME_EXIT, maker = false, note = "$hours saatlik süre doldu")
        }
        return actions
    }

    /**
     * Backtest: bir mumun içindeki sıra bilinmediği için ihtiyatlı davranılır — önce stop denetlenir; başa baş
     * stop ancak bir sonraki mumdan itibaren geçerli olur; limit emirler fiyat seviyeyi aşarsa dolar.
     */
    fun onBar(p: Position, bar: Candle, cfg: ExecutionConfig): List<ExitAction> {
        if (bar.open <= p.stopPrice) return listOf(ExitAction.Close(bar.open, stopReason(p), false, "Fiyat stopun altında açıldı (boşluk)"))
        if (bar.low <= p.stopPrice) return listOf(ExitAction.Close(p.stopPrice, stopReason(p), false, "Mum içinde stop"))
        val actions = mutableListOf<ExitAction>()
        val r = riskDistance(p)
        cfg.partialTakeProfitAtR?.let { atR ->
            val trigger = p.entryPrice + atR * r
            val hit = if (cfg.limitTakeProfit) bar.high > trigger else bar.high >= trigger
            if (!p.partialTaken && hit && trigger < p.targetPrice) {
                actions += ExitAction.PartialClose(cfg.partialFraction, maxOf(trigger, bar.open), cfg.limitTakeProfit, "+%.1fR kısmi kâr".format(atR))
            }
        }
        val targetHit = if (cfg.limitTakeProfit) bar.high > p.targetPrice else bar.high >= p.targetPrice
        if (bar.open >= p.targetPrice || targetHit) {
            actions += ExitAction.Close(maxOf(p.targetPrice, bar.open), ExitReason.TAKE_PROFIT, cfg.limitTakeProfit, "Mum içinde hedef")
            return actions
        }
        cfg.breakEvenAtR?.let { atR ->
            if (!p.breakEvenSet && bar.high >= p.entryPrice + atR * r) actions += ExitAction.MoveStop(p.entryPrice, "+%.1fR görüldü, stop girişe çekildi".format(atR))
        }
        cfg.maxHoldingHours?.let { hours ->
            if (bar.closeTime - p.openedAt >= hours * HOUR) actions += ExitAction.Close(bar.close, ExitReason.TIME_EXIT, false, "$hours saatlik süre doldu")
        }
        return actions
    }

    /** Eylemleri sırayla hesaba uygular. */
    fun apply(state: PortfolioState, positionId: String, actions: List<ExitAction>, costs: com.spotbot.core.risk.CostModel, now: Long): PortfolioState {
        var s = state
        for (action in actions) {
            if (s.positions.none { it.id == positionId }) break
            s = when (action) {
                is ExitAction.MoveStop -> PaperAccount.moveStop(s, positionId, action.price, action.note, now)
                is ExitAction.PartialClose -> PaperAccount.partialClose(s, positionId, action.fraction, action.price, action.maker, action.note, costs, now)
                is ExitAction.Close -> PaperAccount.close(s, positionId, action.price, action.reason, action.note, costs, now, maker = action.maker)
            }
        }
        return s
    }
}

/** Üst üste zarar eden coinin dinlendirilmesi. */
object Cooldown {
    private const val DAY = 24 * 60 * 60_000L

    fun reason(closedTrades: List<ClosedTrade>, symbol: String, now: Long, cfg: ExecutionConfig): String? {
        val n = cfg.cooldownTrades ?: return null
        val recent = closedTrades.filter { it.position.symbol == symbol }.takeLast(n)
        if (recent.size < n) return null
        val sumR = recent.sumOf { it.rMultiple }
        val lastClose = recent.maxOf { it.closedAt }
        return if (sumR <= -cfg.cooldownLossR && now - lastClose < cfg.cooldownDays * DAY) {
            "Son $n işlem toplamı %.1fR; ${cfg.cooldownDays} günlük dinlenme".format(sumR)
        } else null
    }
}
