package com.spotbot.core.backtest

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Series
import com.spotbot.core.model.Timeframe
import com.spotbot.core.risk.AtrStopMode
import com.spotbot.core.risk.CostModel
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.scanner.Scanner
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.support.Fixtures
import com.spotbot.core.support.TestData
import com.spotbot.core.trading.Candidate
import com.spotbot.core.trading.CandidateDecision
import com.spotbot.core.trading.DecisionContext
import com.spotbot.core.trading.DecisionPipeline
import com.spotbot.core.trading.StrategyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestV2Test {

    private fun dataset(symbol: String) =
        HistoricalDataset(symbol, Fixtures.period(symbol, "m15"), Fixtures.period(symbol, "h1"), Fixtures.period(symbol, "h4"))

    private val data = mapOf("NEARUSDT" to dataset("NEARUSDT"), "BTCUSDT" to dataset("BTCUSDT"))
    private val info = mapOf("NEARUSDT" to TestData.symbolInfo("NEARUSDT", "NEAR"), "BTCUSDT" to TestData.symbolInfo("BTCUSDT", "BTC"))
    private val start = 1_789_689_600_000L
    private val end = data.getValue("NEARUSDT").m15.last().closeTime
    private val base = BacktestConfig(listOf("NEARUSDT"), start, end, scanner = ScannerConfig(minQuoteVolume24h = 100_000.0))

    @Test
    fun `removing data after a cutoff does not change any decision before it (no look-ahead)`() {
        val cutoff = start + 12L * 24 * 3_600_000
        val truncated = data.mapValues { (_, d) ->
            d.copy(m15 = d.m15.filter { it.closeTime <= cutoff }, h1 = d.h1.filter { it.closeTime <= cutoff }, h4 = d.h4.filter { it.closeTime <= cutoff })
        }
        val config = base.copy(endTime = cutoff)
        val withFuture = BacktestEngine(config).run(data, info)
        val withoutFuture = BacktestEngine(config).run(truncated, info)

        assertTrue("Karşılaştırma için işlem gerekli", withFuture.trades > 0)
        assertEquals(withoutFuture.closedTrades.map { it.position.openedAt to it.closedAt }, withFuture.closedTrades.map { it.position.openedAt to it.closedAt })
        assertEquals(withoutFuture.finalEquity, withFuture.finalEquity, 1e-9)
        assertEquals(withoutFuture.setupsFound, withFuture.setupsFound)
    }

    @Test
    fun `shadow analysis never influences trading decisions`() {
        val plain = BacktestEngine(base).run(data, info)
        val withShadow = BacktestEngine(base.copy(shadowAnalysis = true)).run(data, info)
        assertEquals(plain.closedTrades.map { it.netPnl }, withShadow.closedTrades.map { it.netPnl })
    }

    @Test
    fun `thin candle volume produces partial fills instead of impossible fills`() {
        val report = BacktestEngine(base.copy(participationRate = 0.00002)).run(data, info)
        val normal = BacktestEngine(base).run(data, info)
        assertTrue("Kısmi gerçekleşme bekleniyor (${report.partialFills})", report.partialFills > 0)
        assertEquals(base.initialCapital + report.closedTrades.sumOf { it.netPnl }, report.finalEquity, 1e-6)
        val avgQtyThin = report.closedTrades.map { it.position.initialQuantity }.average()
        val avgQtyNormal = normal.closedTrades.map { it.position.initialQuantity }.average()
        assertTrue(avgQtyThin < avgQtyNormal)
    }

    @Test
    fun `profiles run independently under the same conditions`() {
        val trend = BacktestEngine(base.copy(profile = StrategyProfile.TREND)).run(data, info)
        assertTrue(trend.closedTrades.all { it.position.strategy == StrategyId.TREND_PULLBACK })
        val selective = BacktestEngine(base.copy(profile = StrategyProfile.SELECTIVE)).run(data, info)
        assertTrue(selective.closedTrades.all { it.position.qualityScore >= StrategyProfile.SELECTIVE_MIN_QUALITY })

        val comparison = StrategyComparison.run(base, data, info)
        assertEquals(StrategyProfile.entries.toList(), comparison.map { it.profile })
        assertEquals(trend.trades, comparison.first { it.profile == StrategyProfile.TREND }.full.trades)
        comparison.forEach { assertTrue(it.firstHalf.trades + it.secondHalf.trades >= 0) }
    }

    /** Fikstür boyunca bir kurulum bulunan ilk anı arar ve pipeline sonucunu döndürür. */
    private fun firstSetup(pipeline: DecisionPipeline): CandidateDecision {
        val (m15, h1, h4) = listOf("m15" to Timeframe.M15, "h1" to Timeframe.H1, "h4" to Timeframe.H4).map { (k, tf) -> Series("NEARUSDT", tf, Fixtures.period("NEARUSDT", k)) }
        val btc = listOf("m15" to Timeframe.M15, "h1" to Timeframe.H1, "h4" to Timeframe.H4).map { (k, tf) -> Series("BTCUSDT", tf, Fixtures.period("BTCUSDT", k)) }
        for (t in m15.candles.map { it.closeTime }.filter { it > start }) {
            val mtf = MultiTimeframe("NEARUSDT", h4.analyze(h4.indexAtOrBefore(t)), h1.analyze(h1.indexAtOrBefore(t)), m15.analyze(m15.indexAtOrBefore(t)))
            if (!mtf.isValid) continue
            val btcMtf = MultiTimeframe("BTCUSDT", btc[2].analyze(btc[2].indexAtOrBefore(t)), btc[1].analyze(btc[1].indexAtOrBefore(t)), btc[0].analyze(btc[0].indexAtOrBefore(t)))
            val context = DecisionContext(t, 1_000.0, 1_000.0, emptyList(), 0.0, Scanner.marketState(btcMtf, emptyList(), ScannerConfig()), btcMtf, emptyMap())
            val decision = pipeline.evaluate(Candidate(mtf, TestData.scan("NEARUSDT"), info["NEARUSDT"], CostModel()), context)
            if (decision !is CandidateDecision.NoSetup) return decision
        }
        error("Fikstürde kurulum bulunamadı")
    }

    @Test
    fun `decision records every strategy and selective profile adds its gates`() {
        val decision = firstSetup(DecisionPipeline(profile = StrategyProfile.SELECTIVE))
        assertEquals(3, decision.evaluations.size)
        assertTrue(decision.evaluations.any { it.strategy == StrategyId.RANGE && !it.applicable })
        val report = (decision as? CandidateDecision.Reject)?.report ?: (decision as CandidateDecision.Enter).report
        assertTrue(report.outcomes.any { it.id == "P01" } && report.outcomes.any { it.id == "P02" })
        assertTrue(report.outcomes.any { it.id == "Z17" } && report.outcomes.any { it.id == "Z18" })
    }

    @Test
    fun `atr stop floor only widens tight stops and is off by default`() {
        val plain = firstSetup(DecisionPipeline())
        val plainSetup = plain.evaluations.first { it.setup != null }.setup!!
        val floored = firstSetup(DecisionPipeline(riskConfig = RiskConfig(atrStopMode = AtrStopMode.FLOOR, atrStopMultiple = 5.0)))
        val flooredSetup = floored.evaluations.first { it.setup != null }.setup!!
        assertEquals(plainSetup.entryPrice, flooredSetup.entryPrice, 0.0)
        assertTrue(flooredSetup.stopPrice <= plainSetup.stopPrice)
        assertTrue(flooredSetup.stopReason.contains("ATR"))
    }

    @Test
    fun `total open risk cap blocks new trades once the cap is reached`() {
        val engine = com.spotbot.core.rules.RuleEngine()
        val mtf = TestData.mtf()
        val setup = TestData.setup(entry = 100.0, stop = 98.0, target = 106.0).copy(signalTime = mtf.m15.candle.closeTime)
        val costs = CostModel()
        fun context(openRisk: Double) = com.spotbot.core.rules.RuleContext(
            setup, mtf, mtf, TestData.scan(), TestData.openMarket, RiskConfig(),
            com.spotbot.core.risk.netRiskReward(100.0, 98.0, 106.0, costs),
            com.spotbot.core.risk.PositionSizer.size(1_000.0, 1_000.0, 100.0, 98.0, 106.0, TestData.symbolInfo(), costs, RiskConfig()),
            true, com.spotbot.core.analysis.Regime.entries.toSet(), mtf.m15.candle.closeTime, emptySet(), 2, 0.0, 0.5,
            openRiskPercent = openRisk, newRiskPercent = 0.5,
        )
        assertTrue(engine.evaluate(context(1.0)).outcomes.single { it.id == "Z18" }.passed)
        assertTrue(!engine.evaluate(context(1.2)).outcomes.single { it.id == "Z18" }.passed)
    }
}
