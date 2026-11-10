package com.spotbot.android.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.LineChart
import com.spotbot.android.ui.common.MetricGrid
import com.spotbot.android.ui.common.NavRow
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.android.ui.scan.MarketFilterDetails
import com.spotbot.android.ui.scan.marketBlockColor
import com.spotbot.core.engine.EngineStatus
import com.spotbot.core.exchange.mexc.ConnectionStatus
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.ClosedTrade
import com.spotbot.core.trading.Decision
import com.spotbot.core.trading.DecisionOutcome
import com.spotbot.core.trading.PeriodResult

class PanelActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val setPaused: (Boolean) -> Unit,
    val closePosition: (String) -> Unit,
    val openSymbol: (String) -> Unit,
    val cancelPending: (String) -> Unit = {},
    val openTrade: (String) -> Unit = {},
    val openEvents: () -> Unit = {},
)

@Composable
fun PanelScreen(
    onOpenSymbol: (String) -> Unit,
    onOpenTrade: (String) -> Unit,
    onOpenEvents: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PanelViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PanelContent(
        state,
        PanelActions(viewModel::startBot, viewModel::stopBot, viewModel::setEntriesPaused, viewModel::closePosition, onOpenSymbol, viewModel::cancelPending, onOpenTrade, onOpenEvents),
        modifier,
    )
}

/** Botun ekrandaki durumu: çalışıyor / duraklatıldı / durduruldu / hata. */
@Composable
fun botStatus(state: PanelUiState): Pair<String, Color> {
    val colors = SpotBotTheme.tradingColors
    val h = state.health
    return when (h.status) {
        EngineStatus.RUNNING -> if (h.entriesPaused) "Duraklatıldı" to MaterialTheme.colorScheme.tertiary else "Çalışıyor" to colors.gain
        EngineStatus.STARTING -> "Başlatılıyor" to MaterialTheme.colorScheme.tertiary
        EngineStatus.ERROR -> "Hata" to colors.loss
        EngineStatus.STOPPED -> "Durduruldu" to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** MEXC bağlantı göstergesi (WebSocket, REST yedeği). */
@Composable
fun mexcStatus(state: PanelUiState): Pair<String, Color> {
    val colors = SpotBotTheme.tradingColors
    val feed = state.health.feed
    return when {
        !state.isActive -> "MEXC bağlı değil" to MaterialTheme.colorScheme.onSurfaceVariant
        feed.webSocket == ConnectionStatus.CONNECTED && !feed.restFallbackActive -> "MEXC bağlı" to colors.gain
        feed.restFallbackActive -> "MEXC · REST yedeği" to MaterialTheme.colorScheme.tertiary
        feed.webSocket == ConnectionStatus.CONNECTING || feed.webSocket == ConnectionStatus.RECONNECTING -> "MEXC bağlanıyor" to MaterialTheme.colorScheme.tertiary
        else -> "MEXC bağlantısı yok" to colors.loss
    }
}

@Composable
fun PanelContent(state: PanelUiState, actions: PanelActions, modifier: Modifier = Modifier) {
    var confirmStop by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        item { StatusHeader(state) }
        item { CapitalCard(state) }
        item { ControlCard(state, actions) { confirmStop = true } }
        item { OpenPositionsCard(state, actions) }
        item { RecentTradesCard(state.recentTrades, actions) }
        item { DetailsCard(state) }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Bot durdurulsun mu?") },
            text = {
                Text(
                    if (state.positions.isNotEmpty()) {
                        "Açık pozisyonların stop ve hedef takibi de durur. MEXC spot API'sinde borsa tarafı stop emri olmadığı için koruma yazılımdadır. Yalnız yeni işlemleri durdurmak için Duraklat'ı kullanın."
                    } else {
                        "Tarama ve fiyat takibi durur. Açık pozisyon yok."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { actions.stop(); confirmStop = false }) { Text("Durdur") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Vazgeç") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHeader(state: PanelUiState) {
    val (status, statusColor) = botStatus(state)
    val (mexc, mexcColor) = mexcStatus(state)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(status, statusColor)
            StatusPill(mexc, mexcColor)
        }
        Text(
            state.health.lastScanAt?.let { "Son tarama ${formatTime(it)} · her 15 dakikada bir" } ?: "Henüz tarama yapılmadı",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.health.lastError?.let {
            Text("Son hata: $it", style = MaterialTheme.typography.bodySmall, color = SpotBotTheme.tradingColors.loss, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CapitalCard(state: PanelUiState) {
    AppCard {
        Text("Toplam sermaye", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatMoney(state.equity), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Text(" USDT", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        MetricGrid(
            listOf(
                Triple(
                    "Toplam kâr/zarar",
                    "${formatSignedMoney(state.totalPnl)} (${formatPercent(state.totalPnlPercent, signed = true, minFractionDigits = 2)})",
                    gainLossColor(state.totalPnl.compareTo(0.0)),
                ),
                Triple(
                    "Bugün",
                    "${formatSignedMoney(state.dailyChange)} (${formatPercent(state.dailyChangePercent, signed = true, minFractionDigits = 2)})",
                    gainLossColor(state.dailyChange.compareTo(0.0)),
                ),
                Triple("Kullanılabilir USDT", formatMoney(state.cash), Color.Unspecified),
                Triple("Açık pozisyon K/Z", formatSignedMoney(state.unrealized), gainLossColor(state.unrealized.compareTo(0.0))),
            ),
        )
    }
}

@Composable
private fun ControlCard(state: PanelUiState, actions: PanelActions, onStop: () -> Unit) {
    val paused = state.health.entriesPaused
    val active = state.isActive
    AppCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Üç düğme küçük ekranda da sığsın diye iç boşluk azaltıldı (dokunma alanı en az 48 dp).
            val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            Button(
                onClick = { if (active) actions.setPaused(false) else actions.start() },
                enabled = !active || paused,
                contentPadding = padding,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Başlat", maxLines = 1, softWrap = false) }
            FilledTonalButton(
                onClick = { actions.setPaused(true) },
                enabled = active && !paused,
                contentPadding = padding,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Duraklat", maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = onStop,
                enabled = active,
                contentPadding = padding,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotBotTheme.tradingColors.loss),
            ) { Text("Durdur", maxLines = 1, softWrap = false) }
        }
        val blocked = state.risk.entriesBlockedReason
        when {
            !active -> Text("Bot kapalı. Başlat ile tarama ve sanal işlem başlar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            paused -> Text("Yeni işlem açılmıyor; açık pozisyonların stop ve hedef takibi sürüyor.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            blocked != null -> {
                // Engelin türü ayrı gösterilir: veri sorunu (sarı) gerçek piyasa riskinden (kırmızı) ayrılır.
                val kind = state.risk.marketBlockKind
                StatusPill(kind?.let { "Yeni işlem yok · ${it.title}" } ?: "Yeni işlem yok", kind?.let { marketBlockColor(it) } ?: SpotBotTheme.tradingColors.loss)
                Text(blocked, style = MaterialTheme.typography.bodySmall)
                if (kind != null) Text("Sayılar: Tarama → Teşhis · ayrıntı: Risk ve sistem ayrıntıları", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.scanReason != null -> {
                Text("Son taramada işlem açılmadı", style = MaterialTheme.typography.labelLarge)
                Text(state.scanReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Sayılar: Tarama → Teşhis", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Text("Her 15 dakikada tarar; kurallar sağlanırsa sanal işlem açar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OpenPositionsCard(state: PanelUiState, actions: PanelActions) {
    SectionCard("Açık pozisyonlar", trailing = { Text("${state.positions.size} / ${state.risk.maxOpenPositions}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
        if (state.positions.isEmpty() && state.pendingOrders.isEmpty()) {
            EmptyText("Açık pozisyon yok. Kurallar sağlanmadığında işlem açmamak da doğru bir karardır.")
        }
        state.positions.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PositionLine(row) { actions.openTrade(row.position.id) }
        }
        state.pendingOrders.forEach { order ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${order.symbol.removeSuffix("USDT")} · bekleyen limit alış", style = MaterialTheme.typography.titleSmall)
                    Text("Limit ${formatPrice(order.limitPrice)} · son ${formatTime(order.expiresAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { actions.cancelPending(order.symbol) }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("İptal")
                }
            }
        }
    }
}

@Composable
private fun PositionLine(row: PositionRow, onClick: () -> Unit) {
    val p = row.position
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium)
                Tag(p.strategy.code, MaterialTheme.colorScheme.primary)
            }
            Text(
                "${formatPrice(p.entryPrice)} → ${row.bid?.let { formatPrice(it) } ?: "—"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val pnl = row.pnl
            Text(pnl?.let { formatSignedMoney(it) } ?: "—", style = MaterialTheme.typography.titleMedium, color = gainLossColor((pnl ?: 0.0).compareTo(0.0)), maxLines = 1)
            Text(row.r?.let { "%+.2fR".format(it) } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentTradesCard(trades: List<ClosedTrade>, actions: PanelActions) {
    SectionCard("Son işlemler") {
        if (trades.isEmpty()) EmptyText("Henüz kapanmış işlem yok.")
        trades.forEachIndexed { i, t ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { actions.openTrade(t.position.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${t.position.symbol.removeSuffix("USDT")} · ${t.position.strategy.code}", style = MaterialTheme.typography.titleSmall)
                    Text("${t.reason.title} · ${formatDateTime(t.closedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(formatSignedMoney(t.netPnl), style = MaterialTheme.typography.titleSmall, color = gainLossColor(t.netPnl.compareTo(0.0)))
            }
        }
        NavRow("Olay günlüğü", subtitle = "Açılan, kapanan ve reddedilen tüm kararlar", onClick = actions.openEvents)
    }
}

/** Ayrıntılı risk, sistem ve performans bilgileri: varsayılan kapalı. */
@Composable
private fun DetailsCard(state: PanelUiState) {
    val r = state.risk
    val h = state.health
    val colors = SpotBotTheme.tradingColors
    ExpandableCard("Risk ve sistem ayrıntıları", subtitle = if (r.entriesBlockedReason == null) "Yeni işleme açık" else "Yeni işlem yok: ${r.entriesBlockedReason}") {
        Text("Risk", style = MaterialTheme.typography.titleSmall)
        KeyValueRow("Strateji profili", r.profile.title)
        KeyValueRow("Toplam açık risk", "%s / %s".format(formatPercent(r.openRiskPercent, minFractionDigits = 2), formatPercent(r.maxTotalOpenRiskPercent)))
        KeyValueRow(
            "Bugün / günlük limit", "%s / -%s".format(formatPercent(r.dailyPnlPercent, signed = true, minFractionDigits = 2), formatPercent(r.dailyLossLimitPercent)),
            gainLossColor(r.dailyPnlPercent.compareTo(0.0)),
        )
        KeyValueRow("Açık işlem + bekleyen emir", "${r.openPositions} + ${r.pendingOrders} / ${r.maxOpenPositions}")
        KeyValueRow("İşlem başına risk", formatPercent(r.riskPerTradePercent))
        KeyValueRow("Stop yöntemi", r.atrStopMode.title)
        if (r.correlatedPairs.isNotEmpty()) KeyValueRow("İlişkili pozisyonlar", r.correlatedPairs.joinToString())
        KeyValueRow("Etkin stratejiler", StrategyId.entries.filter { it in state.enabledStrategies }.joinToString { it.code }.ifEmpty { "—" })

        r.market?.takeIf { it.conditions.isNotEmpty() }?.let { market ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Piyasa filtresi (BTC)", style = MaterialTheme.typography.titleSmall)
            MarketFilterDetails(market)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Performans", style = MaterialTheme.typography.titleSmall)
        state.performance?.let { p ->
            Period("Bugün", p.today)
            Period("7 gün", p.week)
            Period("30 gün", p.month)
            KeyValueRow("Kazanma oranı · ort. R", "%s · %+.2fR".format(formatPercent(p.winRatePercent), p.averageR))
            KeyValueRow("Toplam komisyon", "${formatMoney(p.totalFees)} USDT")
        }
        if (state.equityHistory.size >= 2) LineChart(state.equityHistory)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Sistem", style = MaterialTheme.typography.titleSmall)
        KeyValueRow("Çalıştığı yer", h.runLocation)
        h.uptime24hPercent?.let { KeyValueRow("Son 24 saatte çalışma", formatPercent(it, minFractionDigits = 1), if (it >= 99) colors.gain else colors.loss) }
        h.feed.lastUpdateAt?.let { KeyValueRow("Son fiyat verisi", formatTime(it)) }
        KeyValueRow("Veri yolu", if (h.feed.restFallbackActive) "REST yedeği" else "WebSocket")
        h.feed.lastMaxDeviationPercent?.let { KeyValueRow("WS/REST fiyat sapması", formatPercent(it, minFractionDigits = 3)) }
        KeyValueRow("Yeniden bağlanma", "${h.feed.reconnects}")
        if (h.universe.isNotEmpty()) KeyValueRow("İzlenen coin", "${h.universe.size}")
        h.lastDowntime?.let { d ->
            KeyValueRow("Son kesinti", "${formatDateTime(d.from)} → ${formatTime(d.to)} (${(d.to - d.from) / 60_000} dk)")
            Text(d.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (h.staleSymbols.isNotEmpty()) {
            Text("Verisi güncel olmayan coinler (işlem açılmadı): ${h.staleSymbols.joinToString()}", style = MaterialTheme.typography.bodySmall, color = colors.loss)
        }
        Text(
            "Bot telefonda çalışır: telefon kapanır, internet kesilir ya da Android uygulamayı durdurursa izleme durur ve bu sürede pozisyonlar korumasızdır.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Period(label: String, result: PeriodResult) {
    KeyValueRow(label, "${formatSignedMoney(result.netPnl)} USDT · ${result.trades} işlem", gainLossColor(result.netPnl.compareTo(0.0)))
}

/** Olay günlüğü satırı (İşlemler → Olay günlüğü). */
@Composable
fun DecisionItem(decision: Decision) {
    val colors = SpotBotTheme.tradingColors
    val (label, color) = when (decision.outcome) {
        DecisionOutcome.OPENED -> "AÇILDI" to colors.gain
        DecisionOutcome.CLOSED -> "KAPANDI" to MaterialTheme.colorScheme.primary
        DecisionOutcome.REJECTED -> "RED" to colors.loss
        DecisionOutcome.NO_SETUP -> "KURULUM YOK" to MaterialTheme.colorScheme.onSurfaceVariant
        DecisionOutcome.INFO -> "BİLGİ" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(label, color)
            Text(formatDateTime(decision.time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(decision.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        decision.details.take(4).forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
