package com.spotbot.android.ui.opportunities

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.core.kr.KrState
import com.spotbot.core.kr.Opportunity
import com.spotbot.core.kr.SetupStage

/** Fırsatlar: her coin için durum ve kısa neden; üstte "İlk 300 → MEXC'de mevcut → taranan". */
@Composable
fun OpportunitiesContent(state: KrState, now: Long) {
    var filter by rememberSaveable { mutableStateOf<SetupStage?>(null) }
    val scan = state.scan
    val items = scan?.items.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            AppCard {
                val u = state.universe
                Text(
                    if (u != null) "İlk ${u.top} → MEXC'de ${u.onMexc} → taranan ${scan?.scanned ?: u.included.size}" else "Coin evreni henüz alınmadı",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("funnel"),
                )
                Text(
                    scan?.let { "Son değerlendirme: ${formatTime(it.barClose)} TSİ 15M kapanışı" } ?: "Tarama 15 dakikalık kapanışlarda yapılır.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Tümü ${items.size}") })
                for (s in listOf(SetupStage.ENTRY_READY, SetupStage.AWAITING_RETEST, SetupStage.WATCHING, SetupStage.NOT_SUITABLE)) {
                    FilterChip(selected = filter == s, onClick = { filter = if (filter == s) null else s }, label = { Text("${s.title} ${items.count { it.stage == s }}") })
                }
            }
        }
        val shown = items.filter { filter == null || it.stage == filter }
        if (shown.isEmpty()) item { EmptyText(if (scan == null) "Henüz tarama yok." else "Bu durumda coin yok.") }
        items(shown, key = { it.symbol }) { OpportunityRow(it) }
    }
}

@Composable
private fun OpportunityRow(o: Opportunity) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("#${o.rank} · ${o.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(o.stage.title, stageColor(o.stage))
        }
        Text(o.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun stageColor(s: SetupStage): Color = when (s) {
    SetupStage.ENTRY_READY -> SpotBotTheme.tradingColors.gain
    SetupStage.AWAITING_RETEST -> MaterialTheme.colorScheme.tertiary
    SetupStage.WATCHING -> MaterialTheme.colorScheme.primary
    SetupStage.NOT_SUITABLE -> MaterialTheme.colorScheme.onSurfaceVariant
}
