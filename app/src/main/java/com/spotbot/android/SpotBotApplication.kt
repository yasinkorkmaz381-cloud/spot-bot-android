package com.spotbot.android

import android.app.Application
import com.spotbot.android.di.AppContainer

class SpotBotApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
