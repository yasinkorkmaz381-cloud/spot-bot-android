package com.spotbot.android.ui.trades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.CandleChart
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.MetricGrid
import com.spotbot.android.ui.common.NavRow
import com.spotbot.android.ui.common.PriceMarker
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.SegmentedTabs
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.TimeMarker
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatQuantity
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
import com.spotbot.core.risk.CostModel
import com.spotbot.core.rules.RuleGroup
import com.spotbot.core.rules.RuleReport
import com.spotbot.core.trading.ClosedTrade
import com.spotbot.core.trading.Performance
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.Position
import com.spotbot.core.trading.TradeRationale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** İşlem çevresindeki kapanmış 15M mumları REST'ten yükler (işlem kimliği → mumlar). */
class TradeChartLoader(private val api: MarketDataApi, private val scope: CoroutineScope) {
    private val _charts = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())
    val charts: StateFlow<Map<String, List<Candle>>> = _charts.asStateFlow()

    fun load(position: Position, closedAt: Long?) {
        if (_charts.value.containsKey(position.id)) return
        scope.launch {
            val now = runCatching { api.serverTime() }.getOrDefault(System.currentTimeMillis())
            val start = position.openedAt - 8 * Timeframe.H1.millis
            val end = minOf(now, (closedAt ?: now) + 2 * Timeframe.H1.millis)
            val candles = runCatching { api.klines(position.symbol, Timeframe.M15, limit = 500, startTime = start, endTime = end) }.getOrDefault(emptyList())
                .filter { it.isClosedAt(now) }
            _charts.update { it + (position.id to candles) }
        }
    }
}

class TradesViewModel(private val engine: TradingEngine, private val forwardTracker: com.spotbot.core.research.ForwardTracker) : ViewModel() {
    val portfolio = engine.portfolio
    val shadows = engine.shadows
    val forward = forwardTracker.state
    private val _forwardBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val forwardBusy = _forwardBusy.asStateFlow()

    fun refreshForward() {
        if (_forwardBusy.value) return
        _forwardBusy.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { forwardTracker.refresh() } finally { _forwardBusy.value = false }
        }
    }
    val prices = engine.prices

    fun cancelPending(symbol: String) = engine.cancelPendingOrder(symbol)
}

enum class TradesTab(val title: String) { OPEN("Açık"), HISTORY("Geçmiş"), SHADOW("Gölge test") }

@Composable
fun TradesScreen(onOpenTrade: (String) -> Unit, onOpenEvents: () -> Unit, modifier: Modifier = Modifier, viewModel: TradesViewModel = viewModel(factory = AppViewModels.Factory)) {
    val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    val shadows by viewModel.shadows.collectAsStateWithLifecycle()
    val forward by viewModel.forward.collectAsStateWithLifecycle()
    val forwardBusy by viewModel.forwardBusy.collectAsStateWithLifecycle()
    TradesContent(
        portfolio, prices.mapValues { it.value.bid }, onOpenTrade, modifier, onOpenEvents, viewModel::cancelPending, shadows = shadows,
        forward = forward, forwardBusy = forwardBusy, onRefreshForward = viewModel::refreshForward,
    )
}

@Composable
fun TradesContent(
    portfolio: PortfolioState,
    bids: Map<String, Double>,
    onOpenTrade: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenEvents: () -> Unit = {},
    onCancelPending: (String) -> Unit = {},
    initialTab: TradesTab = TradesTab.OPEN,
    shadows: Map<String, PortfolioState> = emptyMap(),
    forward: com.spotbot.core.research.ForwardState? = null,
    forwardBusy: Boolean = false,
    onRefreshForward: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        item {
            SegmentedTabs(
                TradesTab.entries, tab,
                label = { if (it == TradesTab.OPEN) "${it.title} (${portfolio.positions.size})" else it.title },
                onSelect = { tab = it },
            )
        }
        when (tab) {
            TradesTab.OPEN -> {
                if (portfolio.positions.isEmpty() && portfolio.pendingOrders.isEmpty()) item { EmptyText("Açık işlem yok.", Modifier.padding(4.dp)) }
                items(portfolio.positions, key = { it.id }) { p -> OpenPositionCard(p, bids[p.symbol]) { onOpenTrade(p.id) } }
                items(portfolio.pendingOrders, key = { "pending-${it.symbol}" }) { order ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${order.symbol.removeSuffix("USDT")} · bekleyen limit alış", style = MaterialTheme.typography.titleSmall)
                                Text("Limit ${formatPrice(order.limitPrice)} · son ${formatTime(order.expiresAt)} · ayrılan ${formatMoney(order.reservedQuote)} USDT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onCancelPending(order.symbol) }) { Text("İptal") }
                        }
                    }
                }
            }
            TradesTab.SHADOW -> {
                item {
                    Text(
                        "Gölge testteki stratejiler aynı tarama, kural ve risk koduyla ayrı sanal hesapta işlem açar. Aktif portföyü etkilemez; veri toplamak içindir.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "forward-s1") { ForwardCard(forward, forwardBusy, onRefreshForward) }
                if (shadows.isEmpty()) item { EmptyText("Gölge testteki 15M stratejilerinde (ör. A) henüz işlem yok. Kurulum bulunduğunda burada görünür.", Modifier.padding(4.dp)) }
                shadows.toSortedMap().forEach { (name, state) ->
                    item(key = "shadow-$name") { ShadowCard(name, state, bids) }
                }
            }
            TradesTab.HISTORY -> {
                val summary = Performance.summarize(portfolio, System.currentTimeMillis())
                item {
                    AppCard {
                        MetricGrid(
                            listOf(
                                Triple("Net kâr/zarar", "${formatSignedMoney(summary.allTime.netPnl)} USDT", gainLossColor(summary.allTime.netPnl.compareTo(0.0))),
                                Triple("İşlem", "${summary.allTime.trades}", Color.Unspecified),
                                Triple("Kazanma oranı", formatPercent(summary.winRatePercent), Color.Unspecified),
                                Triple("Profit factor", formatRatio(summary.profitFactor), Color.Unspecified),
                            ),
                        )
                    }
                }
                if (portfolio.closedTrades.isEmpty()) item { EmptyText("Henüz kapanmış işlem yok.", Modifier.padding(4.dp)) }
                items(portfolio.closedTrades.reversed(), key = { it.position.id }) { t -> ClosedTradeCard(t) { onOpenTrade(t.position.id) } }
                item { AppCard { NavRow("Olay günlüğü", subtitle = "Açılan, kapanan ve reddedilen tüm kararlar", onClick = onOpenEvents) } }
            }
        }
    }
}

@Composable
private fun ShadowCard(name: String, state: PortfolioState, bids: Map<String, Double>) {
    val id = runCatching { com.spotbot.core.strategy.StrategyId.valueOf(name) }.getOrNull()
    val summary = Performance.summarize(state, System.currentTimeMillis())
    val equity = state.cash + state.positions.sumOf { p -> (bids[p.symbol] ?: p.entryPrice) * p.quantity }
    SectionCard(id?.let { "Strateji ${it.code} · ${it.title}" } ?: name) {
        MetricGrid(
            listOf(
                Triple("Sanal sermaye", "${formatMoney(equity)} USDT", Color.Unspecified),
                Triple("Net kâr/zarar", "${formatSignedMoney(summary.allTime.netPnl)} USDT", gainLossColor(summary.allTime.netPnl.compareTo(0.0))),
                Triple("İşlem", "${summary.allTime.trades}", Color.Unspecified),
                Triple("Profit factor", formatRatio(summary.profitFactor), Color.Unspecified),
            ),
        )
        state.positions.forEach { p -> KeyValueRow("Açık: ${p.symbol.removeSuffix("USDT")}", "${formatPrice(p.entryPrice)} → ${bids[p.symbol]?.let { formatPrice(it) } ?: "—"}") }
        state.closedTrades.takeLast(5).reversed().forEach { t ->
            KeyValueRow("${t.position.symbol.removeSuffix("USDT")} · ${formatDateTime(t.closedAt)}", "${formatSignedMoney(t.netPnl)} · ${t.reason.title}", gainLossColor(t.netPnl.compareTo(0.0)))
        }
        if (summary.allTime.trades < 30) Text("30'dan az işlem: sonuç henüz istatistik için yetersiz.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** V3 araştırmasından S1'in ileri testi: ayrı 1.000 USDT sanal portföy, 4H mum kapanışlarıyla güncellenir. */
@Composable
private fun ForwardCard(state: com.spotbot.core.research.ForwardState?, busy: Boolean, onRefresh: () -> Unit) {
    val r = state?.result
    SectionCard("S1 · Göreceli güç (ileri test)") {
        Text(
            "V3 araştırmasında ön kayıtlı ölçütleri geçen tek strateji. Aynı kodla, yalnız takip başladıktan sonra kapanan 4H mumlarında " +
                "işlem yapar; dolumlar sonraki 4H açılışından ve orta maliyet senaryosuyla hesaplanır. Gerçek emir gönderilmez.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            state == null -> Text(if (busy) "İlk veri indiriliyor…" else "Henüz başlatılmadı. Bot çalışırken otomatik başlar ya da Güncelle'ye basın.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                val value = state.capital * (1 + (r?.netReturnPercent ?: 0.0) / 100)
                MetricGrid(
                    listOf(
                        Triple("Sanal değer", "${formatMoney(value)} USDT", Color.Unspecified),
                        Triple("Net", r?.let { formatPercent(it.netReturnPercent, signed = true, minFractionDigits = 2) } ?: "—", gainLossColor((r?.netReturnPercent ?: 0.0).compareTo(0.0))),
                        Triple("Kapanan işlem", "${r?.trades ?: 0}", Color.Unspecified),
                        Triple("Ort. R", r?.takeIf { it.trades > 0 }?.let { "%+.2f".format(it.averageR) } ?: "—", Color.Unspecified),
                    ),
                )
                KeyValueRow("Başlangıç", formatDateTime(state.startedAt))
                KeyValueRow("Son kapanmış 4H mum", if (state.dataEnd > 0) formatDateTime(state.dataEnd) else "—")
                KeyValueRow("İşlem yapılabilir parite", "${state.tradableNow} / ${state.symbolsLoaded}")
                r?.btcBuyHoldPercent?.let { KeyValueRow("BTC al-tut (aynı dönem)", formatPercent(it, signed = true, minFractionDigits = 2)) }
                r?.open?.forEach { p ->
                    KeyValueRow("Açık: ${p.symbol.removeSuffix("USDT")}", "${formatPrice(p.entryPrice)} → ${formatPrice(p.lastPrice)} · stop ${formatPrice(p.stop)}", gainLossColor(p.unrealizedPnl.compareTo(0.0)))
                }
                r?.tradeList?.takeLast(5)?.reversed()?.forEach { t ->
                    KeyValueRow("${t.symbol.removeSuffix("USDT")} · ${formatDateTime(t.exitTime)}", "${formatSignedMoney(t.netPnl)} · ${t.reason}", gainLossColor(t.netPnl.compareTo(0.0)))
                }
                val status = when {
                    state.lastError != null -> "Yazılım / veri sorunu: ${state.lastError}"
                    r == null -> "Henüz yeni 4H mumu kapanmadı; işlem yapılmaması kuralların değil zamanın sonucu."
                    r.trades == 0 && r.open.isEmpty() -> "Yazılım sağlıklı. Şu ana kadar kural koşulları oluşmadı; işlem açılmaması kuralların kararıdır."
                    else -> "Yazılım sağlıklı."
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (state.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if ((r?.trades ?: 0) < 30) Text("30'dan az işlem: sonuç henüz istatistik için yetersiz. Araştırmadaki t değeri de düşüktü (son test t≈0,8).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        TextButton(onClick = onRefresh, enabled = !busy) { Text(if (busy) "Güncelleniyor…" else "Güncelle") }
    }
}

@Composable
private fun OpenPositionCard(p: Position, bid: Double?, onClick: () -> Unit) {
    val pnl = bid?.let { p.unrealizedPnl(it, CostModel()) }
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium)
            Tag(p.strategy.code, MaterialTheme.colorScheme.primary)
            Text(
                pnl?.let { "${formatSignedMoney(it)} USDT" } ?: "—", style = MaterialTheme.typography.titleMedium,
                color = gainLossColor((pnl ?: 0.0).compareTo(0.0)), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1,
            )
        }
        MetricGrid(
            listOf(
                Triple("Alış", formatPrice(p.entryPrice), Color.Unspecified),
                Triple("Güncel", bid?.let { formatPrice(it) } ?: "—", Color.Unspecified),
                Triple("Stop", formatPrice(p.stopPrice), SpotBotTheme.tradingColors.loss),
                Triple("Hedef", formatPrice(p.targetPrice), SpotBotTheme.tradingColors.gain),
            ),
        )
    }
}

@Composable
private fun ClosedTradeCard(t: ClosedTrade, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.position.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium)
            Tag(t.position.strategy.code, MaterialTheme.colorScheme.primary)
            Text(
                "${formatSignedMoney(t.netPnl)} USDT", style = MaterialTheme.typography.titleMedium, color = gainLossColor(t.netPnl.compareTo(0.0)),
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1,
            )
        }
        Text("${formatDateTime(t.position.openedAt)} → ${formatDateTime(t.closedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${formatPrice(t.position.entryPrice)} → ${formatPrice(t.averageExitPrice)} · ${t.reason.title} · %+.2fR".format(t.rMultiple), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---- İşlem ayrıntısı (alt sayfa) ----

class TradeDetailViewModel(private val engine: TradingEngine, api: MarketDataApi, handle: SavedStateHandle) : ViewModel() {
    val id: String = handle["id"] ?: ""
    val portfolio = engine.portfolio
    val prices = engine.prices
    private val loader = TradeChartLoader(api, viewModelScope)
    val chart = loader.charts

    fun loadChart(position: Position, closedAt: Long?) = loader.load(position, closedAt)
    fun close(positionId: String) = engine.closePosition(positionId)
}

@Composable
fun TradeDetailScreen(onOpenSymbol: (String) -> Unit, modifier: Modifier = Modifier, viewModel: TradeDetailViewModel = viewModel(factory = AppViewModels.Factory)) {
    val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    val charts by viewModel.chart.collectAsStateWithLifecycle()
    val open = portfolio.positions.firstOrNull { it.id == viewModel.id }
    val closed = portfolio.closedTrades.lastOrNull { it.position.id == viewModel.id }
    val position = open ?: closed?.position
    if (position == null) {
        EmptyText("İşlem bulunamadı (hesap sıfırlanmış olabilir).", modifier.padding(Spacing.screen))
        return
    }
    LaunchedEffect(position.id) { viewModel.loadChart(position, closed?.closedAt) }
    TradeDetailContent(
        position, if (open != null) null else closed, prices[position.symbol]?.bid, charts[position.id],
        onClose = { viewModel.close(position.id) }, onOpenSymbol = { onOpenSymbol(position.symbol) }, modifier = modifier,
    )
}

@Composable
fun TradeDetailContent(
    p: Position,
    closed: ClosedTrade?,
    bid: Double?,
    chart: List<Candle>?,
    onClose: () -> Unit,
    onOpenSymbol: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClose by remember { mutableStateOf(false) }
    val colors = SpotBotTheme.tradingColors
    val pnl = closed?.netPnl ?: bid?.let { p.unrealizedPnl(it, CostModel()) }
    val r = if (p.riskAmount > 0) pnl?.div(p.riskAmount) else null
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.cards),
    ) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleLarge)
                Tag("${p.strategy.code} ${p.strategy.title}", MaterialTheme.colorScheme.primary)
            }
            StatusPill(closed?.let { "Kapandı · ${it.reason.title}" } ?: "Açık", if (closed == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                pnl?.let { "${formatSignedMoney(it)} USDT" + (r?.let { v -> "  (%+.2fR)".format(v) } ?: "") } ?: "—",
                style = MaterialTheme.typography.headlineSmall, color = gainLossColor((pnl ?: 0.0).compareTo(0.0)),
            )
            MetricGrid(
                listOf(
                    Triple("Alış", formatPrice(p.entryPrice), Color.Unspecified),
                    Triple(if (closed != null) "Satış (ort.)" else "Güncel", (closed?.averageExitPrice ?: bid)?.let { formatPrice(it) } ?: "—", Color.Unspecified),
                    Triple("Stop", formatPrice(p.stopPrice), colors.loss),
                    Triple("Hedef", formatPrice(p.targetPrice), colors.gain),
                    Triple("Açılış", formatDateTime(p.openedAt), Color.Unspecified),
                    Triple(if (closed != null) "Kapanış" else "Risk", closed?.let { formatDateTime(it.closedAt) } ?: "${formatMoney(p.openRisk)} USDT", Color.Unspecified),
                ),
            )
        }
        SectionCard("Grafik (15M)") {
            when {
                chart == null -> EmptyText("Grafik yükleniyor…")
                chart.isEmpty() -> EmptyText("Grafik verisi alınamadı.")
                else -> CandleChart(
                    candles = chart.takeLast(120),
                    markers = listOfNotNull(
                        PriceMarker(p.entryPrice, MaterialTheme.colorScheme.primary, "Giriş"),
                        PriceMarker(p.initialStopPrice, colors.loss, "Stop"),
                        PriceMarker(p.targetPrice, colors.gain, "Hedef"),
                        closed?.let { PriceMarker(it.exitPrice, MaterialTheme.colorScheme.tertiary, "Çıkış") },
                    ),
                    timeMarkers = listOfNotNull(
                        TimeMarker(p.openedAt, MaterialTheme.colorScheme.primary, "Giriş"),
                        closed?.let { TimeMarker(it.closedAt, MaterialTheme.colorScheme.tertiary, "Çıkış") },
                    ),
                )
            }
        }
        ExpandableCard("İşlem gerekçesi", subtitle = "Altı soru: neden bu coin, neden şimdi, stop, R:R, iptal", initiallyExpanded = true) { Rationale(p.rationale) }
        ExpandableCard("Ayrıntılar", subtitle = "Miktar, ücret, kapanış notu") {
            KeyValueRow("Miktar", formatQuantity(p.initialQuantity))
            KeyValueRow("Kalite puanı", "%.0f / 100".format(p.qualityScore))
            KeyValueRow("Risk (başlangıç)", "${formatMoney(p.riskAmount)} USDT")
            closed?.let {
                KeyValueRow("Komisyon", "${formatMoney(it.fees)} USDT")
                KeyValueRow("Kapanış notu", it.note)
            }
        }
        p.ruleReport?.let { report -> ExpandableCard("Kurallar", subtitle = "Kalite puanı %.0f · zorunlu ve kalite kuralları".format(report.qualityScore)) { Rules(report) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onOpenSymbol, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Coin analizi", maxLines = 1) }
            if (closed == null) {
                OutlinedButton(
                    onClick = { confirmClose = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.loss),
                ) { Text("Manuel kapat", maxLines = 1) }
            }
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("${p.symbol} kapatılsın mı?") },
            text = { Text("Pozisyon anlık alış fiyatından (sanal) kapatılır. Bu manuel müdahale olarak kaydedilir.") },
            confirmButton = { TextButton(onClick = { onClose(); confirmClose = false }) { Text("Kapat") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun Rationale(r: TradeRationale) {
    listOf(
        "Neden bu coin?" to r.whyCoin,
        "Neden bu giriş?" to r.whyEntry,
        "Trend ve yapı uygun mu?" to r.trendCheck,
        "Stop nerede ve neden?" to r.stopReason,
        "Net risk/getiri" to r.riskReward,
        "Ne zaman geçersiz olur?" to r.invalidation,
    ).forEach { (q, a) ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(q, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(a, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Rules(report: RuleReport) {
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

// ---- Olay günlüğü (alt sayfa) ----

class EventsViewModel(engine: TradingEngine) : ViewModel() {
    val portfolio = engine.portfolio
}

@Composable
fun EventsScreen(modifier: Modifier = Modifier, viewModel: EventsViewModel = viewModel(factory = AppViewModels.Factory)) {
    val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        if (portfolio.decisions.isEmpty()) item { EmptyText("Henüz kayıt yok.") }
        items(portfolio.decisions.reversed()) { AppCard { DecisionItem(it) } }
    }
}
