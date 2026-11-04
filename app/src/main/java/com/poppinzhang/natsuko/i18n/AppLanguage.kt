package com.poppinzhang.natsuko.i18n

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.annotation.StringRes
import com.poppinzhang.natsuko.R

enum class AppLanguage(val languageTag: String, @param:StringRes val labelRes: Int) {
    SYSTEM("", R.string.settings_language_system),
    CHINESE("zh", R.string.settings_language_chinese),
    ENGLISH("en", R.string.settings_language_english),
}

/** Android owns persistence and synchronizes this picker with Settings > App languages. */
object AppLanguages {
    fun current(context: Context): AppLanguage {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) AppLanguage.SYSTEM else when (locales[0].language) {
            "zh" -> AppLanguage.CHINESE
            "en" -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
    }

    fun select(context: Context, language: AppLanguage) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags(language.languageTag)
    }
}
