package com.spotbot.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.spotbot.android.ui.home.HomeContent
import com.spotbot.android.ui.opportunities.OpportunitiesContent
import com.spotbot.android.ui.settings.parseTurkeyTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.core.kr.BotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `switching off with open positions asks first and offers protect-only`() {
        val chosen = ArrayList<BotMode>()
        compose.setContent { SpotBotTheme { HomeContent(SampleData.state(), BotMode.FULL, true, SampleData.NOW, { chosen += it }, {}) } }
        compose.onNodeWithText("Tamamen kapat").performScrollTo().performClick()
        compose.onNodeWithText("Tüm takip kapatılsın mı?").assertIsDisplayed()
        compose.onNodeWithText("Yalnız yeni girişleri durdur").performClick()
        assertEquals(listOf(BotMode.PROTECT_ONLY), chosen)
    }

    @Test
    fun `fully off with open positions shows an unprotected warning`() {
        compose.setContent { SpotBotTheme { HomeContent(SampleData.state(), BotMode.OFF, true, SampleData.NOW, {}, {}) } }
        compose.onNodeWithTag("unprotected").assertIsDisplayed()
        compose.onNodeWithText("Tamamen kapalı").assertIsDisplayed()
    }

    @Test
    fun `stop new entries keeps protection`() {
        val chosen = ArrayList<BotMode>()
        compose.setContent { SpotBotTheme { HomeContent(SampleData.state(), BotMode.FULL, true, SampleData.NOW, { chosen += it }, {}) } }
        compose.onNodeWithText("Yeni girişleri durdur").performScrollTo().performClick()
        assertEquals(listOf(BotMode.PROTECT_ONLY), chosen)
    }

    @Test
    fun `opportunities show the universe funnel and filter by stage`() {
        compose.setContent { SpotBotTheme { OpportunitiesContent(SampleData.state(), SampleData.NOW) } }
        compose.onNodeWithTag("funnel").assertIsDisplayed()
        compose.onNodeWithText("İlk 5 → MEXC'de 3 → taranan 211").assertIsDisplayed()
        compose.onNodeWithText("Retest bekleniyor 2").performClick()
        compose.onNodeWithText("TAO").assertIsDisplayed()
        compose.onNodeWithText("SOL").assertDoesNotExist()
    }

    @Test
    fun `home shows last and next scan, connection, and says clearly when protection is not watched`() {
        val st = SampleData.state()
        val ok = com.spotbot.android.ui.home.homeStatus(st, BotMode.FULL, SAMPLE_LIVE, SampleData.NOW)
        org.junit.Assert.assertTrue(ok.lastScan.contains("sn önce") && ok.lastScan.contains("gecikme"))
        org.junit.Assert.assertTrue(ok.nextScan.contains("her 3 dakikada bir"))
        org.junit.Assert.assertTrue(ok.stream.startsWith("Bağlı"))
        assertNull(ok.protectionWarning)
        // Servis durduysa: tarama yapılmıyor ve koruma izlenemiyor (tarama yapılmış gibi gösterilmez).
        val down = com.spotbot.android.ui.home.homeStatus(st, BotMode.FULL, SAMPLE_LIVE.copy(serviceAliveAt = 0), SampleData.NOW)
        assertEquals("Servis çalışmıyor: tarama yapılmıyor", down.nextScan)
        org.junit.Assert.assertTrue(down.protectionWarning!!.startsWith("Koruma izlenemiyor: 2 açık pozisyon"))
        // Bağlantı gecikmesi: son denetim 2 dk önce.
        val stale = com.spotbot.android.ui.home.homeStatus(st.copy(protection = com.spotbot.core.kr.ProtectionStatus(SampleData.NOW - 120_000, "REST")), BotMode.FULL, SAMPLE_LIVE, SampleData.NOW)
        org.junit.Assert.assertNotNull(stale.protectionWarning)
        // Çakışan tarama sayısı ve hata görünür.
        val skipped = com.spotbot.android.ui.home.homeStatus(st.copy(scanTiming = st.scanTiming.copy(skippedOverlap = 2)), BotMode.FULL, SAMPLE_LIVE, SampleData.NOW)
        org.junit.Assert.assertTrue(skipped.lastScan.contains("atlanan 2"))
        compose.setContent { SpotBotTheme { HomeContent(st, BotMode.FULL, true, SampleData.NOW, {}, {}, live = SAMPLE_LIVE.copy(serviceAliveAt = 0)) } }
        compose.onNodeWithTag("protection-warning").assertIsDisplayed()
    }

    @Test
    fun `event times are read as Turkey time`() {
        assertEquals(1_794_664_800_000L, parseTurkeyTime("14.11.2026 17:00"))
        assertNull(parseTurkeyTime("yarın"))
    }
}
