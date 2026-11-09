package com.spotbot.android.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.BuildConfig
import com.spotbot.android.data.SettingsRepository
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.formatEditable
import com.spotbot.android.ui.common.parseDecimal
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.rules.BuiltInRules
import com.spotbot.core.rules.RuleGroup
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.EntryMode
import com.spotbot.core.trading.StrategyProfile
import com.spotbot.core.risk.AtrStopMode
import com.spotbot.core.trading.ExecutionConfig

class SettingsViewModel(private val engine: TradingEngine, private val repository: SettingsRepository) : ViewModel() {
    val settings = repository.settings
    val ruleEngine get() = engine.ruleEngine

    var riskPerTrade by mutableStateOf(formatEditable(settings.value.engine.risk.riskPerTradePercent))
    var dailyLoss by mutableStateOf(formatEditable(settings.value.engine.risk.dailyLossLimitPercent))
    var maxPositions by mutableStateOf(settings.value.engine.risk.maxOpenPositions.toString())
    var minRr by mutableStateOf(formatEditable(settings.value.engine.risk.minNetRiskReward))
    var maxPositionPct by mutableStateOf(formatEditable(settings.value.engine.risk.maxPositionPercentOfEquity))
    var extraCoins by mutableStateOf(settings.value.engine.scanner.extraCoins.toString())
    var minVolume by mutableStateOf(formatEditable(settings.value.engine.scanner.minQuoteVolume24h))
    var capital by mutableStateOf(formatEditable(settings.value.engine.initialCapital))
    var totalRiskCap by mutableStateOf(formatEditable(settings.value.engine.risk.maxTotalOpenRiskPercent))
    var atrMultiple by mutableStateOf(formatEditable(settings.value.engine.risk.atrStopMultiple))
    var message by mutableStateOf<String?>(null)

    fun saveRisk() {
        val risk = parseDecimal(riskPerTrade)
        val daily = parseDecimal(dailyLoss)
        val max = maxPositions.trim().toIntOrNull()
        val rr = parseDecimal(minRr)
        val cap = parseDecimal(maxPositionPct)
        val extras = extraCoins.trim().toIntOrNull()
        val volume = parseDecimal(minVolume)
        val totalCap = parseDecimal(totalRiskCap)
        val atrK = parseDecimal(atrMultiple)
        if (totalCap == null || totalCap <= 0 || totalCap > 20 || atrK == null || atrK <= 0 || atrK > 10) {
            message = "Geçersiz değer: toplam risk sınırı %0–20, ATR çarpanı 0–10 olmalı."
            return
        }
        if (risk == null || risk <= 0 || risk > 5 || daily == null || daily <= 0 || max == null || max !in 1..10 ||
            rr == null || rr < 1 || cap == null || cap <= 0 || cap > 100 || extras == null || extras !in 0..30 || volume == null || volume < 0
        ) {
            message = "Geçersiz değer: risk %0–5, eş zamanlı 1–10, R:R ≥ 1, pozisyon sınırı %0–100, ek coin 0–30 olmalı."
            return
        }
        repository.update {
            it.copy(
                engine = it.engine.copy(
                    risk = it.engine.risk.copy(
                        riskPerTradePercent = risk, dailyLossLimitPercent = daily, maxOpenPositions = max, minNetRiskReward = rr,
                        maxPositionPercentOfEquity = cap, maxTotalOpenRiskPercent = totalCap, atrStopMultiple = atrK,
                    ),
                    scanner = it.engine.scanner.copy(extraCoins = extras, minQuoteVolume24h = volume),
                ),
            )
        }
        engine.updateConfig(repository.settings.value.engine)
        message = "Kaydedildi. Bir sonraki taramada geçerli olur."
    }

    fun setStrategy(id: StrategyId, enabled: Boolean) {
        repository.update {
            val set = if (enabled) it.engine.enabledStrategies + id else it.engine.enabledStrategies - id
            it.copy(engine = it.engine.copy(enabledStrategies = set))
        }
        engine.updateConfig(repository.settings.value.engine)
    }

    fun setProfile(profile: StrategyProfile) {
        repository.update { it.copy(engine = it.engine.copy(profile = profile)) }
        engine.updateConfig(repository.settings.value.engine)
    }

    fun setRegimePolicy(policy: com.spotbot.core.analysis.RegimePolicy) {
        repository.update { it.copy(engine = it.engine.copy(strategyParams = it.engine.strategyParams.copy(regimePolicy = policy))) }
        engine.updateConfig(repository.settings.value.engine)
    }

    fun setAtrStopMode(mode: AtrStopMode) {
        repository.update { it.copy(engine = it.engine.copy(risk = it.engine.risk.copy(atrStopMode = mode))) }
        engine.updateConfig(repository.settings.value.engine)
    }

    fun setExecution(transform: (ExecutionConfig) -> ExecutionConfig) {
        repository.update { it.copy(engine = it.engine.copy(execution = transform(it.engine.execution))) }
        engine.updateConfig(repository.settings.value.engine)
    }

    fun resetPaper() {
        val value = parseDecimal(capital)?.takeIf { it >= 10 } ?: run {
            message = "Sermaye en az 10 USDT olmalı."
            return
        }
        repository.update { it.copy(engine = it.engine.copy(initialCapital = value)) }
        engine.resetPaperAccount(value)
        message = "Sanal hesap ${formatEditable(value)} USDT ile sıfırlandı."
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = viewModel(factory = AppViewModels.Factory)) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmC by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        viewModel.message?.let { InfoBanner(it) }
        SectionCard("Risk yönetimi (test başlangıç değerleri)") {
            NumberField("İşlem başına risk (%)", viewModel.riskPerTrade) { viewModel.riskPerTrade = it }
            NumberField("Günlük kayıp limiti (%)", viewModel.dailyLoss) { viewModel.dailyLoss = it }
            NumberField("Eş zamanlı en fazla işlem", viewModel.maxPositions) { viewModel.maxPositions = it }
            NumberField("Asgari net R:R", viewModel.minRr) { viewModel.minRr = it }
            NumberField("Pozisyon üst sınırı (sermayenin %)", viewModel.maxPositionPct) { viewModel.maxPositionPct = it }
            NumberField("Toplam açık risk sınırı (%)", viewModel.totalRiskCap) { viewModel.totalRiskCap = it }
            NumberField("ATR stop çarpanı (1H ATR × k)", viewModel.atrMultiple) { viewModel.atrMultiple = it }
            NumberField("Ek coin sayısı (izleme listesine)", viewModel.extraCoins) { viewModel.extraCoins = it }
            NumberField("Asgari 24s hacim (USDT)", viewModel.minVolume) { viewModel.minVolume = it }
            KeyValueRow("Kaldıraç / zararda ek alım", "Kullanılmaz / yasak")
            Button(onClick = viewModel::saveRisk, modifier = Modifier.fillMaxWidth()) { Text("Kaydet") }
        }
        SectionCard("Strateji profili") {
            StrategyProfile.entries.forEach { profile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.title + if (profile == StrategyProfile.ADAPTIVE) " (varsayılan)" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(profile.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.RadioButton(selected = settings.engine.profile == profile, onClick = { viewModel.setProfile(profile) })
                }
            }
            Text("Profiller mevcut kuralları değiştirmez. Karşılaştırma için Backtest → Profiller.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Piyasa rejimi filtresi (Z19)") {
            Text(
                "BTC 4H'e göre 5 sınıf: güçlü yükseliş, zayıf yükseliş, yatay, güçlü düşüş, aşırı volatilite. 1 yıllık testte filtre beklenen değeri pozitife çevirmedi; bu yüzden varsayılan kapalıdır.",
                style = MaterialTheme.typography.bodySmall,
            )
            com.spotbot.core.analysis.RegimePolicy.entries.forEach { policy ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(policy.title + if (policy == com.spotbot.core.analysis.RegimePolicy.OFF) " (varsayılan)" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(policy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.RadioButton(selected = settings.engine.strategyParams.regimePolicy == policy, onClick = { viewModel.setRegimePolicy(policy) })
                }
            }
        }
        SectionCard("Stop yöntemi") {
            AtrStopMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mode.title + if (mode == AtrStopMode.OFF) " (varsayılan)" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.RadioButton(selected = settings.engine.risk.atrStopMode == mode, onClick = { viewModel.setAtrStopMode(mode) })
                }
            }
        }
        SectionCard("Stratejiler") {
            StrategyId.entries.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${id.code} · ${id.title}", modifier = Modifier.weight(1f))
                    Switch(
                        checked = id in settings.engine.enabledStrategies,
                        onCheckedChange = { on -> if (on && id == StrategyId.RANGE) confirmC = true else viewModel.setStrategy(id, on) },
                    )
                }
            }
            Text("Strateji seçimi 4H piyasa rejimine göre otomatik yapılır; burada yalnızca hangi stratejilerin kullanılabileceği belirlenir.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Emir ve çıkış yönetimi (deneysel)") {
            val e = settings.engine.execution
            Text(
                "Bir yıllık testte bu seçeneklerin hiçbiri görülmemiş dönemde kâra geçmedi; varsayılanlar kapalıdır. Backtest sekmesinde deneyebilirsiniz.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
            ToggleRow("Limit giriş (maker, %0 komisyon)", e.entryMode == EntryMode.LIMIT) { on ->
                viewModel.setExecution { it.copy(entryMode = if (on) EntryMode.LIMIT else EntryMode.MARKET) }
            }
            ToggleRow("Hedefe limit emirle çıkış", e.limitTakeProfit) { on -> viewModel.setExecution { it.copy(limitTakeProfit = on) } }
            ToggleRow("+1R'de stopu girişe çek (başa baş)", e.breakEvenAtR != null) { on -> viewModel.setExecution { it.copy(breakEvenAtR = if (on) 1.0 else null) } }
            ToggleRow("+1R'de %50 kısmi kâr", e.partialTakeProfitAtR != null) { on -> viewModel.setExecution { it.copy(partialTakeProfitAtR = if (on) 1.0 else null) } }
            ToggleRow("48 saat süre sınırı", e.maxHoldingHours != null) { on -> viewModel.setExecution { it.copy(maxHoldingHours = if (on) 48 else null) } }
            ToggleRow("Üst üste 3 zararda coini 7 gün dinlendir", e.cooldownTrades != null) { on -> viewModel.setExecution { it.copy(cooldownTrades = if (on) 3 else null) } }
        }
        SectionCard("Kurallar") {
            val engine = viewModel.ruleEngine
            val mandatory = BuiltInRules.all.count { it.group == RuleGroup.MANDATORY }
            val quality = BuiltInRules.all.count { it.group == RuleGroup.QUALITY }
            KeyValueRow("Yerleşik zorunlu / kalite kuralı", "$mandatory / $quality")
            KeyValueRow("Özel kural (60 kural listesi)", "${engine.config.custom.size}")
            Text(
                "60 kuralın doğrulanmış metni henüz eklenmedi. Kurallar varsayımla doldurulmadı; metin geldiğinde rules.json dosyasına zorunlu/kalite olarak işlenecek.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
            engine.configErrors.forEach { Text("Hatalı kural: $it", color = SpotBotTheme.tradingColors.loss) }
            BuiltInRules.all.forEach { rule ->
                Text("${rule.id} ${rule.title}${if (rule.group == RuleGroup.MANDATORY) " (zorunlu)" else ""}", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionCard("Sanal hesap") {
            NumberField("Başlangıç sermayesi (USDT)", viewModel.capital) { viewModel.capital = it }
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) { Text("Sanal hesabı sıfırla") }
        }
        SectionCard("7/24 çalışma") {
            val power = context.getSystemService(PowerManager::class.java)
            val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
            KeyValueRow("Pil optimizasyonu muafiyeti", if (exempt) "Var" else "Yok", if (exempt) SpotBotTheme.tradingColors.gain else SpotBotTheme.tradingColors.loss)
            Text("Muafiyet yoksa Android uyku modunda ağı ve zamanlayıcıları kısıtlayabilir; bot veri kaçırabilir.", style = MaterialTheme.typography.bodySmall)
            if (!exempt) {
                OutlinedButton(
                    onClick = { requestBatteryExemption(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Muafiyet iste") }
            }
        }
        SectionCard("Güvenlik") {
            KeyValueRow("İşlem modu", "Sanal işlem (paper) — tek seçenek", SpotBotTheme.tradingColors.gain)
            KeyValueRow("Gerçek emir gönderme", "Uygulamada yok")
            KeyValueRow("API anahtarı", "Kullanılmıyor, uygulamada saklanmıyor")
            Text("V1 sanal işlem yapar ve MEXC API anahtarı kullanmaz. Gerçek işleme geçildiğinde (V2) anahtarda para çekme yetkisi olmayacak, IP kısıtlaması uygulanacak ve anahtar cihazda şifreli saklanacak.", style = MaterialTheme.typography.bodySmall)
            Text("MEXC spot API'si borsa tarafında stop emri desteklemiyor (yalnız LIMIT, MARKET, LIMIT_MAKER, IOC, FOK). Bu doğrulanmadan gerçek parayla otomatik işlem başlatılmayacak.", style = MaterialTheme.typography.bodySmall, color = SpotBotTheme.tradingColors.loss)
        }
        KeyValueRow("Sürüm", BuildConfig.VERSION_NAME)
    }

    if (confirmC) {
        AlertDialog(
            onDismissRequest = { confirmC = false },
            title = { Text("Strateji C etkinleştirilsin mi?") },
            text = { Text("Tasarım notu gereği yatay piyasa stratejisi ayrı backtest yapılmadan etkinleştirilmemeli. Backtest sekmesinde yalnız C ile test yaptınız mı?") },
            confirmButton = { TextButton(onClick = { viewModel.setStrategy(StrategyId.RANGE, true); confirmC = false }) { Text("Evet, etkinleştir") } },
            dismissButton = { TextButton(onClick = { confirmC = false }) { Text("Vazgeç") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Sanal hesap sıfırlansın mı?") },
            text = { Text("Açık sanal pozisyonlar, işlem geçmişi ve karar günlüğü silinir.") },
            confirmButton = { TextButton(onClick = { viewModel.resetPaper(); confirmReset = false }) { Text("Sıfırla") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Vazgeç") } },
        )
    }
}

/**
 * 7/24 çalışan kişisel bir bot için doğrudan muafiyet istenir. Not: Bu kullanım Play Store içerik
 * politikasına uymaz; uygulama Play'de yayınlanacaksa ayarlar ekranına yönlendirmeyle değiştirilmelidir.
 */
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
}
