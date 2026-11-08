package com.spotbot.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spotbot.android.ui.SpotBotApp
import com.spotbot.android.ui.theme.SpotBotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SpotBotTheme {
                SpotBotApp()
            }
        }
    }
}
