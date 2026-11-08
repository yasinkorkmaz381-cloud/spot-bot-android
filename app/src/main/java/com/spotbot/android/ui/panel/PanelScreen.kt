package com.spotbot.android.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.LabeledValue
import com.spotbot.android.ui.common.LineChart
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.engine.EngineStatus
import com.spotbot.core.exchange.mexc.ConnectionStatus
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.Decision
import com.spotbot.core.trading.DecisionOutcome
import com.spotbot.core.trading.PeriodResult

class PanelActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val setPaused: (Boolean) -> Unit,
    val closePosition: (String) -> Unit,
    val openSymbol: (String) -> Unit,
)

@Composable
fun PanelScreen(onOpenSymbol: (String) -> Unit, modifier: Modifier = Modifier, viewModel: PanelViewModel = viewModel(factory = AppViewModels.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PanelContent(state, PanelActions(viewModel::startBot, viewModel::stopBot, viewModel::setEntriesPaused, viewModel::closePosition, onOpenSymbol), modifier)
}

@Composable
fun PanelContent(state: PanelUiState, actions: PanelActions, modifier: Modifier = Modifier) {
    val onOpenSymbol = actions.openSymbol
    var closing by remember { mutableStateOf<PositionRow?>(null) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { InfoBanner("Sanal işlem (paper trading): MEXC canlı verisi kullanılır, gerçek emir gönderilmez.") }
        item { BotCard(state, actions) }
        item { CapitalCard(state) }
        item {
            SectionCard("Açık işlemler (${state.positions.size})") {
                if (state.positions.isEmpty()) EmptyText("Açık işlem yok. Kurallar sağlanmadığında işlem açmamak da doğru bir karardır.")
                state.positions.forEach { row -> PositionItem(row, onOpenSymbol) { closing = row } }
            }
        }
        item { StrategiesCard(state.enabledStrategies) }
        item {
            SectionCard("Son kararlar") {
                if (state.decisions.isEmpty()) EmptyText("Henüz kayıt yok.")
                state.decisions.forEach { DecisionItem(it) }
            }
        }
    }

    closing?.let { row ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("${row.position.symbol} kapatılsın mı?") },
            text = { Text("Pozisyon anlık alış fiyatından (sanal) kapatılır. Bu manuel müdahale olarak kaydedilir.") },
            confirmButton = { TextButton(onClick = { actions.closePosition(row.position.id); closing = null }) { Text("Kapat") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun BotCard(state: PanelUiState, actions: PanelActions) {
    val health = state.health
    val colors = SpotBotTheme.tradingColors
    val (statusText, statusColor) = when (health.status) {
        EngineStatus.RUNNING -> (if (health.entriesPaused) "Çalışıyor · yeni işlemler duraklatıldı" else "Çalışıyor") to colors.gain
        EngineStatus.STARTING -> "Başlatılıyor" to MaterialTheme.colorScheme.tertiary
        EngineStatus.ERROR -> "Hata" to colors.loss
        EngineStatus.STOPPED -> "Durduruldu" to MaterialTheme.colorScheme.outline
    }
    SectionCard("Bot", trailing = { Tag(statusText, statusColor) }) {
        val ws = when (health.feed.webSocket) {
            ConnectionStatus.CONNECTED -> "WebSocket bağlı"
            ConnectionStatus.CONNECTING -> "WebSocket bağlanıyor"
            ConnectionStatus.RECONNECTING -> "WebSocket yeniden bağlanıyor"
            ConnectionStatus.STOPPED -> "WebSocket kapalı"
        }
        KeyValueRow("Veri bağlantısı", ws + if (health.feed.restFallbackActive) " · REST yedeği aktif" else "")
        health.feed.lastUpdateAt?.let { KeyValueRow("Son fiyat verisi", formatTime(it)) }
        health.lastScanAt?.let { KeyValueRow("Son tarama", formatTime(it)) }
        health.feed.lastMaxDeviationPercent?.let { KeyValueRow("WS/REST fiyat sapması", formatPercent(it, minFractionDigits = 3)) }
        if (health.universe.isNotEmpty()) KeyValueRow("İzlenen coin", "${health.universe.size}")
        health.lastError?.let { Text("Son hata: $it", style = MaterialTheme.typography.bodySmall, color = colors.loss) }
        if (state.isActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Yeni işlemleri duraklat", style = MaterialTheme.typography.bodyLarge)
                    Text("Açık pozisyonların stop/hedef takibi sürer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = health.entriesPaused, onCheckedChange = actions.setPaused)
            }
            OutlinedButton(
                onClick = actions.stop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Botu kapat") }
            if (state.positions.isNotEmpty()) {
                Text(
                    "Uyarı: Bot kapatılırsa açık pozisyonların stop takibi de durur. MEXC spot API borsa tarafında stop emri sunmadığı için koruma yazılımdadır.",
                    style = MaterialTheme.typography.bodySmall, color = colors.loss,
                )
            }
        } else {
            Button(onClick = actions.start, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Botu başlat")
            }
        }
    }
}

@Composable
private fun CapitalCard(state: PanelUiState) {
    SectionCard("Sermaye ve performans") {
        Column {
            Text("Toplam sermaye", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatMoney(state.equity)} USDT", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val total = state.equity - state.initialCapital
            Text(
                "Başlangıca göre ${formatSignedMoney(total)} USDT (${formatPercent(if (state.initialCapital > 0) total / state.initialCapital * 100 else 0.0, signed = true, minFractionDigits = 2)})",
                style = MaterialTheme.typography.bodySmall, color = gainLossColor(total.compareTo(0.0)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledValue("Kullanılabilir USDT", formatMoney(state.cash), Modifier.weight(1f))
            LabeledValue("Gerçekleşmemiş K/Z", formatSignedMoney(state.unrealized), Modifier.weight(1f), gainLossColor(state.unrealized.compareTo(0.0)))
        }
        state.performance?.let { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period("Bugün", p.today, Modifier.weight(1f))
                Period("7 gün", p.week, Modifier.weight(1f))
                Period("30 gün", p.month, Modifier.weight(1f))
            }
            Text(
                "Toplam ${p.allTime.trades} işlem · kazanma %s · ort. %+.2fR · komisyon %s USDT".format(
                    formatPercent(p.winRatePercent), p.averageR, formatMoney(p.totalFees),
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.equityHistory.size >= 2) LineChart(state.equityHistory)
    }
}

@Composable
private fun Period(label: String, result: PeriodResult, modifier: Modifier) {
    LabeledValue(label, "${formatSignedMoney(result.netPnl)} (${result.trades})", modifier, gainLossColor(result.netPnl.compareTo(0.0)))
}

@Composable
private fun PositionItem(row: PositionRow, onOpenSymbol: (String) -> Unit, onClose: () -> Unit) {
    val p = row.position
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onOpenSymbol(p.symbol) }) { Text(p.symbol, fontWeight = FontWeight.SemiBold) }
            Tag(p.strategy.code, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            row.pnl?.let {
                Text("${formatSignedMoney(it)} USDT (${"%+.2f".format(row.r ?: 0.0)}R)", color = gainLossColor(it.compareTo(0.0)), fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            "Giriş ${formatPrice(p.entryPrice)} · Anlık ${row.bid?.let { formatPrice(it) } ?: "—"} · Stop ${formatPrice(p.stopPrice)} · Hedef ${formatPrice(p.targetPrice)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Açılış ${formatDateTime(p.openedAt)} · Kalite puanı %.0f".format(p.qualityScore), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClose) { Text("Manuel kapat") }
        HorizontalDivider()
    }
}

@Composable
private fun StrategiesCard(enabled: Set<StrategyId>) {
    SectionCard("Stratejiler") {
        StrategyId.entries.forEach { id ->
            val on = id in enabled
            KeyValueRow(
                "${id.code} · ${id.title}",
                when {
                    on -> "Etkin"
                    id == StrategyId.RANGE -> "Kapalı (ayrı backtest bekliyor)"
                    else -> "Kapalı"
                },
                if (on) SpotBotTheme.tradingColors.gain else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DecisionItem(decision: Decision) {
    val colors = SpotBotTheme.tradingColors
    val (label, color) = when (decision.outcome) {
        DecisionOutcome.OPENED -> "AÇILDI" to colors.gain
        DecisionOutcome.CLOSED -> "KAPANDI" to MaterialTheme.colorScheme.primary
        DecisionOutcome.REJECTED -> "RED" to colors.loss
        DecisionOutcome.NO_SETUP -> "KURULUM YOK" to MaterialTheme.colorScheme.outline
        DecisionOutcome.INFO -> "BİLGİ" to MaterialTheme.colorScheme.outline
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(label, color)
            Text(formatDateTime(decision.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(decision.summary, style = MaterialTheme.typography.bodyMedium)
        decision.details.take(4).forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = Color.Transparent)
}
