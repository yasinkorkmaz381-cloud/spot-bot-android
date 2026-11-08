package com.spotbot.core.rules

import com.spotbot.core.analysis.Regime
import com.spotbot.core.risk.CostModel
import com.spotbot.core.risk.PositionSizer
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.risk.netRiskReward
import com.spotbot.core.scanner.CoinStatus
import com.spotbot.core.scanner.MarketState
import com.spotbot.core.support.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val costs = CostModel(0.0005, 0.0005, 0.00025)
    private val mtf = TestData.mtf()

    private fun context(
        setup: com.spotbot.core.strategy.TradeSetup = TestData.setup(entry = 100.0, stop = 98.0, target = 106.0),
        market: MarketState = TestData.openMarket,
        scanStatus: CoinStatus = CoinStatus.CANDIDATE,
        openSymbols: Set<String> = emptySet(),
        openCount: Int = 0,
        dailyPnl: Double = 0.0,
        correlatedRisk: Double = 0.5,
        now: Long = mtf.m15.candle.closeTime,
    ) = RuleContext(
        setup = setup.copy(signalTime = mtf.m15.candle.closeTime),
        mtf = mtf, btc = mtf, scan = TestData.scan(status = scanStatus), market = market, riskConfig = RiskConfig(),
        riskReward = netRiskReward(setup.entryPrice, setup.stopPrice, setup.targetPrice, costs),
        sizing = PositionSizer.size(1_000.0, 1_000.0, setup.entryPrice, setup.stopPrice, setup.targetPrice, TestData.symbolInfo(), costs, RiskConfig()),
        strategyEnabled = true, strategySuitableRegimes = Regime.entries.toSet(), now = now,
        openSymbols = openSymbols, openPositionCount = openCount, dailyPnlPercent = dailyPnl, correlatedRiskPercent = correlatedRisk,
    )

    private val engine = RuleEngine(RuleConfig.default())

    private fun failures(c: RuleContext) = engine.evaluate(c).mandatoryFailures.map { it.id }.toSet()

    @Test
    fun `default config loads all quality rules`() {
        val config = RuleConfig.default()
        assertEquals(11, config.builtIn.size)
        assertTrue(engine.configErrors.isEmpty())
    }

    @Test
    fun `each mandatory rule blocks its own violation`() {
        assertTrue("Z03" in failures(context(setup = TestData.setup(entry = 100.0, stop = 98.0, target = 102.0))))
        assertTrue("Z10" in failures(context(market = TestData.openMarket.copy(allowsLongs = false))))
        assertTrue("Z08" in failures(context(scanStatus = CoinStatus.ELIMINATED)))
        assertTrue("Z09" in failures(context(scanStatus = CoinStatus.FLAGGED)))
        assertTrue("Z11" in failures(context(openSymbols = setOf("ETHUSDT"))))
        assertTrue("Z12" in failures(context(openCount = 3)))
        assertTrue("Z13" in failures(context(dailyPnl = -1.5)))
        assertTrue("Z14" in failures(context(correlatedRisk = 1.2)))
        assertTrue("Z01" in failures(context(now = mtf.m15.candle.closeTime - 1)))
    }

    @Test
    fun `report is not allowed when any mandatory rule fails`() {
        val report = engine.evaluate(context(openCount = 3))
        assertFalse(report.allowed)
        assertTrue(report.qualityScore in 0.0..100.0)
    }

    @Test
    fun `quality rules can be disabled but mandatory ones cannot`() {
        val config = RuleConfig(builtIn = listOf(RuleSetting("K01", enabled = false), RuleSetting("Z03", enabled = false)))
        val report = RuleEngine(config).evaluate(context(setup = TestData.setup(entry = 100.0, stop = 98.0, target = 102.0)))
        assertTrue(report.outcomes.none { it.id == "K01" })
        assertTrue(report.mandatoryFailures.any { it.id == "Z03" })
    }

    @Test
    fun `custom expression rules are evaluated and can block`() {
        val config = RuleConfig(
            custom = listOf(
                CustomRule("R01", "4H ADX en az 5", RuleGroup.QUALITY, "h4.adx >= 5 and h1.rsi > 0", weight = 2.0),
                CustomRule("R02", "İmkânsız koşul", RuleGroup.MANDATORY, "m15.rsi > 200"),
                CustomRule("R03", "Rejim karşılaştırması", RuleGroup.QUALITY, "h4.regime != TREND_DOWN ve setup.confirmations >= 2"),
            ),
        )
        val report = RuleEngine(config).evaluate(context())
        assertTrue(report.outcomes.single { it.id == "R01" }.passed)
        assertTrue(report.outcomes.single { it.id == "R03" }.passed)
        assertEquals(listOf("R02"), report.mandatoryFailures.map { it.id })
    }

    @Test
    fun `malformed custom rule is reported and blocks safely`() {
        val config = RuleConfig(custom = listOf(CustomRule("R09", "Hatalı", RuleGroup.MANDATORY, "h4.adx yirmibeş")))
        val engine = RuleEngine(config)
        assertEquals(1, engine.configErrors.size)
        assertTrue(engine.evaluate(context()).mandatoryFailures.any { it.id == "R09" && it.detail.startsWith("Koşul hatalı") })
    }

    @Test
    fun `unknown variables never pass`() {
        val (passed, _) = RuleExpression.parse("h4.olmayan > 1").evaluate(context())
        assertFalse(passed)
    }
}
