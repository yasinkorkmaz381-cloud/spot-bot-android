package com.spotbot.core.support

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.Series
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Timeframe
import com.spotbot.core.scanner.CoinScan
import com.spotbot.core.scanner.CoinStatus
import com.spotbot.core.scanner.MarketState
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.TradeSetup

object TestData {
    fun symbolInfo(symbol: String = "ETHUSDT", base: String = symbol.removeSuffix("USDT")) = SymbolInfo(
        symbol = symbol, baseAsset = base, quoteAsset = "USDT", pricePrecision = 2, quantityPrecision = 4,
        minQuantity = 0.0001, minNotional = 1.0, makerFee = 0.0, takerFee = 0.0005, isOnline = true,
        isSpotTradingAllowed = true, orderTypes = listOf("LIMIT", "MARKET", "LIMIT_MAKER"), riskFlagged = false, tradeSideType = 1,
    )

    fun setup(symbol: String = "ETHUSDT", entry: Double = 100.0, stop: Double = 98.0, target: Double = 105.0, signalTime: Long = 0) = TradeSetup(
        symbol = symbol, strategy = StrategyId.TREND_PULLBACK, signalTime = signalTime, entryPrice = entry, stopPrice = stop,
        targetPrice = target, invalidationPrice = stop + 0.5, confirmations = listOf("Hacim", "RSI"), confluences = listOf("EMA", "FVG"),
        entryReason = "test", stopReason = "test", targetReason = "test", invalidationReason = "test",
    )

    /** Gerçek BTC verisinden üretilmiş geçerli bir çoklu zaman dilimi görünümü (4H/1H/15M aynı veriyi kullanır). */
    fun mtf(symbol: String = "ETHUSDT"): MultiTimeframe {
        val candles = Fixtures.btc1h
        val h1 = Series(symbol, Timeframe.H1, candles)
        return MultiTimeframe(symbol, h1.analyze(), h1.analyze(), h1.analyze())
    }

    fun scan(symbol: String = "ETHUSDT", status: CoinStatus = CoinStatus.CANDIDATE) = CoinScan(
        symbol, 100.0, 1.0, 5_000_000.0, 0.02, 50_000.0, status, emptyList(), 5, Regime.TREND_UP, null, 2.0,
    )

    val openMarket = MarketState(Regime.TREND_UP, 0.5, allowsLongs = true, reasons = listOf("BTC 4H rejimi: TREND_UP"), breadthPercent = 60.0)
}
