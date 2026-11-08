package com.spotbot.android.ui.trades

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatRatio
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.panel.DecisionItem
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.rules.RuleGroup
import com.spotbot.core.rules.RuleReport
import com.spotbot.core.trading.ClosedTrade
import com.spotbot.core.trading.Performance
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.Position
import com.spotbot.core.trading.TradeRationale

class TradesViewModel(engine: TradingEngine) : ViewModel() {
    val portfolio = engine.portfolio
}

private enum class Tab(val title: String) { OPEN("Açık"), CLOSED("Kapanmış"), JOURNAL("Karar günlüğü") }

@Composable
fun TradesScreen(modifier: Modifier = Modifier, viewModel: TradesViewModel = viewModel(factory = AppViewModels.Factory)) {
    val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
    TradesContent(portfolio, modifier)
}

@Composable
fun TradesContent(portfolio: PortfolioState, modifier: Modifier = Modifier, initialTab: Int = 0, expanded: Boolean = false) {
    var tab by rememberSaveable { mutableStateOf(Tab.entries[initialTab]) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.title) }) }
            }
        }
        when (tab) {
            Tab.OPEN -> {
                if (portfolio.positions.isEmpty()) item { EmptyText("Açık işlem yok.") }
                items(portfolio.positions, key = { it.id }) { OpenCard(it, expanded) }
            }
            Tab.CLOSED -> {
                if (portfolio.closedTrades.isEmpty()) item { EmptyText("Henüz kapanmış işlem yok.") }
                items(portfolio.closedTrades.reversed(), key = { it.position.id }) { ClosedCard(it, expanded) }
            }
            Tab.JOURNAL -> {
                items(portfolio.decisions.reversed()) { DecisionItem(it) }
            }
        }
    }
}

@Composable
private fun OpenCard(p: Position, expanded: Boolean) {
    ExpandableCard(
        initiallyExpanded = expanded,
        header = {
            Text(p.symbol, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Tag(p.strategy.title, MaterialTheme.colorScheme.primary)
        },
        summary = "Giriş ${formatPrice(p.entryPrice)} · Stop ${formatPrice(p.stopPrice)} · Hedef ${formatPrice(p.targetPrice)} · Risk ${formatMoney(p.riskAmount)} USDT",
    ) {
        Rationale(p.rationale)
        p.ruleReport?.let { Rules(it) }
    }
}

@Composable
private fun ClosedCard(t: ClosedTrade, expanded: Boolean) {
    ExpandableCard(
        initiallyExpanded = expanded,
        header = {
            Text(t.position.symbol, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${formatSignedMoney(t.netPnl)} USDT (${"%+.2f".format(t.rMultiple)}R)", color = gainLossColor(t.netPnl.compareTo(0.0)), fontWeight = FontWeight.SemiBold)
        },
        summary = "${t.position.strategy.title} · ${t.reason.title} · ${formatDateTime(t.position.openedAt)} → ${formatDateTime(t.closedAt)}",
    ) {
        KeyValueRow("Giriş / çıkış", "${formatPrice(t.position.entryPrice)} → ${formatPrice(t.exitPrice)}")
        KeyValueRow("Komisyon", "${formatMoney(t.fees)} USDT")
        KeyValueRow("Kapanış gerekçesi", t.note)
        Rationale(t.position.rationale)
        t.position.ruleReport?.let { Rules(it) }
    }
}

@Composable
private fun ExpandableCard(
    initiallyExpanded: Boolean,
    header: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
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
                Text("Gerekçeleri görmek için dokunun", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
