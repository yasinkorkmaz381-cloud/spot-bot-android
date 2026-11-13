package com.spotbot.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.spotbot.android.data.ArchiveSummary
import com.spotbot.android.data.ArchivedTrade
import com.spotbot.android.ui.detail.ArchiveContent
import com.spotbot.android.ui.detail.DetailContent
import com.spotbot.android.ui.home.HomeContent
import com.spotbot.android.ui.opportunities.OpportunitiesContent
import com.spotbot.android.ui.settings.SettingsContent
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.trades.TradesContent
import com.spotbot.android.ui.trades.TradesTab
import com.spotbot.core.kr.BotMode
import com.spotbot.core.kr.EventWindow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ekran görüntüleri (yalnız -Pscreenshots ile kaydedilir). Veriler örnektir (SampleData), sonuç değildir. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h1100dp-xhdpi")
open class ScreenshotTest {
    protected open val prefix: String = ""

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            SpotBotTheme(darkTheme = true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$prefix$name.png")
    }

    private val state = SampleData.state()
    private val now = SampleData.NOW

    @Test fun home() = capture("01-ana-ekran") { HomeContent(state, BotMode.FULL, true, now, {}, {}) }
    @Test fun homeUnprotected() = capture("02-ana-ekran-kapali-acik-pozisyon") { HomeContent(state, BotMode.OFF, true, now, {}, {}) }
    @Test fun homeNoKey() = capture("03-ana-ekran-anahtar-yok") { HomeContent(SampleData.state(open = false).copy(universe = null, scan = null), BotMode.PROTECT_ONLY, false, now, {}, {}) }
    @Test fun opportunities() = capture("04-firsatlar") { OpportunitiesContent(state, now) }
    @Test fun tradesOpen() = capture("05-islemler-acik") { TradesContent(state, {}) }
    @Test fun tradesClosed() = capture("06-islemler-kapali") { TradesContent(state, {}, TradesTab.CLOSED) }
    @Test fun detail() = capture("07-ayrintilar") { DetailContent(state, now) }
    @Test fun settings() = capture("08-ayarlar") { SettingsContent(true, listOf(EventWindow(now + 86_400_000, "FOMC faiz kararı")), {}, {}, { _, _ -> }, {}, {}, {}) }
    @Test fun settingsKeyOk() = capture("08b-ayarlar-anahtar-dogrulandi") {
        SettingsContent(true, emptyList(), {}, {}, { _, _ -> }, {}, {}, {}, keyStatus = com.spotbot.android.ui.KeyStatus(com.spotbot.android.ui.KeyStatus.Kind.OK, "Anahtar geçerli (kalan aylık kredi 13.905). Liste alındı: ilk 300 → MEXC'de 215 → taranan 211."))
    }
    @Test fun settingsKeyUnreadable() = capture("08c-ayarlar-anahtar-okunamadi") {
        SettingsContent(false, emptyList(), {}, {}, { _, _ -> }, {}, {}, {}, keyUnreadable = true, keyStatus = com.spotbot.android.ui.KeyStatus(com.spotbot.android.ui.KeyStatus.Kind.ERROR, "CMC anahtarı geçersiz (yanlış ya da eksik kopyalanmış olabilir)"))
    }
    @Test fun archive() = capture("09-arsiv") {
        ArchiveContent(ArchiveSummary(listOf(ArchivedTrade("Momentum RS-1 (eski strateji)", "SOLUSDT", "RS-1", now - 9 * 86_400_000L, now - 2 * 86_400_000L, -4.25, "STOP")), listOf("archive-rs1/paper.json"), null))
    }
}

/** Küçük telefon (360 × 640 dp, büyük yazı tipi). */
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.3f)
class SmallScreenTest : ScreenshotTest() {
    override val prefix = "kucuk-"
}
