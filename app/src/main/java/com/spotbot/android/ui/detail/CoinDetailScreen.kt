package com.spotbot.android.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.CandleChart
import com.spotbot.android.ui.common.ChartLine
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.PriceMarker
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.scan.StrategyCheckView
import com.spotbot.android.ui.scan.analysisLabel
import com.spotbot.android.ui.scan.eligibilityLabel
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.analysis.AnalysisConfig
import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.Series
import com.spotbot.core.analysis.TimeframeAnalysis
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.Timeframe
import com.spotbot.core.risk.CostModel
import com.spotbot.core.risk.netRiskReward
import com.spotbot.core.scanner.CoinScan
import com.spotbot.core.strategy.BreakoutRetestStrategy
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.StrategyParams
import com.spotbot.core.strategy.StrategyResult
import com.spotbot.core.strategy.TradeSetup
import com.spotbot.core.strategy.TrendPullbackStrategy
import com.spotbot.core.strategy.Triggers
import com.spotbot.core.trading.CoinDecision
import com.spotbot.core.trading.Position
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bir stratejinin şu anki (kapanmış mumlara göre) planı; ekran içindir, işlem kararını motor verir. */
data class PlanView(
    val strategy: StrategyId,
    /** 4H rejimi stratejiye uygun mu. */
    val regimeFits: Boolean,
    val setup: TradeSetup?,
    val reasons: List<String>,
    val netRiskReward: Double?,
)

data class DetailState(
    val symbol: String,
    val timeframe: Timeframe = Timeframe.H1,
    /** Grafikte seçili zaman dilimi. */
    val analysis: TimeframeAnalysis? = null,
    /** 4H / 1H / 15M birlikte (kapanmış mumlar). */
    val mtf: MultiTimeframe? = null,
    val plans: List<PlanView> = emptyList(),
    val scan: CoinScan? = null,
    /** Son taramadaki karar ve zamanı. */
    val decision: CoinDecision? = null,
    val decisionTime: Long? = null,
    val position: Position? = null,
    val minRiskReward: Double = 1.5,
    val strategyParams: StrategyParams = StrategyParams(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Coin ayrıntısı: motorun belleğindeki mumlar, yoksa REST'ten kapanmış mumlar alınır. */
class CoinDetailViewModel(private val engine: TradingEngine, private val api: MarketDataApi, handle: SavedStateHandle) : ViewModel() {
    private val symbol: String = handle["symbol"] ?: "BTCUSDT"
    private val _state = MutableStateFlow(DetailState(symbol))
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        loadOverview()
        select(Timeframe.H1)
    }

    private suspend fun series(timeframe: Timeframe): Series? = engine.series(symbol, timeframe) ?: runCatching {
        val now = api.serverTime()
        Series(symbol, timeframe, api.klines(symbol, timeframe, limit = 300).filter { it.isClosedAt(now) }, AnalysisConfig())
    }.getOrNull()?.takeIf { it.size > 0 }

    private fun loadOverview() {
        viewModelScope.launch {
            val config = engine.config
            val mtf = engine.analysis(symbol) ?: run {
                val h4 = series(Timeframe.H4)
                val h1 = series(Timeframe.H1)
                val m15 = series(Timeframe.M15)
                if (h4 != null && h1 != null && m15 != null) MultiTimeframe(symbol, h4.analyze(), h1.analyze(), m15.analyze()) else null
            }
            val log = engine.scanLogs.value.lastOrNull { l -> l.coins.any { it.symbol == symbol } }
            _state.value = _state.value.copy(
                mtf = mtf,
                plans = mtf?.takeIf { it.isValid }?.let { plans(it, config.strategyParams, CostModel(slippage = config.slippage)) }.orEmpty(),
                scan = engine.lastScan.value?.coins?.firstOrNull { it.symbol == symbol },
                decision = log?.coins?.firstOrNull { it.symbol == symbol },
                decisionTime = log?.boundary,
                position = engine.portfolio.value.positions.firstOrNull { it.symbol == symbol },
                minRiskReward = config.risk.minNetRiskReward,
                strategyParams = config.strategyParams,
            )
        }
    }

    fun select(timeframe: Timeframe) {
        _state.value = _state.value.copy(timeframe = timeframe, loading = true, error = null)
        viewModelScope.launch {
            val s = series(timeframe)
            _state.value = if (s == null) _state.value.copy(loading = false, error = "Veri alınamadı")
            else _state.value.copy(analysis = s.analyze(), loading = false)
        }
    }

    companion object {
        /** A ve B'nin şu anki planı (motorla aynı strateji kodu; kurallar ve risk kontrolü motorda yapılır). */
        fun plans(mtf: MultiTimeframe, params: StrategyParams, costs: CostModel): List<PlanView> =
            listOf(BreakoutRetestStrategy(params), TrendPullbackStrategy(params)).map { strategy ->
                when (val result = strategy.evaluate(mtf)) {
                    is StrategyResult.Found -> PlanView(
                        strategy.id, mtf.h4.regime in strategy.suitableRegimes, result.setup, emptyList(),
                        netRiskReward(result.setup.entryPrice, result.setup.stopPrice, result.setup.targetPrice, costs).ratio,
                    )
                    is StrategyResult.NotFound -> PlanView(strategy.id, mtf.h4.regime in strategy.suitableRegimes, null, result.reasons, null)
                }
            }
    }
}

@Composable
fun CoinDetailScreen(modifier: Modifier = Modifier, viewModel: CoinDetailViewModel = viewModel(factory = AppViewModels.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CoinDetailContent(state, viewModel::select, modifier)
}

@Composable
fun CoinDetailContent(state: DetailState, onSelect: (Timeframe) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.cards),
    ) {
        HeaderCard(state)
        DecisionCard(state)
        state.mtf?.takeIf { it.isValid }?.let { mtf ->
            TrendCard(mtf)
            PlanCard(state)
            TriggerCard(mtf, state.strategyParams)
        }
        ChartCard(state, onSelect)
        state.analysis?.let { a ->
            ExpandableCard("Göstergeler", subtitle = "${a.series.timeframe.label} · son kapanmış mum") { Indicators(a) }
            ExpandableCard("Seviyeler", subtitle = "Destek, direnç, FVG, order block, likidite") { Levels(a) }
        }
        state.decision?.takeIf { it.strategies.isNotEmpty() }?.let { d ->
            ExpandableCard("Kurallar (son tarama)", subtitle = "Strateji ve kural sonuçları tek tek") { d.strategies.forEach { StrategyCheckView(it) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderCard(state: DetailState) {
    val price = state.scan?.price ?: state.mtf?.m15?.indicators?.close
    AppCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(price?.let { formatPrice(it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
            Text(" USDT", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        state.scan?.let { s ->
            Text("24s ${formatPercent(s.change24hPercent, signed = true, minFractionDigits = 2)}", style = MaterialTheme.typography.bodyMedium, color = gainLossColor(s.change24hPercent.compareTo(0.0)))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.scan?.let { s -> analysisLabel(s.status).let { (t, c) -> StatusPill("Analiz: $t", c) } }
            eligibilityLabel(state.decision?.outcome).let { (t, c) -> StatusPill("İşlem: $t", c) }
        }
    }
}

/** Son taramadaki karar ve işlem açılmama gerekçesi. */
@Composable
private fun DecisionCard(state: DetailState) {
    val d = state.decision
    SectionCard(if (d == null) "Karar" else "Karar · ${state.decisionTime?.let { formatTime(it) } ?: ""}") {
        if (d == null) {
            EmptyText("Bu coin için henüz tarama kararı yok (bot çalışırken her 15 dakikada güncellenir).")
            return@SectionCard
        }
        Text(d.summary, style = MaterialTheme.typography.bodyMedium)
        val failed = d.strategies.flatMap { s -> s.rules.filter { it.mandatory && !it.passed }.map { "${s.strategy.code}: ${it.title} — ${it.detail}" } }
        val reasons = (d.reasons + failed).distinct()
        if (reasons.isNotEmpty()) {
            Text("Neden işlem açılmadı", style = MaterialTheme.typography.titleSmall)
            reasons.take(6).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        state.position?.let { p ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            KeyValueRow("Açık pozisyon", "${p.strategy.code} · giriş ${formatPrice(p.entryPrice)}")
        }
    }
}

private fun regimeText(r: Regime) = when (r) {
    Regime.TREND_UP -> "Yükseliş trendi"
    Regime.TREND_DOWN -> "Düşüş trendi"
    Regime.RANGE -> "Yatay"
    Regime.UNCLEAR -> "Belirsiz"
}

@Composable
private fun TrendCard(mtf: MultiTimeframe) {
    val h4 = mtf.h4
    val i = h4.indicators
    val up = h4.regime == Regime.TREND_UP
    val down = h4.regime == Regime.TREND_DOWN
    SectionCard("4H trend", trailing = {
        StatusPill(regimeText(h4.regime), if (up) SpotBotTheme.tradingColors.gain else if (down) SpotBotTheme.tradingColors.loss else MaterialTheme.colorScheme.onSurfaceVariant)
    }) {
        KeyValueRow("Kapanış", formatPrice(i.close))
        KeyValueRow("EMA21 / EMA55", "${formatPrice(i.emaMid)} / ${formatPrice(i.emaSlow)}")
        KeyValueRow("ADX (trend gücü)", "%.1f".format(i.adx))
        KeyValueRow("SuperTrend", if (i.superTrendUp) "Yukarı" else "Aşağı")
        KeyValueRow("Yapı", "${h4.structure.trend} (${h4.structure.lastHighLabel ?: "-"} / ${h4.structure.lastLowLabel ?: "-"})")
    }
}

@Composable
private fun PlanCard(state: DetailState) {
    val h1 = state.mtf?.h1 ?: return
    SectionCard("1H işlem planı") {
        KeyValueRow("1H yapı", "${h1.structure.trend} · ${regimeText(h1.regime)}")
        h1.resistances.firstOrNull()?.let { KeyValueRow("En yakın direnç", formatPrice(it.price)) }
        h1.supports.firstOrNull()?.let { KeyValueRow("En yakın destek", formatPrice(it.price)) }
        state.plans.forEach { plan ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("${plan.strategy.code} ${plan.strategy.title}", style = MaterialTheme.typography.titleSmall)
            val setup = plan.setup
            when {
                !plan.regimeFits -> Text("4H rejimi bu stratejiye uygun değil.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                setup == null -> plan.reasons.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> {
                    KeyValueRow("Giriş / stop / hedef", "${formatPrice(setup.entryPrice)} / ${formatPrice(setup.stopPrice)} / ${formatPrice(setup.targetPrice)}")
                    plan.netRiskReward?.let { rr ->
                        KeyValueRow("Net risk/getiri", "%.2f (asgari %.1f)".format(rr, state.minRiskReward), if (rr >= state.minRiskReward) SpotBotTheme.tradingColors.gain else SpotBotTheme.tradingColors.loss)
                    }
                    KeyValueRow("Giriş koşulu", setup.entryReason)
                    KeyValueRow("İptal koşulu", setup.invalidationReason)
                }
            }
        }
        Text(
            "Plan kapanmış mumlardan hesaplanır; işlem açılıp açılmayacağına zorunlu kurallar ve risk sınırları karar verir.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TriggerCard(mtf: MultiTimeframe, params: StrategyParams) {
    val m15 = mtf.m15
    val trigger = Triggers.bullishTrigger(m15)
    val confirmations = Triggers.confirmations(m15, params)
    val i = m15.indicators
    SectionCard("15M giriş teyidi", trailing = {
        StatusPill(if (trigger != null) "Tetik var" else "Tetik yok", if (trigger != null) SpotBotTheme.tradingColors.gain else MaterialTheme.colorScheme.onSurfaceVariant)
    }) {
        trigger?.let { KeyValueRow("Tetik mumu", it) }
        KeyValueRow("Teyit sayısı", "${confirmations.size} (en az ${params.minConfirmations})")
        confirmations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        KeyValueRow("RSI / hacim oranı", "%.1f / %.2f×".format(i.rsi, if (i.volumeAverage > 0) i.volume / i.volumeAverage else 0.0))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(state: DetailState, onSelect: (Timeframe) -> Unit) {
    val colors = SpotBotTheme.tradingColors
    SectionCard("Grafik") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4).forEach { tf ->
                FilterChip(selected = state.timeframe == tf, onClick = { onSelect(tf) }, label = { Text(tf.label) })
            }
        }
        val a = state.analysis
        when {
            state.loading -> EmptyText("Yükleniyor…")
            state.error != null -> EmptyText("Veri alınamadı: ${state.error}")
            a == null -> EmptyText("Veri yok.")
            else -> {
                val s = a.series
                val count = minOf(90, s.size)
                CandleChart(
                    candles = s.candles.takeLast(count),
                    lines = listOf(
                        ChartLine(s.emaMid.takeLast(count), Color(0xFF3B82F6), "EMA21"),
                        ChartLine(s.emaSlow.takeLast(count), Color(0xFFF59E0B), "EMA55"),
                    ),
                    markers = buildList {
                        state.position?.let {
                            add(PriceMarker(it.entryPrice, MaterialTheme.colorScheme.primary, "Giriş"))
                            add(PriceMarker(it.stopPrice, colors.loss, "Stop"))
                            add(PriceMarker(it.targetPrice, colors.gain, "Hedef"))
                        }
                    },
                )
                Text("Mavi EMA21 · turuncu EMA55 · yalnız kapanmış mumlar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Indicators(a: TimeframeAnalysis) {
    val i = a.indicators
    KeyValueRow("Kapanış", formatPrice(i.close))
    KeyValueRow("Rejim", a.regime.name)
    KeyValueRow("Yapı", "${a.structure.trend} (${a.structure.lastHighLabel ?: "-"} / ${a.structure.lastLowLabel ?: "-"})")
    a.structure.lastBos?.let { KeyValueRow("Son yapı kırılımı (BOS)", "${if (it.bullish) "Yukarı" else "Aşağı"} @ ${formatPrice(it.level)}") }
    KeyValueRow("EMA 9 / 21", "${formatPrice(i.emaFast)} / ${formatPrice(i.emaMid)}")
    KeyValueRow("EMA 55 / 200", "${formatPrice(i.emaSlow)} / ${formatPrice(i.emaTrend)}")
    KeyValueRow("SuperTrend", if (i.superTrendUp) "Yukarı (${formatPrice(i.superTrendLine)})" else "Aşağı (${formatPrice(i.superTrendLine)})")
    KeyValueRow("ADX / +DI / −DI", "%.1f / %.1f / %.1f".format(i.adx, i.plusDi, i.minusDi))
    KeyValueRow("RSI", "%.1f".format(i.rsi))
    KeyValueRow("MACD / sinyal / histogram", "%.4g / %.4g / %.4g".format(i.macd, i.macdSignal, i.macdHistogram))
    KeyValueRow("ATR", formatPrice(i.atr))
    KeyValueRow("VWAP (günlük)", formatPrice(i.vwap))
    KeyValueRow("Hacim / ortalama", "%.2f×".format(if (i.volumeAverage > 0) i.volume / i.volumeAverage else 0.0))
}

@Composable
private fun Levels(a: TimeframeAnalysis) {
    val colors = SpotBotTheme.tradingColors
    a.resistances.take(3).forEach { KeyValueRow("Direnç (${it.touches} temas)", formatPrice(it.price), colors.loss) }
    a.supports.take(3).forEach { KeyValueRow("Destek (${it.touches} temas)", formatPrice(it.price), colors.gain) }
    a.fairValueGaps.filter { it.bullish }.takeLast(2).forEach { KeyValueRow("Yükseliş FVG", "${formatPrice(it.bottom)} – ${formatPrice(it.top)}") }
    a.orderBlocks.takeLast(2).forEach { KeyValueRow("Order block", "${formatPrice(it.bottom)} – ${formatPrice(it.top)}") }
    a.liquidityPools.forEach { KeyValueRow(if (it.buySide) "Eşit tepeler (likidite)" else "Eşit dipler (likidite)", formatPrice(it.price)) }
    if (a.levels.isEmpty()) EmptyText("Belirgin seviye yok.")
}
