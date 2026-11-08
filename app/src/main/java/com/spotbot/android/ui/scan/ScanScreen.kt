package com.spotbot.android.ui.scan

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.formatCompact
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.scanner.CoinScan
import com.spotbot.core.scanner.CoinStatus
import com.spotbot.core.scanner.ScanReport

class ScanViewModel(engine: TradingEngine) : ViewModel() {
    val report = engine.lastScan
    val health = engine.health
}

@Composable
fun ScanScreen(onOpenSymbol: (String) -> Unit, modifier: Modifier = Modifier, viewModel: ScanViewModel = viewModel(factory = AppViewModels.Factory)) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    ScanContent(report, onOpenSymbol, modifier)
}

@Composable
fun ScanContent(r: ScanReport?, onOpenSymbol: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (r == null) {
            item { InfoBanner("Henüz tarama yok. Botu başlattığınızda ilk tarama hemen, sonrakiler her 15M mum kapanışında yapılır.") }
            return@LazyColumn
        }
        item {
            val market = r.market
            SectionCard(
                "Piyasa filtresi",
                trailing = { Tag(if (market.allowsLongs) "ALIMA UYGUN" else "ALIM YOK", if (market.allowsLongs) SpotBotTheme.tradingColors.gain else SpotBotTheme.tradingColors.loss) },
            ) {
                KeyValueRow("Tarama zamanı", formatTime(r.time))
                KeyValueRow("BTC 4H rejimi", market.btcRegime?.name ?: "—")
                market.btcChange4hPercent?.let { KeyValueRow("BTC son 4 saat", formatPercent(it, signed = true, minFractionDigits = 2), gainLossColor(it.compareTo(0.0))) }
                market.breadthPercent?.let { KeyValueRow("4H EMA55 üzerindeki coinler", formatPercent(it)) }
                market.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            SectionCard("Ayrıntılı incelenen adaylar") {
                if (r.candidates.isEmpty()) EmptyText("Uygun aday yok: bu taramada işlem açılmaz.")
                else Text(r.candidates.joinToString(" · "), fontWeight = FontWeight.SemiBold)
            }
        }
        item { Text("Tüm coinler (${r.coins.size})", style = MaterialTheme.typography.titleMedium) }
        items(r.coins, key = { it.symbol }) { coin -> CoinRow(coin) { onOpenSymbol(coin.symbol) } }
    }
}

@Composable
private fun CoinRow(coin: CoinScan, onClick: () -> Unit) {
    val colors = SpotBotTheme.tradingColors
    val (label, color) = when (coin.status) {
        CoinStatus.CANDIDATE -> "ADAY" to colors.gain
        CoinStatus.PASSED -> "GEÇTİ" to MaterialTheme.colorScheme.primary
        CoinStatus.FLAGGED -> "RİSKLİ" to MaterialTheme.colorScheme.tertiary
        CoinStatus.ELIMINATED -> "ELENDİ" to colors.loss
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(coin.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Tag(label, color)
                Text("Puan ${coin.preScore}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(formatPercent(coin.change24hPercent, signed = true, minFractionDigits = 2), color = gainLossColor(coin.change24hPercent.compareTo(0.0)))
            }
            Text(
                "Fiyat ${formatPrice(coin.price)} · Hacim ${formatCompact(coin.quoteVolume24h)} USDT · Spread ${coin.spreadPercent?.let { formatPercent(it, minFractionDigits = 3) } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "4H ${coin.h4Regime ?: "—"} · 1H yapı ${coin.h1Trend ?: "—"}" +
                    (coin.relativeStrength7dPercent?.let { " · BTC'ye göre ${formatPercent(it, signed = true)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            coin.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = color) }
        }
    }
}
