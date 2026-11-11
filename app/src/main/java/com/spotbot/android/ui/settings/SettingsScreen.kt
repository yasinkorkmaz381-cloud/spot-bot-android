package com.spotbot.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.AppContainer
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.BotController
import com.spotbot.android.ui.common.BackgroundNotice
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Spacing
import com.spotbot.core.rs1.Rs1Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer, private val controller: BotController) : ViewModel() {
    val settings = container.settings.settings
    val config = container.config
    fun setBot(on: Boolean) = if (on) controller.start() else controller.stop()
    fun resetPaper() { viewModelScope.launch(Dispatchers.IO) { container.resetPaper() } }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = viewModel(factory = AppViewModels.Factory)) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(settings.botEnabled, viewModel.config, viewModel::setBot, viewModel::resetPaper, modifier)
}

@Composable
fun SettingsContent(botEnabled: Boolean, cfg: Rs1Config, onBot: (Boolean) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        item {
            SectionCard("Bot", trailing = { Switch(checked = botEnabled, onCheckedChange = onBot) }) {
                Text("Yalnız sanal işlem (paper trading). Gerçek emir gönderimi yok; API anahtarı istenmez.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard("Strateji: Momentum RS-1") {
                Text("Uygulamada tek strateji vardır. Parametreler ilk sürüm araştırma değerleridir; verilerle doğrulanmadan değiştirilmez.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                KeyValueRow("BTC filtresi", "Kapanış > EMA${cfg.emaTrend} ve ${cfg.returnDays} günlük getiri > 0")
                KeyValueRow("Giriş", "Pazartesi 00:15 UTC, en güçlü ${cfg.candidates} uygun coin")
            }
        }
        item {
            ExpandableCard("Uygunluk şartları") {
                KeyValueRow("Trend", "Kapanış > EMA${cfg.emaTrend} (1D)")
                KeyValueRow("Getiri", "${cfg.shortReturnDays} ve ${cfg.returnDays} günlük getiri > 0")
                KeyValueRow("BTC'ye göre", "≥ ${"%.0f".format(cfg.minRelativeStrengthPoints)} puan (BTC muaf)")
                KeyValueRow("Spread", "≤ %${cfg.maxSpreadPercent}".replace('.', ','))
                KeyValueRow("Derinlik", "±%${cfg.depthBandPercent.toInt()} içinde ≥ ${cfg.depthMultiple.toInt()} × en büyük pozisyon")
                KeyValueRow("Hacim", "30 gün medyan ≥ ${(cfg.minMedianVolume / 1e6).toInt()}M USDT")
                KeyValueRow("Son gün aralığı", "≤ ${cfg.maxRangeAtr} ATR".replace('.', ','))
                KeyValueRow("Geçmiş", "≥ ${cfg.minHistoryDays} gün, son ${cfg.gapCheckDays} günde eksik yok")
            }
        }
        item {
            ExpandableCard("Risk ve çıkış") {
                KeyValueRow("Sermaye", "${cfg.capital.toInt()} USDT")
                KeyValueRow("İşlem başına risk", "%${cfg.riskPercent}".replace('.', ','))
                KeyValueRow("En fazla pozisyon", "${cfg.maxPositions}")
                KeyValueRow("Coin başına / toplam", "%${cfg.maxPositionPercent.toInt()} / %${cfg.maxTotalPercent.toInt()}")
                KeyValueRow("Günlük kayıp sınırı", "%${cfg.dailyLossPercent}".replace('.', ','))
                KeyValueRow("Başlangıç stopu", "${cfg.initialStopAtr} × ATR14".replace('.', ','))
                KeyValueRow("İz süren stop", "En yüksek kapanış − ${cfg.trailAtr} × ATR14".replace('.', ','))
                KeyValueRow("Diğer çıkışlar", "BTC filtresi kapanır · 2 gün EMA${cfg.emaExit} altı · haftalık uygunluk kaybı")
                KeyValueRow("Kaldıraç / açığa satış / ek alım", "Yok")
            }
        }
        item {
            SectionCard("Sanal hesap") {
                Text("Sıfırlama, mevcut kaydı silmez: arşive taşır ve paper trading'i bugünden yeniden başlatır.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirmReset = true }) { Text("Sanal hesabı sıfırla") }
            }
        }
        item { BackgroundNotice() }
        item { InfoBanner("Eski A/B/C stratejileri kaldırıldı. Geçmiş işlemleri İşlemler → Arşiv'de salt okunur olarak görebilirsiniz.") }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Sanal hesap sıfırlansın mı?") },
            text = { Text("Mevcut paper trading kaydı arşivlenir ve bugünden yeni bir 1.000 USDT sanal hesap başlar.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Sıfırla") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Vazgeç") } },
        )
    }
}
