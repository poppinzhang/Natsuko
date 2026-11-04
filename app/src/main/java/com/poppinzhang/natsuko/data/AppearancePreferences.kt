package com.poppinzhang.natsuko.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.poppinzhang.natsuko.model.ThemeMode

class AppearancePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun read(): ThemeMode = ThemeMode.fromPreference(preferences.getString("theme_mode", null))

    fun setThemeMode(mode: ThemeMode): ThemeMode {
        preferences.edit { putString("theme_mode", mode.preferenceValue) }
        return mode
    }

    /** Retain the subscription, then invoke it when the observer is no longer needed. */
    fun observe(onChanged: (ThemeMode) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_mode" || key == null) onChanged(read())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
