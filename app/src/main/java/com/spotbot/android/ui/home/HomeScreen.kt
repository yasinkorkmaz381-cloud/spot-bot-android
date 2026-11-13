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
) {
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
            KeyValueRow("Son başarılı fiyat verisi", if (state.lastPriceDataAt > 0) "${formatTime(state.lastPriceDataAt)} TSİ" else "Henüz yok")
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
            state.scan?.let { KeyValueRow("Son tarama", "${formatTime(it.barClose)} kapanışı · ${it.scanned} coin") }
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
