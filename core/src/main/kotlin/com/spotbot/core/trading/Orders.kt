package com.spotbot.core.trading

import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.risk.CostModel
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

enum class OrderSide { BUY, SELL }
enum class OrderType { MARKET, LIMIT }

/** MEXC spot emir kurallarına uygunluk denetimi. Uygun değilse emir gönderilmez (sanalda da doldurulmaz). */
object OrderRules {
    /**
     * Miktarı borsa adımına aşağı yuvarlar. Çıkarma işlemlerinden kalan kayan nokta gürültüsü (ör. 0.2413909999…
     * ya da 9.99999e-7) bir alt adıma düşmesin diye değer önce 6 ek basamağa en yakın yuvarlanır.
     */
    fun roundQuantity(qty: Double, info: SymbolInfo?): Double = roundDown(qty, info?.quantityPrecision ?: 8, RoundingMode.DOWN)

    /** Limit fiyatını borsa adımına yuvarlar: alışta aşağı, satışta yukarı (daha kötü fiyata kaymamak için). */
    fun roundPrice(price: Double, side: OrderSide, info: SymbolInfo?): Double =
        roundDown(price, info?.pricePrecision ?: 8, if (side == OrderSide.BUY) RoundingMode.DOWN else RoundingMode.UP)

    private fun roundDown(value: Double, precision: Int, mode: RoundingMode): Double {
        val scale = precision.coerceAtLeast(0)
        return BigDecimal.valueOf(value).setScale(scale + 6, RoundingMode.HALF_UP).setScale(scale, mode).toDouble()
    }

    /**
     * Borsada tek başına satılamayacak kadar küçük miktar ("toz"): adıma yuvarlanınca sıfır, asgari miktarın ya da
     * asgari piyasa emri tutarının altında. Pozisyonun son kalanı tozsa pozisyon açık bırakılmaz.
     */
    fun isDust(quantity: Double, price: Double, info: SymbolInfo?): Boolean {
        val q = roundQuantity(quantity, info)
        if (q <= 0) return true
        info ?: return false
        return q < info.minQuantity || q * price < maxOf(info.minNotional, info.minMarketNotional)
    }

    /** Uygunsa null, değilse Türkçe gerekçe döner. [marketPrice] fiyat bandı denetimi içindir. */
    fun validate(side: OrderSide, type: OrderType, quantity: Double, price: Double, info: SymbolInfo?, marketPrice: Double? = null): String? {
        if (quantity <= 0 || price <= 0) return "Miktar ya da fiyat geçersiz"
        info ?: return null
        val notional = quantity * price
        if (!info.isOnline || !info.isSpotTradingAllowed) return "${info.symbol} API ile işleme kapalı"
        if (side == OrderSide.BUY && info.tradeSideType == 3 || side == OrderSide.SELL && info.tradeSideType == 2 || info.tradeSideType == 4) {
            return "${info.symbol} bu yönde işleme kapalı"
        }
        if (type == OrderType.MARKET && "MARKET" !in info.orderTypes) return "Piyasa emri bu paritede desteklenmiyor"
        if (quantity < info.minQuantity) return "Miktar asgari miktarın (${info.minQuantity}) altında"
        val minNotional = if (type == OrderType.MARKET) maxOf(info.minNotional, info.minMarketNotional) else info.minNotional
        if (notional < minNotional) return "Tutar (%.2f USDT) asgari emir tutarının (%.2f USDT) altında".format(notional, minNotional)
        val maxNotional = if (type == OrderType.MARKET) info.maxMarketQuoteAmount else info.maxQuoteAmount
        if (maxNotional > 0 && notional > maxNotional) return "Tutar en büyük emir tutarını (%.0f USDT) aşıyor".format(maxNotional)
        if (type == OrderType.LIMIT && marketPrice != null && marketPrice > 0) {
            if (side == OrderSide.BUY && info.buyPriceBandUp > 0 && price > marketPrice * (1 + info.buyPriceBandUp)) return "Alış fiyatı borsanın fiyat bandı dışında"
            if (side == OrderSide.SELL && info.sellPriceBandDown > 0 && price < marketPrice * (1 - info.sellPriceBandDown)) return "Satış fiyatı borsanın fiyat bandı dışında"
        }
        return null
    }
}

/** Bir emrin (sanal) gerçekleşmesi. [quantity] istenenden az ise kısmi gerçekleşmedir. */
@Serializable
data class Fill(
    val quantity: Double,
    val averagePrice: Double,
    val fee: Double,
    val maker: Boolean,
    val requestedQuantity: Double,
    val note: String = "",
) {
    val partial: Boolean get() = quantity < requestedQuantity * (1 - 1e-9)
}

sealed interface FillResult {
    data class Filled(val fill: Fill) : FillResult
    data class Rejected(val reason: String) : FillResult
}

/**
 * Gerçekçi dolum modelleri. Hiçbiri gerçekleşmesi mümkün olmayan bir emri doldurmaz:
 * - Canlı: piyasa emri emir defterini (derinlik) seviye seviye tüketir; [maxSlippage] bandı dışındaki
 *   seviyeler kullanılmaz, yetmezse kısmi gerçekleşir.
 * - Backtest: piyasa emri mum açılışı + yarım spread + kayma ile, en fazla mum USDT hacminin
 *   [participation] payı kadar dolar; limit emir yalnız fiyat limitin ötesinde işlem görürse dolar.
 */
object FillModel {

    fun walkBook(side: OrderSide, quantity: Double, book: OrderBook, maxSlippage: Double, costs: CostModel, info: SymbolInfo?): FillResult {
        val levels = if (side == OrderSide.BUY) book.asks else book.bids
        val best = levels.firstOrNull()?.price ?: return FillResult.Rejected("Emir defteri boş")
        val limit = if (side == OrderSide.BUY) best * (1 + maxSlippage) else best * (1 - maxSlippage)
        var remaining = quantity
        var cost = 0.0
        for (level in levels) {
            if (side == OrderSide.BUY && level.price > limit || side == OrderSide.SELL && level.price < limit) break
            val take = minOf(remaining, level.quantity)
            cost += take * level.price
            remaining -= take
            if (remaining <= 0) break
        }
        val filled = OrderRules.roundQuantity(quantity - maxOf(remaining, 0.0), info)
        if (filled <= 0) return FillResult.Rejected("Kayma bandı içinde (%.2f%%) derinlik yok".format(maxSlippage * 100))
        val average = cost / (quantity - maxOf(remaining, 0.0))
        OrderRules.validate(side, OrderType.MARKET, filled, average, info)?.let { return FillResult.Rejected(it) }
        val note = if (filled < quantity) "Kısmi gerçekleşme: derinlik %%%.2f bant içinde yalnız %s adet karşıladı".format(maxSlippage * 100, filled) else "Emir defterinden dolum"
        return FillResult.Filled(Fill(filled, average, average * filled * costs.takerFee, false, quantity, note))
    }

    fun marketFromBar(side: OrderSide, quantity: Double, reference: Double, quoteVolume: Double, participation: Double, costs: CostModel, info: SymbolInfo?): FillResult {
        val price = if (side == OrderSide.BUY) costs.buyPrice(reference) else costs.sellPrice(reference)
        val cap = if (participation > 0) participation * quoteVolume / price else Double.MAX_VALUE
        val filled = OrderRules.roundQuantity(minOf(quantity, cap), info)
        if (filled <= 0) return FillResult.Rejected("Mum hacmi emri karşılamıyor")
        OrderRules.validate(side, OrderType.MARKET, filled, price, info)?.let { return FillResult.Rejected(it) }
        val note = if (filled < quantity) "Kısmi gerçekleşme: mum hacminin %%%.0f sınırı".format(participation * 100) else ""
        return FillResult.Filled(Fill(filled, price, price * filled * costs.takerFee, false, quantity, note))
    }

    /** Limit emir: alışta mumun en düşüğü limitin altına, satışta en yükseği üstüne inmeli/çıkmalı. */
    fun limitFromBar(side: OrderSide, quantity: Double, limitPrice: Double, low: Double, high: Double, quoteVolume: Double, participation: Double, costs: CostModel, info: SymbolInfo?): FillResult {
        val tradedThrough = if (side == OrderSide.BUY) low < limitPrice else high > limitPrice
        if (!tradedThrough) return FillResult.Rejected("Fiyat limit seviyesinin ötesinde işlem görmedi")
        val cap = if (participation > 0) participation * quoteVolume / limitPrice else Double.MAX_VALUE
        val filled = OrderRules.roundQuantity(minOf(quantity, cap), info)
        if (filled <= 0) return FillResult.Rejected("Mum hacmi emri karşılamıyor")
        OrderRules.validate(side, OrderType.LIMIT, filled, limitPrice, info)?.let { return FillResult.Rejected(it) }
        val note = if (filled < quantity) "Kısmi gerçekleşme: mum hacminin %%%.0f sınırı".format(participation * 100) else ""
        return FillResult.Filled(Fill(filled, limitPrice, limitPrice * filled * costs.makerFee, true, quantity, note))
    }

    /**
     * Pozisyonun satılamayan son kalanı (toz): aynı fiyattan ve ücretle kapanmış sayılır. Gerçek hesapta bu miktar
     * cüzdanda kalır; değeri ihmal edilebilir ama pozisyon ve işlem sınırı boşuna meşgul edilmez.
     */
    fun dustExit(quantity: Double, price: Double, maker: Boolean, costs: CostModel): Fill =
        Fill(quantity, price, price * quantity * (if (maker) costs.makerFee else costs.takerFee), maker, quantity, "Satılamayan küçük kalan (toz) aynı fiyattan değerlendirildi")

    /** Tam kapanış emrinden sonra kalan miktar toz olacaksa onu da bu dolumun içine katar. */
    fun absorbDust(fill: Fill, positionQuantity: Double, info: SymbolInfo?): Fill {
        if (fill.requestedQuantity < positionQuantity * (1 - 1e-9) || fill.quantity >= positionQuantity) return fill
        val rest = positionQuantity - fill.quantity
        if (!OrderRules.isDust(rest, fill.averagePrice, info)) return fill
        return fill.copy(
            quantity = positionQuantity,
            fee = fill.fee * positionQuantity / fill.quantity,
            requestedQuantity = positionQuantity,
            note = listOf(fill.note, "kalan toz miktar aynı fiyattan eklendi").filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }

    /** Defter yoksa: en iyi fiyat + kayma (eski davranış), en iyi seviyedeki miktarla sınırlı değil. */
    fun marketFromQuote(side: OrderSide, quantity: Double, bestPrice: Double, costs: CostModel, info: SymbolInfo?): FillResult {
        val price = if (side == OrderSide.BUY) bestPrice * (1 + costs.slippage) else bestPrice * (1 - costs.slippage)
        val filled = OrderRules.roundQuantity(quantity, info)
        OrderRules.validate(side, OrderType.MARKET, filled, price, info)?.let { return FillResult.Rejected(it) }
        return FillResult.Filled(Fill(filled, price, price * filled * costs.takerFee, false, quantity, "Derinlik alınamadı; en iyi fiyat + kayma"))
    }
}

/** Kalıcı bekleyen limit giriş emri (uygulama yeniden başlasa da korunur; nakit ayrılmış sayılır). */
@Serializable
data class PendingOrder(
    val setup: com.spotbot.core.strategy.TradeSetup,
    val limitPrice: Double,
    val quantity: Double,
    val riskAmount: Double,
    val qualityScore: Double,
    val rationale: TradeRationale,
    val report: com.spotbot.core.rules.RuleReport?,
    val placedAt: Long,
    val expiresAt: Long,
) {
    val symbol: String get() = setup.symbol
    /** Emir dolarsa ödenecek tutar (nakitten ayrılır). */
    val reservedQuote: Double get() = limitPrice * quantity

    /** Karar bağlamında açık pozisyon gibi sayılması için (Z11, Z12, Z14, Z18). */
    fun asPosition(): Position = Position(
        id = "pending-$symbol", symbol = symbol, strategy = setup.strategy, openedAt = placedAt, entryPrice = limitPrice,
        quantity = quantity, entryFee = 0.0, stopPrice = setup.stopPrice, targetPrice = setup.targetPrice,
        invalidationPrice = setup.invalidationPrice, riskAmount = riskAmount, qualityScore = qualityScore, setup = setup, rationale = rationale,
    )
}

/** Botun çalışmadığı süre (uygulama/telefon kapalı ya da süreç sonlandırılmış). */
@Serializable
data class Downtime(val from: Long, val to: Long, val note: String)
