package com.spotbot.android.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.CandleChart
import com.spotbot.android.ui.common.ChartLine
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.PriceMarker
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.core.analysis.AnalysisConfig
import com.spotbot.core.analysis.Series
import com.spotbot.core.analysis.TimeframeAnalysis
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.Timeframe
import com.spotbot.core.trading.Position
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailState(
    val symbol: String,
    val timeframe: Timeframe = Timeframe.H1,
    val analysis: TimeframeAnalysis? = null,
    val position: Position? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Coin ayrıntısı: motorun belleğindeki mumlar, yoksa REST'ten kapanmış mumlar alınır. 5M yalnızca izleme içindir. */
class CoinDetailViewModel(private val engine: TradingEngine, private val api: MarketDataApi, handle: SavedStateHandle) : ViewModel() {
    private val symbol: String = handle["symbol"] ?: "BTCUSDT"
    private val _state = MutableStateFlow(DetailState(symbol))
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init { select(Timeframe.H1) }

    fun select(timeframe: Timeframe) {
        _state.value = _state.value.copy(timeframe = timeframe, loading = true, error = null)
        viewModelScope.launch {
            val series = engine.series(symbol, timeframe) ?: runCatching {
                val now = api.serverTime()
                Series(symbol, timeframe, api.klines(symbol, timeframe, limit = 300).filter { it.isClosedAt(now) }, AnalysisConfig())
            }.getOrElse {
                _state.value = _state.value.copy(loading = false, error = it.message)
                return@launch
            }
            _state.value = _state.value.copy(
                analysis = series.takeIf { it.size > 0 }?.analyze(),
                position = engine.portfolio.value.positions.firstOrNull { it.symbol == symbol },
                loading = false,
            )
        }
    }
}

@Composable
fun CoinDetailScreen(modifier: Modifier = Modifier, viewModel: CoinDetailViewModel = viewModel(factory = AppViewModels.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.H4).forEach { tf ->
                FilterChip(selected = state.timeframe == tf, onClick = { viewModel.select(tf) }, label = { Text(tf.label) })
            }
        }
        val a = state.analysis
        when {
            state.loading -> EmptyText("Yükleniyor…")
            state.error != null -> EmptyText("Veri alınamadı: ${state.error}")
            a == null -> EmptyText("Veri yok.")
            else -> DetailContent(a, state.position)
        }
    }
}

@Composable
fun DetailContent(a: TimeframeAnalysis, position: Position?) {
    val s = a.series
    val count = minOf(90, s.size)
    val colors = SpotBotTheme.tradingColors
    val markers = buildList {
        position?.let {
            add(PriceMarker(it.entryPrice, MaterialTheme.colorScheme.primary, "Giriş"))
            add(PriceMarker(it.stopPrice, colors.loss, "Stop"))
            add(PriceMarker(it.targetPrice, colors.gain, "Hedef"))
        }
    }
    SectionCard("${s.symbol} · ${s.timeframe.label} (kapanmış mumlar)") {
        CandleChart(
            candles = s.candles.takeLast(count),
            lines = listOf(
                ChartLine(s.emaMid.takeLast(count), Color(0xFF3B82F6), "EMA21"),
                ChartLine(s.emaSlow.takeLast(count), Color(0xFFF59E0B), "EMA55"),
                ChartLine(s.superTrend.line.takeLast(count), Color(0xFF8B5CF6), "SuperTrend"),
            ),
            markers = markers,
        )
        Text("Mavi: EMA21 · Turuncu: EMA55 · Mor: SuperTrend", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val i = a.indicators
    SectionCard("Göstergeler") {
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
    SectionCard("Seviyeler") {
        a.resistances.take(3).forEach { KeyValueRow("Direnç (${it.touches} temas)", formatPrice(it.price), colors.loss) }
        a.supports.take(3).forEach { KeyValueRow("Destek (${it.touches} temas)", formatPrice(it.price), colors.gain) }
        a.fairValueGaps.filter { it.bullish }.takeLast(2).forEach { KeyValueRow("Yükseliş FVG", "${formatPrice(it.bottom)} – ${formatPrice(it.top)}") }
        a.orderBlocks.takeLast(2).forEach { KeyValueRow("Order block", "${formatPrice(it.bottom)} – ${formatPrice(it.top)}") }
        a.liquidityPools.forEach { KeyValueRow(if (it.buySide) "Eşit tepeler (likidite)" else "Eşit dipler (likidite)", formatPrice(it.price)) }
        if (a.levels.isEmpty()) EmptyText("Belirgin seviye yok.")
    }
}
