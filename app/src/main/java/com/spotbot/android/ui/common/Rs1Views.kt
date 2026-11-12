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

/** Açık sanal pozisyon satırı. [untracked]: giriş, uygulamanın çalışmadığı bir aralıkta oldu. */
@Composable
fun PositionView(p: Rs1Position, untracked: Boolean = false) {
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
            "Giriş ${formatDateTime(p.entryTime)} TSİ · başlangıç stopu ${formatPrice(p.initialStop)}" +
                if (p.stopChanges.size > 1) " · stop ${p.stopChanges.size - 1} kez yükseldi (son: ${formatDateTime(p.stopChanges.last().time)})" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (untracked) Text("Giriş, uygulamanın çalışmadığı bir aralıkta gerçekleşti; canlı izlenmedi, sonradan hesaplandı.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

/**
 * Kapanmış işlem satırı: giriş, stop değişiklikleri ve çıkış gerekçesi. [untracked]: çıkış, uygulamanın çalışmadığı bir
 * aralıkta oldu (canlı izlenmedi); [recordedAt]: uygulamanın işlemi ilk gördüğü an.
 */
@Composable
fun TradeRow(t: Rs1Trade, recordedAt: Long? = null, untracked: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(coin(t.symbol), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${formatSignedMoney(t.netPnl)} USDT · %+.2fR".format(t.r), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = gainLossColor(t.netPnl.compareTo(0.0)))
        }
        Text(
            "${formatDateTime(t.entryTime)} → ${formatDateTime(t.exitTime)} · ${formatPrice(t.entryPrice)} → ${formatPrice(t.exitPrice)} · ${t.reason.title}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Stop: başlangıç ${formatPrice(t.initialStop)}" + (if (t.stopChanges.size > 1) " → ${formatPrice(t.stopChanges.last().stop)} (${t.stopChanges.size - 1} yükselme)" else "") +
                (if (t.intradayResolved) " · stop gün içi mumla çözüldü" else "") + " · maliyet ${formatMoney(t.fees + t.friction)} USDT",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (untracked || (recordedAt != null && recordedAt - t.exitTime > 2 * 86_400_000L)) {
            Text(
                "Canlı izlenmedi: uygulama o sırada çalışmıyordu; çıkış MEXC verisinden sonradan hesaplandı" + (recordedAt?.let { " (kayıt ${formatDateTime(it)})" } ?: "") + ".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
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

/**
 * Kanıt durumu: yalnız ön kayıtlı ölçütlerden (Rs1Evidence) ve araştırma sonucundan gelir; "kârlı" ya da "onaylı" gibi
 * etiketler kanıtsız gösterilmez.
 */
@Composable
fun EvidenceView(e: com.spotbot.core.rs1.Evidence?, title: String) {
    val colors = SpotBotTheme.tradingColors
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (e == null) {
        Text("Henüz kapanmış işlem yok.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    StatusPill(
        e.classification.title,
        when (e.classification) {
            com.spotbot.core.rs1.EvidenceClass.EVIDENCE -> colors.gain
            com.spotbot.core.rs1.EvidenceClass.FAILED -> colors.loss
            com.spotbot.core.rs1.EvidenceClass.INSUFFICIENT -> MaterialTheme.colorScheme.tertiary
        },
    )
    e.checks.forEach { (k, ok) -> Text("${if (ok) "✓" else "✗"} $k", style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** V6 araştırmasının sonucu (docs/RS1-V6.md): uygulamada sabit, kanıta dayalı açıklama. */
object ResearchVerdict {
    const val TITLE = "Kanıtlanmış avantaj yok"
    const val TEXT = "Bağımsız test (2018–2019): 5 işlem, yetersiz kanıt. Daha önce görülmüş 2025–2026 döneminde zarar (−0,44R, PF 0,30). " +
        "Paper trading ileriye dönük veri toplamak içindir; kâr beklentisi kanıtlanmamıştır."
}
