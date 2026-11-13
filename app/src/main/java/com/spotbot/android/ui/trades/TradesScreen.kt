package com.spotbot.android.ui.trades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.MetricTile
import com.spotbot.android.ui.common.SegmentedTabs
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.kr.ExitSource
import com.spotbot.core.kr.KrPosition
import com.spotbot.core.kr.KrState
import com.spotbot.core.kr.KrTrade
import kotlin.math.sign

enum class TradesTab(val label: String) { OPEN("Açık"), CLOSED("Kapalı") }

/** İşlemler: coin, giriş, stop, hedef, net sonuç ve çıkış nedeni. Yalnız Kırılım–Retest paper işlemleri. */
@Composable
fun TradesContent(state: KrState, onOpenArchive: () -> Unit, initialTab: TradesTab = TradesTab.OPEN) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val open = state.book.positions
    val closed = state.book.trades.sortedByDescending { it.exitTime }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SegmentedTabs(TradesTab.entries, tab, { "${it.label} (${if (it == TradesTab.OPEN) open.size else closed.size})" }, { tab = it })
        }
        if (tab == TradesTab.OPEN) {
            if (open.isEmpty()) item { EmptyText("Açık pozisyon yok.") }
            items(open, key = { it.id }) { OpenRow(it, state.marks[it.symbol]) }
        } else {
            if (closed.isNotEmpty()) item {
                AppCard {
                    val net = closed.sumOf { it.netPnl }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricTile("Toplam net", "${formatSignedMoney(net)} USDT", Modifier.weight(1f), gainLossColor(net.sign.toInt()))
                        MetricTile("Kazanan", "${closed.count { it.netPnl > 0 }} / ${closed.size}", Modifier.weight(1f))
                    }
                }
            }
            if (closed.isEmpty()) item { EmptyText("Kapanmış işlem yok.") }
            items(closed, key = { it.id }) { ClosedRow(it) }
        }
        item {
            TextButton(onClick = onOpenArchive) { Text("Eski stratejilerin kayıtları (arşiv, bu sonuçlara dahil değil)") }
        }
    }
}

@Composable
private fun OpenRow(p: KrPosition, mark: Double?) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            mark?.let {
                val pnl = (it - p.entryPrice) * p.quantity - p.entryFee - it * p.quantity * p.exitLeg.fee
                Text("${formatSignedMoney(pnl)} USDT", color = gainLossColor(pnl.sign.toInt()), style = MaterialTheme.typography.titleMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Giriş", formatPrice(p.entryPrice), Modifier.weight(1f))
            MetricTile("Stop", formatPrice(p.stop), Modifier.weight(1f))
            MetricTile("Hedef", formatPrice(p.target), Modifier.weight(1f))
        }
        Text("${formatDateTime(p.entryTime)} TSİ · planlanan net R:R ${"%.2f".format(p.plannedNetRewardRisk)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ClosedRow(t: KrTrade) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium)
                Text(t.reason.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${formatSignedMoney(t.netPnl)} USDT", color = gainLossColor(t.netPnl.sign.toInt()), style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Giriş", formatPrice(t.entryPrice), Modifier.weight(1f))
            MetricTile("Stop", formatPrice(t.stop), Modifier.weight(1f))
            MetricTile("Hedef", formatPrice(t.target), Modifier.weight(1f))
        }
        Text(
            "${formatDateTime(t.entryTime)} → ${formatDateTime(t.exitTime)} TSİ · çıkış ${formatPrice(t.exitPrice)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (t.source == ExitSource.BACKFILLED || t.ambiguous) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.source == ExitSource.BACKFILLED) Tag("Canlı izlenmedi", MaterialTheme.colorScheme.error)
                if (t.ambiguous) Tag("Stop/hedef sırası belirsiz", MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
