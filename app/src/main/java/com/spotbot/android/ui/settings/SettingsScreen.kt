package com.spotbot.android.ui.settings

import android.content.Intent
import android.annotation.SuppressLint
import android.content.Context
import androidx.core.net.toUri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.TURKEY
import com.spotbot.android.ui.common.formatDateTime
import androidx.compose.ui.platform.testTag
import com.spotbot.android.ui.KeyStatus
import com.spotbot.core.kr.EventWindow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val INPUT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** Kullanıcının yazdığı TSİ zamanını ayrıştırır ("14.11.2026 21:00"). */
fun parseTurkeyTime(text: String): Long? = runCatching {
    LocalDateTime.parse(text.trim(), INPUT).atZone(TURKEY).toInstant().toEpochMilli()
}.getOrNull()

@Composable
fun SettingsContent(
    keyPresent: Boolean,
    events: List<EventWindow>,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onAddEvent: (Long, String) -> Unit,
    onRemoveEvent: (EventWindow) -> Unit,
    onReset: () -> Unit,
    onOpenArchive: () -> Unit,
    keyStatus: KeyStatus? = null,
    keyUnreadable: Boolean = false,
    onRefreshList: () -> Unit = {},
    onClearCache: () -> Unit = {},
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var eventTime by remember { mutableStateOf("") }
    var eventTitle by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.cards),
    ) {
        SectionCard("CoinMarketCap API anahtarı") {
            Text(
                if (keyPresent) "Anahtar kayıtlı (cihazda şifreli; bir daha gösterilmez)." else "Coin evreni CMC'nin resmî API'sinden alınır. Ücretsiz Basic plan anahtarı yeterlidir.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true, label = { Text(if (keyPresent) "Yeni anahtar" else "API anahtarı") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (keyUnreadable) Text("Kayıtlı anahtar bu cihazda okunamadı (uygulama yeniden kurulmuş olabilir). Lütfen yeniden girin.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSaveKey(key); key = "" }, enabled = key.isNotBlank(), modifier = Modifier.testTag("save-key")) { Text("Kaydet ve doğrula") }
                if (keyPresent) OutlinedButton(onClick = onClearKey) { Text("Sil") }
            }
            if (keyPresent) TextButton(onClick = onRefreshList) { Text("Coin listesini şimdi yenile") }
            keyStatus?.let { st ->
                Text(
                    st.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("key-status"),
                    color = when (st.kind) { KeyStatus.Kind.OK -> com.spotbot.android.ui.theme.SpotBotTheme.tradingColors.gain; KeyStatus.Kind.ERROR -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                )
            }
        }
        SectionCard("Makro olay pencereleri") {
            Text(
                "Uygulamada doğrulanmış makro takvim kaynağı yok. Bildiğiniz olayları (ör. FOMC) TSİ olarak girin: olaydan 30 dk önce – 60 dk sonra yeni giriş yapılmaz.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(eventTime, { eventTime = it }, singleLine = true, label = { Text("Zaman (GG.AA.YYYY SS:DD)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(eventTitle, { eventTitle = it }, singleLine = true, label = { Text("Başlık") }, modifier = Modifier.fillMaxWidth())
            val parsed = parseTurkeyTime(eventTime)
            Button(onClick = { parsed?.let { onAddEvent(it, eventTitle); eventTime = ""; eventTitle = "" } }, enabled = parsed != null) { Text("Ekle") }
            if (events.isEmpty()) EmptyText("Olay yok.")
            events.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatDateTime(e.time)} · ${e.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRemoveEvent(e) }) { Text("Kaldır") }
                }
            }
        }
        SectionCard("Arka planda çalışma") {
            Text("Android pil tasarrufu servisi durdurabilir. Bot çalışmadığı aralıklar kaydedilir ve Ayrıntılar'da gösterilir.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { requestBatteryExemption(context) }) { Text("Pil optimizasyonu muafiyeti iste") }
        }
        SectionCard("Sanal hesap") {
            InfoBanner("Bu sürüm yalnız sanal (paper) işlem yapar; gerçek emir gönderen kod yoktur.")
            OutlinedButton(onClick = { confirmReset = true }) { Text("Sanal hesabı sıfırla") }
            TextButton(onClick = onClearCache) { Text("Geçmiş test mum önbelleğini temizle") }
            TextButton(onClick = onOpenArchive) { Text("Arşivi aç") }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Sanal hesap sıfırlansın mı?") },
            text = { Text("Mevcut kayıt silinmez, arşive taşınır. Bakiye 1.000 USDT'ye döner.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Sıfırla") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Vazgeç") } },
        )
    }
}

/** Kişisel kullanım uygulaması (Play Store'da yayımlanmıyor): 7/24 koruma takibi için kullanıcının açık onayıyla istenir. */
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())) }
}
