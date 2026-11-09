package com.spotbot.android.ui.trades

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.CandleChart
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.PriceMarker
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.TimeMarker
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatRatio
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.panel.DecisionItem
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe
import com.spotbot.core.rules.RuleGroup
import com.spotbot.core.rules.RuleReport
import com.spotbot.core.trading.ClosedTrade
import com.spotbot.core.trading.CoinDecision
import com.spotbot.core.trading.CoinOutcome
import com.spotbot.core.trading.DataStamp
import com.spotbot.core.trading.Performance
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.Position
import com.spotbot.core.trading.ScanLog
import com.spotbot.core.trading.TradeRationale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TradesViewModel(engine: TradingEngine, private val api: MarketDataApi) : ViewModel() {
    val portfolio = engine.portfolio
    val scanLogs = engine.scanLogs

    private val _charts = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())
    /** İşlem kimliği → işlem çevresindeki kapanmış 15M mumlar. */
    val charts: StateFlow<Map<String, List<Candle>>> = _charts.asStateFlow()

    fun loadChart(position: Position, closedAt: Long?) {
        if (_charts.value.containsKey(position.id)) return
        viewModelScope.launch {
            val now = runCatching { api.serverTime() }.getOrDefault(System.currentTimeMillis())
            val start = position.openedAt - 8 * Timeframe.H1.millis
            val end = minOf(now, (closedAt ?: now) + 2 * Timeframe.H1.millis)
            val candles = runCatching { api.klines(position.symbol, Timeframe.M15, limit = 500, startTime = start, endTime = end) }.getOrDefault(emptyList())
                .filter { it.isClosedAt(now) }
            _charts.update { it + (position.id to candles) }
        }
    }
}

private enum class Tab(val title: String) { OPEN("Açık"), CLOSED("Kapanmış"), DECISIONS("Karar günlüğü"), EVENTS("Olaylar") }

@Composable
fun TradesScreen(modifier: Modifier = Modifier, viewModel: TradesViewModel = viewModel(factory = AppViewModels.Factory)) {
    val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
    val logs by viewModel.scanLogs.collectAsStateWithLifecycle()
    val charts by viewModel.charts.collectAsStateWithLifecycle()
    TradesContent(portfolio, modifier, scanLogs = logs, charts = charts, onLoadChart = viewModel::loadChart)
}

@Composable
fun TradesContent(
    portfolio: PortfolioState,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    expanded: Boolean = false,
    scanLogs: List<ScanLog> = emptyList(),
    charts: Map<String, List<Candle>> = emptyMap(),
    onLoadChart: (Position, Long?) -> Unit = { _, _ -> },
) {
    var tab by rememberSaveable { mutableStateOf(Tab.entries[initialTab]) }
    var coinFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val summary = Performance.summarize(portfolio, System.currentTimeMillis())

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Özet") {
                KeyValueRow("Kapanmış işlem", "${summary.allTime.trades}")
                KeyValueRow("Net K/Z", "${formatSignedMoney(summary.allTime.netPnl)} USDT", gainLossColor(summary.allTime.netPnl.compareTo(0.0)))
                KeyValueRow("Kazanma oranı", formatPercent(summary.winRatePercent))
                KeyValueRow("Profit factor", formatRatio(summary.profitFactor))
                KeyValueRow("Ortalama R", "%+.2f".format(summary.averageR))
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.title) }) }
            }
        }
        when (tab) {
            Tab.OPEN -> {
                if (portfolio.positions.isEmpty()) item { EmptyText("Açık işlem yok.") }
                items(portfolio.positions, key = { it.id }) { p -> OpenCard(p, expanded, charts[p.id]) { onLoadChart(p, null) } }
            }
            Tab.CLOSED -> {
                if (portfolio.closedTrades.isEmpty()) item { EmptyText("Henüz kapanmış işlem yok.") }
                items(portfolio.closedTrades.reversed(), key = { it.position.id }) { t -> ClosedCard(t, expanded, charts[t.position.id]) { onLoadChart(t.position, t.closedAt) } }
            }
            Tab.DECISIONS -> {
                val symbols = scanLogs.lastOrNull()?.coins?.map { it.symbol }.orEmpty()
                item {
                    Text(
                        "Her 15M taramasında her coin için karar, gerekçe, kuralların sonucu ve kullanılan verinin zamanı. Ayrıntılı strateji ve kural incelemesi tasarım notu gereği yalnız en iyi 2–4 adaya yapılır.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (symbols.isNotEmpty()) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = coinFilter == null, onClick = { coinFilter = null }, label = { Text("Tümü") })
                            symbols.forEach { s -> FilterChip(selected = coinFilter == s, onClick = { coinFilter = s }, label = { Text(s.removeSuffix("USDT")) }) }
                        }
                    }
                }
                if (scanLogs.isEmpty()) item { EmptyText("Henüz tarama kaydı yok.") }
                items(scanLogs.reversed(), key = { it.time }) { log -> ScanLogCard(log, coinFilter, expanded) }
            }
            Tab.EVENTS -> items(portfolio.decisions.reversed()) { DecisionItem(it) }
        }
    }
}

@Composable
private fun ScanLogCard(log: ScanLog, coinFilter: String?, expanded: Boolean) {
    val coins = log.coins.filter { coinFilter == null || it.symbol == coinFilter }
    if (coins.isEmpty()) return
    SectionCard(
        "Tarama ${formatDateTime(log.boundary)}",
        trailing = { Tag(if (log.marketAllowsLongs) "PİYASA UYGUN" else "PİYASA FİLTRESİ", if (log.marketAllowsLongs) SpotBotTheme.tradingColors.gain else SpotBotTheme.tradingColors.loss) },
    ) {
        Text(
            "Profil: ${log.profile.title} · karar anı ${formatTime(log.time)}${if (log.entriesPaused) " · yeni işlemler duraklatılmıştı" else ""}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!log.marketAllowsLongs) Text(log.marketReasons.joinToString(), style = MaterialTheme.typography.bodySmall)
        coins.sortedBy { it.outcome.ordinal }.forEach { CoinRow(it, expanded || coinFilter != null) }
    }
}

@Composable
private fun CoinRow(coin: CoinDecision, initiallyExpanded: Boolean) {
    var open by rememberSaveable(coin.symbol, coin.data.m15Close) { mutableStateOf(initiallyExpanded && coin.strategies.isNotEmpty()) }
    val colors = SpotBotTheme.tradingColors
    val color = when (coin.outcome) {
        CoinOutcome.OPENED, CoinOutcome.ORDER_PLACED -> colors.gain
        CoinOutcome.REJECTED, CoinOutcome.ENTRY_FAILED, CoinOutcome.STALE_DATA -> colors.loss
        CoinOutcome.ELIMINATED, CoinOutcome.FLAGGED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(Modifier.fillMaxWidth().clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(coin.symbol.removeSuffix("USDT"), fontWeight = FontWeight.SemiBold)
            Tag(coin.outcome.title, color)
            coin.preScore?.let { Text("puan $it", style = MaterialTheme.typography.labelSmall) }
        }
        Text(coin.summary, style = MaterialTheme.typography.bodySmall)
        if (open) {
            coin.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            DataStampText(coin.data)
            coin.strategies.forEach { s ->
                Text(
                    "${s.strategy.code} ${s.strategy.title}: " + when {
                        !s.applicable -> "değerlendirilmedi"
                        !s.setupFound -> "kurulum yok"
                        else -> "giriş ${formatPrice(s.entry ?: 0.0)}, stop ${formatPrice(s.stop ?: 0.0)}, hedef ${formatPrice(s.target ?: 0.0)}, net R:R %.2f, kalite %.0f".format(s.netRiskReward ?: 0.0, s.qualityScore ?: 0.0)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                s.reasons.forEach { Text("   – $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                s.rules.sortedBy { !it.mandatory }.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (r.passed) "✓" else "✗", color = if (r.passed) colors.gain else colors.loss, fontWeight = FontWeight.Bold)
                        Column {
                            Text("${r.id} ${r.title}${if (r.mandatory) " (zorunlu)" else ""}", style = MaterialTheme.typography.bodySmall)
                            Text(r.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else if (coin.strategies.isNotEmpty() || coin.reasons.isNotEmpty()) {
            Text("Ayrıntı için dokunun", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(color = Color.Transparent)
    }
}

@Composable
private fun DataStampText(d: DataStamp) {
    fun t(v: Long?) = v?.let { formatDateTime(it) } ?: "—"
    Text(
        "Veri zamanı — 15M: ${t(d.m15Close)} · 1H: ${t(d.h1Close)} · 4H: ${t(d.h4Close)} · fiyat: ${d.priceTime?.let { formatTime(it) } ?: "—"}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OpenCard(p: Position, expanded: Boolean, chart: List<Candle>?, onLoadChart: () -> Unit) {
    ExpandableCard(
        initiallyExpanded = expanded,
        header = {
            Text(p.symbol, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Tag(p.strategy.title, MaterialTheme.colorScheme.primary)
        },
        summary = "Giriş ${formatPrice(p.entryPrice)} · Stop ${formatPrice(p.stopPrice)} · Hedef ${formatPrice(p.targetPrice)} · Risk ${formatMoney(p.openRisk)} USDT",
    ) {
        TradeChart(p, null, null, chart, onLoadChart)
        Rationale(p.rationale)
        p.ruleReport?.let { Rules(it) }
    }
}

@Composable
private fun ClosedCard(t: ClosedTrade, expanded: Boolean, chart: List<Candle>?, onLoadChart: () -> Unit) {
    ExpandableCard(
        initiallyExpanded = expanded,
        header = {
            Text(t.position.symbol, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${formatSignedMoney(t.netPnl)} USDT (${"%+.2f".format(t.rMultiple)}R)", color = gainLossColor(t.netPnl.compareTo(0.0)), fontWeight = FontWeight.SemiBold)
        },
        summary = "${t.position.strategy.title} · ${t.reason.title} · ${formatDateTime(t.position.openedAt)} → ${formatDateTime(t.closedAt)}",
    ) {
        TradeChart(t.position, t.closedAt, t.exitPrice, chart, onLoadChart)
        KeyValueRow("Giriş / çıkış", "${formatPrice(t.position.entryPrice)} → ${formatPrice(t.exitPrice)}")
        KeyValueRow("Komisyon", "${formatMoney(t.fees)} USDT")
        KeyValueRow("Kapanış gerekçesi", t.note)
        Rationale(t.position.rationale)
        t.position.ruleReport?.let { Rules(it) }
    }
}

@Composable
private fun TradeChart(p: Position, closedAt: Long?, exitPrice: Double?, chart: List<Candle>?, onLoad: () -> Unit) {
    val colors = SpotBotTheme.tradingColors
    when {
        chart == null -> OutlinedButton(onClick = onLoad) { Text("İşlem grafiğini göster (15M)") }
        chart.isEmpty() -> EmptyText("Grafik verisi alınamadı.")
        else -> CandleChart(
            candles = chart.takeLast(120),
            markers = listOfNotNull(
                PriceMarker(p.entryPrice, MaterialTheme.colorScheme.primary, "Giriş"),
                PriceMarker(p.initialStopPrice, colors.loss, "Stop"),
                PriceMarker(p.targetPrice, colors.gain, "Hedef"),
                exitPrice?.let { PriceMarker(it, MaterialTheme.colorScheme.tertiary, "Çıkış") },
            ),
            timeMarkers = listOfNotNull(
                TimeMarker(p.openedAt, MaterialTheme.colorScheme.primary, "Giriş"),
                closedAt?.let { TimeMarker(it, MaterialTheme.colorScheme.tertiary, "Çıkış") },
            ),
        )
    }
}

@Composable
private fun ExpandableCard(
    initiallyExpanded: Boolean,
    header: @Composable RowScope.() -> Unit,
    summary: String,
    details: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = header)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                HorizontalDivider()
                details()
            } else {
                Text("Gerekçeleri ve grafiği görmek için dokunun", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Rationale(r: TradeRationale) {
    Text("İşlem gerekçesi", style = MaterialTheme.typography.titleSmall)
    listOf(
        "Neden bu coin?" to r.whyCoin,
        "Neden bu giriş?" to r.whyEntry,
        "Trend ve yapı uygun mu?" to r.trendCheck,
        "Stop nerede ve neden?" to r.stopReason,
        "Net risk/getiri" to r.riskReward,
        "Ne zaman geçersiz olur?" to r.invalidation,
    ).forEach { (q, a) ->
        Text(q, style = MaterialTheme.typography.labelLarge)
        Text(a, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Rules(report: RuleReport) {
    Text("Kurallar · kalite puanı %.0f".format(report.qualityScore), style = MaterialTheme.typography.titleSmall)
    report.outcomes.sortedBy { it.group }.forEach { o ->
        val color = if (o.passed) SpotBotTheme.tradingColors.gain else SpotBotTheme.tradingColors.loss
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (o.passed) "✓" else "✗", color = color, fontWeight = FontWeight.Bold)
            Column {
                Text("${o.id} ${o.title}${if (o.group == RuleGroup.MANDATORY) " (zorunlu)" else ""}", style = MaterialTheme.typography.bodySmall)
                Text(o.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
