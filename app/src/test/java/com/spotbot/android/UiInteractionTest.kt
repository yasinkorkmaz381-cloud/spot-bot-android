package com.spotbot.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.spotbot.android.bot.BacktestPeriod
import com.spotbot.android.bot.BacktestState
import com.spotbot.android.data.ArchiveSummary
import com.spotbot.android.ui.backtest.BacktestContent
import com.spotbot.android.ui.settings.SettingsContent
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.trades.TradesContent
import com.spotbot.core.rs1.Rs1Costs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class UiInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        SpotBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
    }

    @Test
    fun `trades tabs switch between open, history, weekly and archive`() {
        val paper = SampleData.paper()
        show { TradesContent(paper, ArchiveSummary(emptyList(), emptyList(), null)) }
        compose.onNodeWithText("Geçmiş").performClick()
        compose.onNodeWithText("Net kâr/zarar").assertIsDisplayed()
        compose.onNodeWithText("Haftalık").performClick()
        compose.onNodeWithText("Her pazartesi", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Arşiv").performClick()
        compose.onNodeWithText("Arşivde kapanmış işlem yok.").assertIsDisplayed()
    }

    @Test
    fun `backtest runs the selected period and cost scenario`() {
        var asked: Pair<BacktestPeriod, Rs1Costs>? = null
        show { BacktestContent(BacktestState.Idle, { p, c -> asked = p to c }) }
        compose.onNodeWithText("Kötümser").performClick()
        compose.onNodeWithText("Backtest'i çalıştır").performClick()
        assertEquals(BacktestPeriod.HOLDOUT to Rs1Costs.PESSIMISTIC, asked)
    }

    @Test
    fun `resetting the paper account asks for confirmation`() {
        var resets = 0
        show { SettingsContent(true, SampleData.cfg, {}, { resets++ }) }
        compose.onNodeWithText("Sanal hesabı sıfırla").performClick()
        assertEquals(0, resets)
        compose.onNodeWithText("Sıfırla").performClick()
        assertEquals(1, resets)
    }

    @Test
    fun `the settings screen offers no strategy selection`() {
        show { SettingsContent(true, SampleData.cfg, {}, {}) }
        assertTrue(compose.onAllNodesWithText("Strateji A", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Trend Pullback", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Strateji: Momentum RS-1").assertIsDisplayed()
    }
}
