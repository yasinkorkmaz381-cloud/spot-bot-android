package com.spotbot.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.MetricTile
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.android.LiveStatus
import com.spotbot.core.kr.BotMode
import com.spotbot.core.kr.KrPaper
import com.spotbot.core.kr.KrState
import kotlin.math.sign

/** Ana ekran: bakiye, bugünkü K/Z, açık pozisyon, bot kontrolü, son veri zamanı ve kısa durum. */
@Composable
fun HomeContent(
    state: KrState,
    mode: BotMode,
    keyPresent: Boolean,
    now: Long,
    onMode: (BotMode) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit = {},
    live: LiveStatus = LiveStatus(),
) {
    val status = homeStatus(state, mode, live, now)
    val open = state.book.positions.size
    var confirmOff by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.cards),
    ) {
        if (mode == BotMode.OFF && open > 0) {
            InfoBanner(
                "Bot tamamen kapalı: $open açık pozisyonun stop ve hedefi izlenmiyor. Korumayı açmak için \"Yalnız koruma\" seçin.",
                containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag("unprotected"),
            )
        }
        status.protectionWarning?.let {
            InfoBanner(it, containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("protection-warning"))
        }
        if (!keyPresent) {
            AppCard {
                Text("CMC API anahtarı girilmemiş", style = MaterialTheme.typography.titleMedium)
                Text("Coin evreni (CoinMarketCap ilk 300) alınamaz; yeni giriş yapılmaz.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onOpenSettings) { Text("Anahtarı gir") }
            }
        }
        AppCard {
            Text("Sanal bakiye", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatMoney(state.equity)} USDT", style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val today = state.todayPnl(now)
                MetricTile("Bugün net", "${formatSignedMoney(today)} USDT", Modifier.weight(1f), gainLossColor(today.sign.toInt()))
                MetricTile("Açık pozisyon", "$open / ${4}", Modifier.weight(1f))
            }
        }
        AppCard {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Bot", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(mode.title, modeColor(mode), Modifier.testTag("mode"))
            }
            Text(mode.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (mode) {
                BotMode.OFF -> {
                    Button(onClick = { onMode(BotMode.FULL) }, modifier = Modifier.fillMaxWidth()) { Text("Botu başlat") }
                    if (open > 0) OutlinedButton(onClick = { onMode(BotMode.PROTECT_ONLY) }, modifier = Modifier.fillMaxWidth()) { Text("Yalnız koruma (yeni giriş yok)") }
                }
                BotMode.FULL -> {
                    OutlinedButton(onClick = { onMode(BotMode.PROTECT_ONLY) }, modifier = Modifier.fillMaxWidth()) { Text("Yeni girişleri durdur") }
                    TextButton(onClick = { if (open > 0) confirmOff = true else onMode(BotMode.OFF) }, modifier = Modifier.fillMaxWidth()) { Text("Tamamen kapat") }
                }
                BotMode.PROTECT_ONLY -> {
                    Button(onClick = { onMode(BotMode.FULL) }, modifier = Modifier.fillMaxWidth()) { Text("Yeni girişleri aç") }
                    TextButton(onClick = { if (open > 0) confirmOff = true else onMode(BotMode.OFF) }, modifier = Modifier.fillMaxWidth()) { Text("Tamamen kapat") }
                }
            }
        }
        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth().testTag("open-history")) { Text("Geçmiş test") }
        AppCard {
            Text("Durum", style = MaterialTheme.typography.titleMedium)
            KeyValueRow("Son tarama", status.lastScan, valueColor = if (status.scanProblem) MaterialTheme.colorScheme.error else Color.Unspecified)
            KeyValueRow("Sonraki tarama", status.nextScan, valueColor = if (status.serviceDown) MaterialTheme.colorScheme.error else Color.Unspecified)
            KeyValueRow("Bağlantı", status.connection)
            KeyValueRow("Fiyat akışı", status.stream)
            if (state.book.positions.isNotEmpty()) KeyValueRow("Koruma", status.protection)
            val u = state.universe
            val listAge = u?.let { now - it.list.cmcTimestamp }
            KeyValueRow(
                "CMC listesi",
                when {
                    u == null -> "Alınmadı"
                    listAge!! > KrPaper.LIST_VALIDITY -> "${formatDateTime(u.list.cmcTimestamp)} · bayat, yeni giriş durdu"
                    else -> formatDateTime(u.list.cmcTimestamp)
                },
                valueColor = if (u == null || listAge!! > KrPaper.LIST_VALIDITY) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            state.scan?.let { KeyValueRow("Değerlendirilen 15M kapanışı", "${formatTime(it.barClose)} · ${it.scanned} coin") }
            val gaps = state.untracked.count { now - it.to < 24 * 3_600_000L }
            if (gaps > 0) KeyValueRow("Son 24 saatte izleme boşluğu", "$gaps kez", valueColor = MaterialTheme.colorScheme.error)
            Text(shortStatus(state, mode, keyPresent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("short-status"))
        }
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Tüm takip kapatılsın mı?") },
            text = { Text("$open açık pozisyonun stop ve hedefi izlenmeyecek. Yeni girişleri durdurup korumayı sürdürmek için \"Yeni girişleri durdur\"u kullanın.") },
            confirmButton = { TextButton(onClick = { confirmOff = false; onMode(BotMode.OFF) }) { Text("Yine de kapat") } },
            dismissButton = { TextButton(onClick = { confirmOff = false; onMode(BotMode.PROTECT_ONLY) }) { Text("Yalnız yeni girişleri durdur") } },
        )
    }
}

fun shortStatus(state: KrState, mode: BotMode, keyPresent: Boolean): String {
    val scan = state.scan
    return when {
    state.lastError != null -> "Uyarı: ${state.lastError}"
    mode == BotMode.OFF -> "Bot kapalı."
    !keyPresent -> "CMC anahtarı yok: evren alınamıyor."
    state.universeError != null -> "CMC listesi alınamadı: ${state.universeError}"
    mode == BotMode.PROTECT_ONLY -> "Yeni giriş yok; açık pozisyonlar korunuyor."
    scan == null -> "İlk tarama bekleniyor (15 dakikalık kapanışlarda)."
    else -> scan.items.count { it.stage == com.spotbot.core.kr.SetupStage.AWAITING_RETEST }.let { "Tarama sürüyor; $it coinde retest bekleniyor." }
    }
}

@Composable
private fun modeColor(mode: BotMode): Color = when (mode) {
    BotMode.FULL -> SpotBotTheme.tradingColors.gain
    BotMode.PROTECT_ONLY -> MaterialTheme.colorScheme.tertiary
    BotMode.OFF -> MaterialTheme.colorScheme.error
}

/** Ana ekrandaki tarama/bağlantı/koruma satırları (saf fonksiyon; testlerde doğrulanır). */
data class HomeStatus(
    val lastScan: String,
    val nextScan: String,
    val connection: String,
    val stream: String,
    val protection: String,
    val protectionWarning: String?,
    val serviceDown: Boolean,
    val scanProblem: Boolean,
)

private fun clock(t: Long) = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(com.spotbot.android.ui.common.TURKEY).format(java.time.Instant.ofEpochMilli(t))
private fun ago(now: Long, t: Long): String { val s = (now - t) / 1000; return if (s < 90) "$s sn önce" else "${s / 60} dk önce" }

fun homeStatus(state: KrState, mode: BotMode, live: LiveStatus, now: Long): HomeStatus {
    val t = state.scanTiming
    val serviceUp = live.serviceAliveAt > 0 && now - live.serviceAliveAt < 90_000
    val open = state.book.positions.size
    val lastScan = when {
        t.lastSuccessAt == 0L && t.lastError == null -> "Henüz yok"
        t.lastError != null && t.lastFinishedAt >= t.lastSuccessAt -> "Hatalı (${clock(t.lastFinishedAt)}): ${t.lastError}" + if (t.lastSuccessAt > 0) " · son başarılı ${clock(t.lastSuccessAt)}" else ""
        else -> "${clock(t.lastSuccessAt)} (${ago(now, t.lastSuccessAt)}) · ${"%.1f".format(t.lastDurationMs / 1000.0)} sn · gecikme ${"%.1f".format(t.lastDelayMs / 1000.0)} sn"
    } + if (t.skippedOverlap > 0) " · çakıştığı için atlanan ${t.skippedOverlap}" else ""
    val nextScan = when {
        mode == BotMode.OFF -> "Tarama kapalı"
        mode == BotMode.PROTECT_ONLY -> "Yeni girişler durdu: tarama yapılmıyor"
        !serviceUp -> "Servis çalışmıyor: tarama yapılmıyor"
        live.nextScanAt > 0 -> "${clock(live.nextScanAt)} (her 3 dakikada bir)"
        else -> "Planlanıyor"
    }
    val lastData = maxOf(state.lastPriceDataAt, t.lastSuccessAt, state.protection.lastCheckAt)
    val connection = if (lastData > 0) "MEXC: son başarılı veri ${clock(lastData)} (${ago(now, lastData)})" else "MEXC: henüz veri yok"
    val st = live.stream
    val stream = when {
        open == 0 -> "Gerekmiyor (açık pozisyon yok)"
        !serviceUp -> "Kapalı (servis çalışmıyor)"
        st.status == com.spotbot.core.exchange.mexc.ConnectionStatus.CONNECTED -> "Bağlı" + (st.lastMessageAt?.let { " · son fiyat ${ago(now, it)}" } ?: "")
        st.status == com.spotbot.core.exchange.mexc.ConnectionStatus.STOPPED -> "Kapalı; koruma REST ile"
        else -> "Yeniden bağlanıyor; koruma REST ile" + (st.lastError?.let { " ($it)" } ?: "")
    }
    val p = state.protection
    val stale = open > 0 && (!serviceUp || mode == BotMode.OFF || p.lastCheckAt == 0L || now - p.lastCheckAt > 60_000)
    val protection = if (p.lastCheckAt == 0L) "Henüz denetlenmedi" else "Son denetim ${clock(p.lastCheckAt)} (${ago(now, p.lastCheckAt)}) · ${p.lastPriceSource.ifBlank { "—" }}"
    val warning = if (stale && mode != BotMode.OFF) "Koruma izlenemiyor: $open açık pozisyonun stopu şu an denetlenmiyor" +
        (if (p.lastCheckAt > 0) " (son denetim ${clock(p.lastCheckAt)})" else "") + (if (!serviceUp) ". Servis çalışmıyor." else ". Bağlantı yok ya da gecikiyor.") else null
    return HomeStatus(lastScan, nextScan, connection, stream, protection, warning, serviceDown = mode != BotMode.OFF && !serviceUp, scanProblem = t.lastError != null && t.lastFinishedAt >= t.lastSuccessAt)
}
