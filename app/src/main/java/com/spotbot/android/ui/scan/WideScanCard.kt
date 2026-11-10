package com.spotbot.android.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.core.scanner.WideCoin
import com.spotbot.core.scanner.WideScanState

/** Görünen kısa sektör adı (MEXC etiketinden). */
private fun sectorLabel(c: WideCoin): String? = c.sectors.firstNotNullOfOrNull {
    when (it) {
        "LAYER 1 / LAYER 2" -> "L1/L2"; "MEME" -> "Meme"; "AI", "AI Agent" -> "YZ"; "DeFi" -> "DeFi"; "DePIN", "Storage", "ZK" -> "Altyapı"
        "RWA" -> "RWA"; "GameFi" -> "Oyun"; "Privacy" -> "Gizlilik"; else -> null
    }
} ?: if ("Main" in c.sectors) "Büyük" else null

/**
 * V4 geniş evren taraması (S1 kurallarıyla): bütün MEXC USDT paritelerinden aday seçimine kadar huni, en güçlü 20 ve
 * elenme nedenleri. Spread, derinlik ve korelasyon geçmişte ölçülemediği için yalnız uyarıdır.
 */
@Composable
fun WideScanCard(state: WideScanState?, busy: Boolean, onRefresh: () -> Unit, initiallyExpanded: Boolean = false) {
    val colors = SpotBotTheme.tradingColors
    val r = state?.result
    val subtitle = when {
        busy && r == null -> "İlk tarama yapılıyor…"
        state?.lastError != null -> "Yazılım / veri sorunu"
        r == null -> "Henüz tarama yok"
        else -> "${r.totalPairs} parite tarandı · ${r.candidates.size} aday"
    }
    ExpandableCard("Geniş evren taraması (S1)", subtitle = subtitle, initiallyExpanded = initiallyExpanded) {
        state?.lastError?.let {
            StatusPill("Yazılım / veri sorunu", colors.loss)
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        if (r != null) {
            if (state.lastError == null) {
                StatusPill("Yazılım çalışıyor", colors.gain)
                Text(
                    if (r.candidates.isEmpty()) "Hiçbir parite S1 kurallarını geçmedi; işlem açılmaması kuralların kararıdır."
                    else "Adaylar S1 ileri test portföyünde değerlendirilir (en fazla 3 pozisyon).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            KeyValueRow("Son tarama", formatDateTime(r.time))
            KeyValueRow("Taranan toplam USDT paritesi", "${r.totalPairs}")
            KeyValueRow("Desteklenmeyen ürün", "${r.productExcluded}")
            KeyValueRow("İşlem kapalı / risk etiketli", "${r.notTradable}")
            KeyValueRow("Likidite filtresini geçen", "${r.liquidityPassed}")
            KeyValueRow("Trend filtresini geçen", "${r.trendPassed}")
            KeyValueRow("BTC'den güçlü (sıralamada)", "${r.strengthPassed}")
            KeyValueRow("İşleme uygun aday", if (r.candidates.isEmpty()) "yok" else r.candidates.joinToString { it.removeSuffix("USDT") }, if (r.candidates.isNotEmpty()) colors.gain else Color.Unspecified)
            KeyValueRow("BTC 30 günlük getiri", "%+.1f%%".format(r.btcMomentum30Percent))
            if (r.newListings.isNotEmpty()) KeyValueRow("Yeni listelenen", r.newListings.take(6).joinToString { it.removeSuffix("USDT") })
            if (r.delisted.isNotEmpty()) KeyValueRow("Listeden çıkan", r.delisted.take(6).joinToString { it.removeSuffix("USDT") }, colors.loss)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("En güçlü ${r.top.size} (BTC'ye göre 30 gün)", style = MaterialTheme.typography.titleSmall)
            if (r.top.isEmpty()) Text("Kuralları geçen parite yok.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.top.forEach { c -> WideRow(c) }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Elenme nedenleri", style = MaterialTheme.typography.titleSmall)
            r.rejections.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("$count", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
            if (r.strongestRejected.isNotEmpty()) {
                Text("Güçlü ama elenenler", style = MaterialTheme.typography.titleSmall)
                r.strongestRejected.take(5).forEach { c ->
                    Text(
                        "${c.symbol.removeSuffix("USDT")} (30g %+.0f%%): ${c.rejection?.title ?: ""}".format(c.momentum30Percent),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Spread, emir defteri derinliği ve korelasyon geçmişte ölçülemediği için uyarı olarak gösterilir; S1 kuralı değildir.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRefresh, enabled = !busy) { Text(if (busy) "Taranıyor…" else "Şimdi tara") }
    }
}

@Composable
private fun WideRow(c: WideCoin) {
    val colors = SpotBotTheme.tradingColors
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.rank?.toString() ?: "–", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleSmall)
            if (c.candidate) Tag("Aday", colors.gain)
            sectorLabel(c)?.let { Tag(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                "RS %+.1f".format(c.relativeStrength), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f), color = if (c.relativeStrength > 0) colors.gain else Color.Unspecified,
            )
        }
        Text(
            "7g %+.1f%% · 30g %+.1f%% · hacim %.1fM · stop %%%.0f · spread %%%.2f".format(
                c.momentum7Percent, c.momentum30Percent, c.medianVolume30 / 1e6, c.stopDistancePercent ?: Double.NaN, c.spreadPercent,
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        c.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    }
}
