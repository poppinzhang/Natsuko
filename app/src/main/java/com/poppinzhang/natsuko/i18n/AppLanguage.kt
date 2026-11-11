package com.poppinzhang.natsuko.i18n

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.annotation.StringRes
import com.poppinzhang.natsuko.R
import java.util.Locale

enum class AppLanguage(val languageTag: String, @param:StringRes val labelRes: Int) {
    SYSTEM("", R.string.settings_language_system),
    CHINESE("zh", R.string.settings_language_chinese),
    TRADITIONAL_CHINESE("zh-Hant", R.string.settings_language_traditional_chinese),
    KOREAN("ko", R.string.settings_language_korean),
    JAPANESE("ja", R.string.settings_language_japanese),
    ENGLISH("en", R.string.settings_language_english),
}

/** Android owns persistence and synchronizes this picker with Settings > App languages. */
object AppLanguages {
    fun current(context: Context): AppLanguage {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return fromLocale(if (locales.isEmpty) null else locales[0])
    }

    internal fun fromLocale(locale: Locale?): AppLanguage = when (locale?.language) {
        "zh" -> {
            // Explicit scripts take priority over regions, including legacy system selections.
            val traditional = locale.script == "Hant" ||
                (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
            if (traditional) AppLanguage.TRADITIONAL_CHINESE else AppLanguage.CHINESE
        }
        "ko" -> AppLanguage.KOREAN
        "ja" -> AppLanguage.JAPANESE
        "en" -> AppLanguage.ENGLISH
        else -> AppLanguage.SYSTEM
    }

    fun select(context: Context, language: AppLanguage) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags(language.languageTag)
    }
}
