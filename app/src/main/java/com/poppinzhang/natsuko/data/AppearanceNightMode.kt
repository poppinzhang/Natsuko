package com.poppinzhang.natsuko.data

import android.app.UiModeManager
import android.content.Context
import com.poppinzhang.natsuko.model.ThemeMode

/** Keeps Android's window resources and next launch's splash screen in sync with the app theme. */
fun applyApplicationTheme(context: Context, mode: ThemeMode) {
    val nightMode = when (mode) {
        // For the application override, AUTO clears the override and inherits the system mode.
        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    }
    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(nightMode)
}
