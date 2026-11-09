package com.spotbot.core.trading

import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.risk.CostModel
import com.spotbot.core.support.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdersTest {

    private val info = TestData.symbolInfo().copy(
        minNotional = 1.0, minMarketNotional = 5.0, maxQuoteAmount = 10_000.0, maxMarketQuoteAmount = 2_000.0,
        buyPriceBandUp = 0.02, sellPriceBandDown = 0.02, quantityPrecision = 3, pricePrecision = 2,
    )
    private val costs = CostModel(takerFee = 0.001, slippage = 0.001, halfSpread = 0.0005, makerFee = 0.0)

    @Test
    fun `order rules enforce exchange limits`() {
        assertNull(OrderRules.validate(OrderSide.BUY, OrderType.LIMIT, 0.5, 100.0, info))
        assertTrue(OrderRules.validate(OrderSide.BUY, OrderType.MARKET, 0.04, 100.0, info)!!.contains("asgari emir"))
        assertNull(OrderRules.validate(OrderSide.BUY, OrderType.LIMIT, 0.04, 100.0, info))
        assertTrue(OrderRules.validate(OrderSide.BUY, OrderType.MARKET, 30.0, 100.0, info)!!.contains("en büyük"))
        assertTrue(OrderRules.validate(OrderSide.BUY, OrderType.LIMIT, 0.00005, 100.0, info)!!.contains("asgari miktar"))
        assertTrue(OrderRules.validate(OrderSide.BUY, OrderType.LIMIT, 1.0, 110.0, info, marketPrice = 100.0)!!.contains("fiyat bandı"))
        assertTrue(OrderRules.validate(OrderSide.BUY, OrderType.MARKET, 1.0, 100.0, info.copy(orderTypes = listOf("LIMIT")))!!.contains("desteklenmiyor"))
        assertTrue(OrderRules.validate(OrderSide.SELL, OrderType.LIMIT, 1.0, 100.0, info.copy(tradeSideType = 2))!!.contains("yönde"))
    }

    @Test
    fun `prices and quantities round to exchange steps in the safe direction`() {
        assertEquals(100.12, OrderRules.roundPrice(100.129, OrderSide.BUY, info), 0.0)
        assertEquals(100.13, OrderRules.roundPrice(100.121, OrderSide.SELL, info), 0.0)
        assertEquals(1.234, OrderRules.roundQuantity(1.23499, info), 0.0)
    }

    @Test
    fun `market order walks the book and fills partially beyond the slippage band`() {
        val book = OrderBook("ETHUSDT", listOf(PriceLevel(99.9, 10.0)), listOf(PriceLevel(100.0, 1.0), PriceLevel(100.2, 1.0), PriceLevel(101.0, 50.0)))
        val full = (FillModel.walkBook(OrderSide.BUY, 1.5, book, 0.005, costs, info) as FillResult.Filled).fill
        assertEquals(1.5, full.quantity, 1e-12)
        assertEquals((100.0 + 0.5 * 100.2) / 1.5, full.averagePrice, 1e-9)
        assertEquals(full.averagePrice * 1.5 * 0.001, full.fee, 1e-12)
        val partial = (FillModel.walkBook(OrderSide.BUY, 5.0, book, 0.005, costs, info) as FillResult.Filled).fill
        assertTrue(partial.partial)
        assertEquals(2.0, partial.quantity, 1e-12)
        assertTrue(FillModel.walkBook(OrderSide.BUY, 1.0, OrderBook("X", emptyList(), emptyList()), 0.005, costs, info) is FillResult.Rejected)
    }

    @Test
    fun `bar fills respect volume participation and limit trade-through`() {
        val market = (FillModel.marketFromBar(OrderSide.BUY, 10.0, 100.0, quoteVolume = 1_000.0, participation = 0.1, costs = costs, info = info) as FillResult.Filled).fill
        assertTrue(market.partial)
        assertEquals(OrderRules.roundQuantity(100.0 / market.averagePrice, info), market.quantity, 1e-12)
        assertEquals(costs.buyPrice(100.0), market.averagePrice, 1e-12)

        assertTrue(FillModel.limitFromBar(OrderSide.BUY, 1.0, 100.0, low = 100.0, high = 101.0, quoteVolume = 1e6, participation = 0.1, costs = costs, info = info) is FillResult.Rejected)
        val limit = (FillModel.limitFromBar(OrderSide.BUY, 1.0, 100.0, low = 99.99, high = 101.0, quoteVolume = 1e6, participation = 0.1, costs = costs, info = info) as FillResult.Filled).fill
        assertEquals(100.0, limit.averagePrice, 0.0)
        assertEquals(0.0, limit.fee, 0.0)
        assertTrue(limit.maker)
    }

    @Test
    fun `account never fills an order it cannot pay for and reserves cash for pending orders`() {
        val rationale = TradeRationale("", "", "", "", "", "")
        val setup = TestData.setup(entry = 100.0, stop = 98.0, target = 106.0)
        val pending = PendingOrder(setup, 100.0, 8.0, 10.0, 50.0, rationale, null, 0, 1_000)
        val state = PortfolioState.create(1_000.0).copy(pendingOrders = listOf(pending))
        assertEquals(200.0, state.availableCash, 1e-9)

        val tooBig = PaperAccount.openWithFill(state, setup, Fill(3.0, 100.0, 0.3, false, 3.0), 6.0, 50.0, rationale, null, 1)
        assertTrue(tooBig.positions.isEmpty())
        assertEquals(DecisionOutcome.REJECTED, tooBig.decisions.last().outcome)

        val fromPending = PaperAccount.openWithFill(state, setup, Fill(8.0, 100.0, 0.0, true, 8.0), 10.0, 50.0, rationale, null, 1, fromPending = pending)
        assertEquals(1, fromPending.positions.size)
        assertTrue(fromPending.pendingOrders.isEmpty())
        assertEquals(200.0, fromPending.cash, 1e-9)
    }

    @Test
    fun `partial exit keeps closing the remainder on the next bar even if price recovers`() {
        val rationale = TradeRationale("", "", "", "", "", "")
        var state = PaperAccount.openWithFill(
            PortfolioState.create(10_000.0), TestData.setup(entry = 100.0, stop = 98.0, target = 106.0), Fill(10.0, 100.0, 1.0, false, 10.0), 21.0, 50.0, rationale, null, 0,
        )
        val id = state.positions.single().id
        state = PaperAccount.exitWithFill(state, id, Fill(4.0, 97.9, 0.39, false, 10.0, "kısmi"), ExitReason.STOP_LOSS, "stop", 1)
        val rest = state.positions.single()
        assertEquals(6.0, rest.quantity, 1e-12)
        assertEquals(ExitReason.STOP_LOSS, rest.closingReason)
        assertTrue(!rest.partialTaken)

        val recovered = com.spotbot.core.model.Candle(2, 101.0, 102.0, 100.5, 101.5, 1.0, 3, 1e6)
        val actions = ExitManager.onBar(rest, recovered, ExecutionConfig())
        assertEquals(ExitReason.STOP_LOSS, (actions.single() as ExitAction.Close).reason)
        state = PaperAccount.exitWithFill(state, id, Fill(6.0, 101.0, 0.606, false, 6.0), ExitReason.STOP_LOSS, "kalan", 3)
        val trade = state.closedTrades.single()
        val expected = (4 * 97.9 - 0.39) + (6 * 101.0 - 0.606) - (1000.0 + 1.0)
        assertEquals(expected, trade.netPnl, 1e-9)
        assertEquals(10_000.0 + expected, state.cash, 1e-9)
    }

    @Test
    fun `data freshness requires the expected closes and a fresh price`() {
        val boundary = 4 * 3_600_000L * 1000 // 4H ile hizalı
        val fresh = DataStamp(boundary, boundary, boundary, boundary + 5_000)
        assertNull(DataFreshness.issue(fresh, boundary, boundary + 10_000, 30))
        assertTrue(DataFreshness.issue(fresh.copy(m15Close = boundary - 900_000), boundary, boundary + 10_000, 30)!!.contains("15M"))
        assertTrue(DataFreshness.issue(fresh, boundary, boundary + 60_000, 30)!!.contains("sn eski"))
        assertTrue(DataFreshness.issue(fresh.copy(priceTime = null), boundary, boundary, 30)!!.contains("fiyat yok"))
    }

    @Test
    fun `uptime subtracts downtimes inside the window`() {
        val hour = 3_600_000L
        assertEquals(75.0, Reliability.uptimePercent(listOf(Downtime(hour, 2 * hour, "x")), 0, 4 * hour)!!, 1e-9)
        assertEquals(100.0, Reliability.uptimePercent(listOf(Downtime(5 * hour, 6 * hour, "x")), 0, 4 * hour)!!, 1e-9)
    }

    @Test
    fun `risk status explains why entries are blocked`() {
        val risk = com.spotbot.core.risk.RiskConfig()
        val state = PortfolioState.create(1_000.0).copy(dayStartEquity = 1_000.0)
        assertNull(RiskStatus.of(state, 1_000.0, risk, StrategyProfile.ADAPTIVE, false, true, emptyList()).entriesBlockedReason)
        assertTrue(RiskStatus.of(state, 984.0, risk, StrategyProfile.ADAPTIVE, false, true, emptyList()).entriesBlockedReason!!.contains("Günlük"))
        assertTrue(RiskStatus.of(state, 1_000.0, risk, StrategyProfile.ADAPTIVE, true, true, emptyList()).entriesBlockedReason!!.contains("duraklatıldı"))
        assertTrue(RiskStatus.of(state, 1_000.0, risk, StrategyProfile.ADAPTIVE, false, false, emptyList()).entriesBlockedReason!!.contains("BTC"))
    }
}
