package com.spotbot.android.data

import com.spotbot.android.assertDecimalEquals
import com.spotbot.android.data.bot.PaperTradingBotRepository
import com.spotbot.android.data.bot.StrategyUpdateResult
import com.spotbot.android.domain.model.MarketTicker
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.StrategyError
import com.spotbot.android.domain.model.TradeReason
import com.spotbot.android.domain.model.TradingPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PaperTradingBotRepositoryTest {

    private val btc = TradingPair("BTC", "USDT")
    private val eth = TradingPair("ETH", "USDT")
    private val market = FakeMarketRepository(
        listOf(
            MarketTicker(btc, BigDecimal("50000"), BigDecimal("50000")),
            MarketTicker(eth, BigDecimal("3000"), BigDecimal("3000")),
        ),
    )
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun TestScope.repository(scope: CoroutineScope = backgroundScope) =
        PaperTradingBotRepository(market, scope, clock, initialBalance = BigDecimal("1000"))

    @Test
    fun `start enters immediately and stop halts trading`() = runTest {
        val repo = repository()

        repo.start()
        runCurrent()
        assertTrue(repo.state.value.isRunning)
        assertEquals(TradeReason.ENTRY, repo.state.value.trades.single().reason)

        market.setPrice(btc, "60000")
        runCurrent()
        assertEquals(TradeReason.TAKE_PROFIT, repo.state.value.trades.first().reason)

        repo.stop()
        market.setPrice(btc, "61000")
        runCurrent()
        assertFalse(repo.state.value.isRunning)
        assertEquals(2, repo.state.value.trades.size)
    }

    @Test
    fun `strategy cannot change while the bot is running`() = runTest {
        val repo = repository()
        repo.start()
        runCurrent()

        val result = repo.updateStrategy(StrategyConfig.DEFAULT.copy(orderAmount = BigDecimal("20")))

        assertEquals(StrategyUpdateResult.BotRunning, result)
        repo.stop()
    }

    @Test
    fun `pair cannot change while a position is open on another pair`() = runTest {
        val repo = repository()
        repo.start()
        runCurrent()
        repo.stop()
        assertNotNull(repo.state.value.account.position)

        assertEquals(
            StrategyUpdateResult.PositionOpenOnOtherPair,
            repo.updateStrategy(StrategyConfig.DEFAULT.copy(pair = eth)),
        )
        assertEquals(
            StrategyUpdateResult.Saved,
            repo.updateStrategy(StrategyConfig.DEFAULT.copy(orderAmount = BigDecimal("20"))),
        )
    }

    @Test
    fun `invalid strategy is rejected`() = runTest {
        val result = repository().updateStrategy(StrategyConfig.DEFAULT.copy(orderAmount = BigDecimal.ONE))
        assertEquals(StrategyUpdateResult.Invalid(listOf(StrategyError.ORDER_AMOUNT_TOO_LOW)), result)
    }

    @Test
    fun `reset restores the balance and keeps the strategy`() = runTest {
        val repo = repository()
        val config = StrategyConfig.DEFAULT.copy(pair = eth, orderAmount = BigDecimal("25"))
        assertEquals(StrategyUpdateResult.Saved, repo.updateStrategy(config))
        repo.start()
        runCurrent()

        repo.resetSimulation()

        val state = repo.state.value
        assertFalse(state.isRunning)
        assertNull(state.account.position)
        assertTrue(state.trades.isEmpty())
        assertDecimalEquals("1000", state.account.quoteBalance)
        assertEquals(config, state.config)
    }
}
