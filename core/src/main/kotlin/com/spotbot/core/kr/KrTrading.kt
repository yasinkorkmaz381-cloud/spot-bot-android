package com.spotbot.core.kr

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Çıkış nedenleri, öncelik sırasıyla (docs/KR-ONKAYIT.md §1.3). */
@Serializable
enum class ExitReason(val title: String) {
    STOP("Stop"),
    TARGET("Hedef"),
    INVALIDATION("Retest yapısı bozuldu"),
    NO_PROGRESS("Hareket başlamadı (2 saat)"),
    MAX_HOLD("En uzun süre doldu (6 saat)"),
}

/** Çıkışın nasıl tespit edildiği. */
@Serializable
enum class ExitSource(val title: String) {
    LIVE("Canlı izlendi"),
    BACKFILLED("Geçmiş mumlardan hesaplandı (o sırada canlı izlenmedi)"),
    BACKTEST("Backtest"),
}

/** Bir paritenin işlem anındaki gerçekleşme girdileri; işlem kaydında saklanır, sonradan değişmez. */
@Serializable
data class SymbolTerms(
    val takerFee: Double? = null,
    val halfSpread: Double? = null,
    val quantityStep: Double = 0.0,
    val minNotional: Double = 0.0,
)

@Serializable
data class KrPosition(
    val id: String,
    val symbol: String,
    val setupId: String,
    val entryTime: Long,
    /** Maliyet öncesi referans (backtest: mum açılışı; paper: orta fiyat). */
    val refPrice: Double,
    val entryPrice: Double,
    val quantity: Double,
    val stop: Double,
    val target: Double,
    val level: Double,
    val invalidation: Double,
    val entryLeg: Leg,
    val exitLeg: Leg,
    val entryFee: Double,
    val plannedNetRewardRisk: Double,
    /** Stopta beklenen net kayıp (USDT, maliyetler dahil). */
    val riskAmount: Double,
    val highest: Double,
    val barsHeld: Int = 0,
    val pendingExit: ExitReason? = null,
    /** Son izlenen an (paper): sert stop bu ana kadar denetlendi. */
    val checkedUntil: Long = entryTime,
) {
    val notional: Double get() = entryPrice * quantity
}

@Serializable
data class KrTrade(
    val id: String,
    val symbol: String,
    val setupId: String,
    val entryTime: Long,
    val entryPrice: Double,
    val quantity: Double,
    val stop: Double,
    val target: Double,
    val level: Double,
    val exitTime: Long,
    val exitPrice: Double,
    val reason: ExitReason,
    val grossPnl: Double,
    val fees: Double,
    val netPnl: Double,
    val riskAmount: Double,
    val plannedNetRewardRisk: Double,
    /** Aynı mumda stop ve hedef görüldü, sıra bilinmedi → stop sayıldı. */
    val ambiguous: Boolean = false,
    /** Çıkışın tespit edildiği mum aralığı (kesin saniye bilinmez). */
    val exitWindowFrom: Long = exitTime,
    val exitWindowTo: Long = exitTime,
    val source: ExitSource = ExitSource.BACKTEST,
    val entryLeg: Leg,
    val exitLeg: Leg,
) {
    val rMultiple: Double get() = if (riskAmount > 0) netPnl / riskAmount else 0.0
    val holdMinutes: Long get() = (exitTime - entryTime) / 60_000
    /** İki yön maliyet: komisyonlar + spread/kayma etkisi (USDT). */
    val costs: Double get() = fees + quantity * (entryPrice * entryLeg.priceImpact / (1 + entryLeg.priceImpact)) +
        quantity * (if (reason == ExitReason.TARGET) 0.0 else exitPrice * exitLeg.priceImpact / (1 - exitLeg.priceImpact))
}

/** Dolum anında hedef, net R:R ve maliyet payı (G4–G6). Backtest ve paper aynı fonksiyonu kullanır. */
object KrPricing {
    data class Quote(val fill: Double, val target: Double, val netRewardRisk: Double, val costShare: Double)

    fun target(plan: SetupPlan, fill: Double, cfg: KrConfig): Double {
        val structural = plan.levelsAbove.firstOrNull { it > fill } ?: Double.POSITIVE_INFINITY
        return min(structural, fill + cfg.targetAtr1h * plan.atr1h)
    }

    fun netRewardRisk(fill: Double, target: Double, stop: Double, entry: Leg, exit: Leg): Double {
        val cost = fill * (1 + entry.fee)
        val win = target * (1 - exit.fee) - cost
        val loss = cost - stop * (1 - exit.priceImpact) * (1 - exit.fee)
        return if (loss <= 0) Double.NaN else win / loss
    }

    /** İki yön maliyet / brüt hedef hareketi (referans fiyattan). */
    fun costShare(ref: Double, fill: Double, target: Double, entry: Leg, exit: Leg): Double {
        val gross = target - ref
        if (gross <= 0) return Double.POSITIVE_INFINITY
        val costs = (fill - ref) + fill * entry.fee + target * exit.fee
        return costs / gross
    }

    fun quote(plan: SetupPlan, ref: Double, entry: Leg, exit: Leg, cfg: KrConfig): Quote {
        val fill = ref * (1 + entry.priceImpact)
        val target = target(plan, fill, cfg)
        return Quote(fill, target, netRewardRisk(fill, target, plan.stop, entry, exit), costShare(ref, fill, target, entry, exit))
    }
}

/** Miktar hesabı (§2). Kapasite yalnız başarılı girişte azalır: bu fonksiyon durum değiştirmez. */
object KrSizing {
    sealed interface Result {
        data class Ok(val quantity: Double, val riskAmount: Double) : Result
        data class Rejected(val reason: Rejection) : Result
    }

    fun size(
        fill: Double,
        stop: Double,
        entry: Leg,
        exit: Leg,
        equity: Double,
        cash: Double,
        openNotional: Double,
        openRisk: Double,
        openCount: Int,
        risk: KrRisk,
        terms: SymbolTerms,
    ): Result {
        if (openCount >= risk.maxPositions) return Result.Rejected(Rejection.MAX_POSITIONS)
        val perUnitLoss = fill * (1 + entry.fee) - stop * (1 - exit.priceImpact) * (1 - exit.fee)
        if (!(perUnitLoss > 0)) return Result.Rejected(Rejection.BAD_STOP)
        val riskRoom = min(equity * risk.riskPercent / 100, equity * risk.maxOpenRiskPercent / 100 - openRisk)
        if (riskRoom <= 0) return Result.Rejected(Rejection.RISK_FULL)
        val totalRoom = equity * risk.maxTotalPercent / 100 - openNotional
        if (totalRoom <= 0) return Result.Rejected(Rejection.CAPITAL_FULL)
        var qty = riskRoom / perUnitLoss
        qty = min(qty, equity * risk.maxPositionPercent / 100 / fill)
        qty = min(qty, totalRoom / fill)
        qty = min(qty, cash / (fill * (1 + entry.fee)))
        if (terms.quantityStep > 0) qty = floor(qty / terms.quantityStep + 1e-9) * terms.quantityStep
        if (qty <= 0) return Result.Rejected(if (cash < equity * 0.01) Rejection.CASH else Rejection.MIN_NOTIONAL)
        if (qty * fill < max(terms.minNotional, 1.0)) return Result.Rejected(Rejection.MIN_NOTIONAL)
        return Result.Ok(qty, qty * perUnitLoss)
    }
}

/** Elenme nedenleri (kaçan fırsat hunisi). */
@Serializable
enum class Rejection(val title: String) {
    NOT_IN_UNIVERSE("CMC ilk 300 / MEXC eşleşmesi dışında"),
    LIST_STALE("CMC listesi geçersiz (bayat)"),
    DATA("Veri boşluğu / geçmiş yetersiz"),
    LIQUIDITY("24 saatlik hacim düşük"),
    SPREAD("Spread yüksek"),
    BOOK("Emir defteri yetersiz (kayma yüksek)"),
    BTC_SHOCK("BTC sert düşüşte (bekle)"),
    BTC_TREND("BTC 4H yönü yükselişte değil"),
    WEAK_RS("BTC'ye göre zayıf"),
    EVENT("Olay riski penceresi / risk etiketi"),
    ALREADY_OPEN("Aynı coinde açık pozisyon"),
    DAILY_LOSS("Günlük kayıp sınırı"),
    ENTRIES_OFF("Yeni girişler kapalı"),
    LATE("Karar anı kaçırıldı (bot o an çalışmıyordu)"),
    NO_BAR("Dolum mumu yok"),
    CHASE("Fiyat giriş bölgesinden uzaklaştı"),
    NO_TARGET("Hedef hesaplanamadı"),
    RR_LOW("Net R:R < 1,2"),
    COST_HIGH("Maliyet hedefe göre yüksek"),
    BAD_STOP("Stop girişin altında değil"),
    MAX_POSITIONS("4 açık pozisyon dolu"),
    RISK_FULL("Toplam açık risk dolu"),
    CAPITAL_FULL("Toplam sermaye kullanımı dolu"),
    CASH("Bakiye yetersiz"),
    MIN_NOTIONAL("Asgari emir tutarının altında"),
}

/** Çıkış kuralları (§1.3). */
object KrExits {
    data class Hit(val reason: ExitReason, val price: Double, val ambiguous: Boolean)

    /** Girişten tamamen sonra kalan bir fiyat aralığında (mum) sert stop ve hedef. Stop önce gelir. */
    fun intrabar(p: KrPosition, open: Double, high: Double, low: Double): Hit? {
        val targetTrigger = p.target * (1 + p.exitLeg.halfSpread)
        if (low <= p.stop) {
            val raw = min(open, p.stop)
            return Hit(ExitReason.STOP, raw * (1 - p.exitLeg.priceImpact), ambiguous = high >= targetTrigger && open > p.stop)
        }
        if (high >= targetTrigger) return Hit(ExitReason.TARGET, p.target, false)
        return null
    }

    /** 15M kapanışında: tez iptali, ilerleme yok, en uzun süre. [p] bu mumla güncellenmiş olmalı. */
    fun onClose(p: KrPosition, close: Double, cfg: KrConfig): ExitReason? = when {
        close < p.invalidation -> ExitReason.INVALIDATION
        p.barsHeld == cfg.progressBars && p.highest < p.entryPrice + cfg.progressFraction * (p.target - p.entryPrice) -> ExitReason.NO_PROGRESS
        p.barsHeld >= cfg.maxHoldBars -> ExitReason.MAX_HOLD
        else -> null
    }

    data class Replay(val position: KrPosition, val trade: KrTrade?)

    /**
     * Paper koruması: [p] için son denetimden ([KrPosition.checkedUntil]) sonra kapanan 1 dakikalık mumlar zaman
     * sırasıyla işlenir, sonra anlık fiyat ([live]). Her mumda öncelik: bekleyen kapanış çıkışı (bu mumun açılışından)
     * → sert stop → hedef; çeyrek saat kapanışında (15M kapanışı) tez iptali / ilerleme yok / en uzun süre. Girişi
     * içeren ilk dakikada stop/hedef görülürse sıra bilinmez ("belirsiz" işaretlenir). [untracked] aralığa düşen mumdan
     * çıkan çıkış "canlı izlenmedi" (geçmişten hesaplandı) olarak işaretlenir.
     */
    fun replay(p: KrPosition, minuteBars: List<com.spotbot.core.model.Candle>, live: com.spotbot.core.model.BookTicker?, now: Long, cfg: KrConfig, untracked: (Long, Long) -> Boolean): Replay {
        var pos = p
        for (b in minuteBars.filter { it.closeTime > p.checkedUntil && it.closeTime <= now }.sortedBy { it.openTime }) {
            val source = if (untracked(b.openTime, b.closeTime)) ExitSource.BACKFILLED else ExitSource.LIVE
            pos.pendingExit?.let { r ->
                return Replay(pos, close(pos, b.openTime, b.open * (1 - pos.exitLeg.priceImpact), r, source, windowFrom = b.openTime, windowTo = b.openTime))
            }
            val partial = b.openTime < pos.entryTime
            val hit = intrabar(pos, if (partial) pos.entryPrice else b.open, b.high, b.low)
            if (hit != null) {
                return Replay(pos, close(pos, b.closeTime, hit.price, hit.reason, source, ambiguous = hit.ambiguous || partial, windowFrom = maxOf(b.openTime, pos.entryTime), windowTo = b.closeTime))
            }
            pos = pos.copy(highest = maxOf(pos.highest, b.high), checkedUntil = b.closeTime)
            if (b.closeTime % KrConfig.M15 == 0L && b.closeTime > pos.entryTime) {
                pos = pos.copy(barsHeld = pos.barsHeld + 1)
                onClose(pos, b.close, cfg)?.let { pos = pos.copy(pendingExit = it) }
            }
        }
        val bid = live?.bid
        if (live != null && bid != null && bid > 0 && live.time >= pos.checkedUntil) {
            val t = when {
                pos.pendingExit != null -> close(pos, now, bid * (1 - pos.exitLeg.slippage), pos.pendingExit!!, ExitSource.LIVE)
                bid <= pos.stop -> close(pos, now, bid * (1 - pos.exitLeg.slippage), ExitReason.STOP, ExitSource.LIVE)
                bid >= pos.target -> close(pos, now, pos.target, ExitReason.TARGET, ExitSource.LIVE)
                else -> null
            }
            if (t != null) return Replay(pos, t)
            pos = pos.copy(highest = maxOf(pos.highest, bid))
        }
        return Replay(pos, null)
    }

    fun close(p: KrPosition, time: Long, price: Double, reason: ExitReason, source: ExitSource, ambiguous: Boolean = false, windowFrom: Long = time, windowTo: Long = time): KrTrade {
        val exitFee = price * p.quantity * p.exitLeg.fee
        val gross = (price - p.entryPrice) * p.quantity
        return KrTrade(
            id = p.id, symbol = p.symbol, setupId = p.setupId, entryTime = p.entryTime, entryPrice = p.entryPrice,
            quantity = p.quantity, stop = p.stop, target = p.target, level = p.level, exitTime = time, exitPrice = price,
            reason = reason, grossPnl = gross, fees = p.entryFee + exitFee, netPnl = gross - p.entryFee - exitFee,
            riskAmount = p.riskAmount, plannedNetRewardRisk = p.plannedNetRewardRisk, ambiguous = ambiguous,
            exitWindowFrom = windowFrom, exitWindowTo = windowTo, source = source, entryLeg = p.entryLeg, exitLeg = p.exitLeg,
        )
    }
}
