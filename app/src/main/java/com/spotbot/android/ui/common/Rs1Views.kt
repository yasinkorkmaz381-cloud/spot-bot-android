package com.spotbot.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.rs1.BtcState
import com.spotbot.core.rs1.Rs1Position
import com.spotbot.core.rs1.Rs1Trade
import com.spotbot.core.rs1.WeeklyDecision

fun coin(symbol: String) = symbol.removeSuffix("USDT")

/** BTC piyasa filtresi kartının içeriği. */
@Composable
fun BtcFilterView(btc: BtcState?) {
    val colors = SpotBotTheme.tradingColors
    if (btc == null) {
        Text("BTC verisi henüz yok.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    StatusPill(
        when {
            btc.dataMissing -> "Veri yok: yeni alım engelli"
            btc.open -> "Alıma izin veriyor"
            else -> "Yeni alım yok"
        },
        if (btc.open) colors.gain else if (btc.dataMissing) MaterialTheme.colorScheme.tertiary else colors.loss,
    )
    if (!btc.dataMissing) {
        KeyValueRow("Günlük kapanış / EMA100", "${formatPrice(btc.close)} / ${formatPrice(btc.ema100)}", gainLossColor((btc.close - btc.ema100).compareTo(0.0)))
        KeyValueRow("30 günlük getiri", formatPercent(btc.return30Percent, signed = true, minFractionDigits = 1), gainLossColor(btc.return30Percent.compareTo(0.0)))
    }
    btc.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Açık sanal pozisyon satırı. */
@Composable
fun PositionView(p: Rs1Position) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(coin(p.symbol), style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatSignedMoney(p.unrealizedPnl)} USDT", style = MaterialTheme.typography.titleMedium, color = gainLossColor(p.unrealizedPnl.compareTo(0.0)),
                modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1,
            )
        }
        MetricGrid(
            listOf(
                Triple("Alış", formatPrice(p.entryPrice), Color.Unspecified),
                Triple("Son", formatPrice(p.lastPrice), Color.Unspecified),
                Triple("Stop", formatPrice(p.stop), SpotBotTheme.tradingColors.loss),
                Triple("Tutar", "${formatMoney(p.entryPrice * p.quantity)} USDT", Color.Unspecified),
            ),
        )
        Text(
            "Giriş ${formatDateTime(p.entryTime)} · başlangıç stopu ${formatPrice(p.initialStop)}" + if (p.stop > p.initialStop) " · iz süren stop yükseldi" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Kapanmış işlem satırı. [recordedAt] uygulamanın işlemi ilk gördüğü an (geç kayıt). */
@Composable
fun TradeRow(t: Rs1Trade, recordedAt: Long? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(coin(t.symbol), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${formatSignedMoney(t.netPnl)} USDT · %+.2fR".format(t.r), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = gainLossColor(t.netPnl.compareTo(0.0)))
        }
        Text(
            "${formatDateTime(t.entryTime)} → ${formatDateTime(t.exitTime)} · ${formatPrice(t.entryPrice)} → ${formatPrice(t.exitPrice)} · ${t.reason.title}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (recordedAt != null && recordedAt - t.exitTime > 2 * 86_400_000L) {
            Text("Uygulama bu çıkışı ${formatDateTime(recordedAt)}'te kaydetti (o sırada çalışmıyordu).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Haftalık değerlendirmenin özeti: neden işlem açıldı ya da açılmadı. */
@Composable
fun WeeklyDecisionView(w: WeeklyDecision) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(formatDateTime(w.time) + " · BTC filtresi " + if (w.btc.open) "açık" else "kapalı", style = MaterialTheme.typography.titleSmall)
        when {
            w.entered.isNotEmpty() -> Text("Alındı: ${w.entered.joinToString { coin(it) }}", style = MaterialTheme.typography.bodySmall, color = SpotBotTheme.tradingColors.gain)
            else -> Text("İşlem açılmadı: ${w.noEntryReason ?: "-"}", style = MaterialTheme.typography.bodySmall)
        }
        if (w.exited.isNotEmpty()) Text("Çıkılan: ${w.exited.joinToString { coin(it) }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (w.ranked.isNotEmpty()) {
            Text("Uygun ${w.eligible}: " + w.ranked.take(5).joinToString { "${coin(it.symbol)} %+.0f%%".format(it.return30Percent) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        w.liveRejections.forEach { (s, f) -> Text("${coin(s)}: ${f.joinToString { it.title }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Android arka plan kısıtı uyarısı (kesintisiz çalışma garanti edilemez). */
@Composable
fun BackgroundNotice(longestGapMinutes: Long = 0) {
    InfoBanner(
        "Android pil optimizasyonu servisi geciktirebilir ya da durdurabilir; kesintisiz çalışma garanti edilemez. " +
            "Haftalık giriş için uygulamanın pazartesi 00:15–04:00 UTC arasında çalışıyor olması gerekir; çalışmazsa o hafta yeni işlem açılmaz. " +
            "Stop ve çıkışlar MEXC verisinden hesaplandığı için kaçmaz, ancak geç kaydedilebilir." +
            if (longestGapMinutes > 30) " Son çalışmada döngüler arasında $longestGapMinutes dakikalık boşluk görüldü." else "",
    )
}
