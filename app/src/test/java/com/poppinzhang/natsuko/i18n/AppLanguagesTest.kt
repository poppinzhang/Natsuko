package com.poppinzhang.natsuko.i18n

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguagesTest {
    @Test
    fun absentOrUnsupportedOverrideUsesSystemSelection() {
        assertEquals(AppLanguage.SYSTEM, AppLanguages.fromLocale(null))
        for (tag in listOf("fr-FR", "ar-EG", "und")) {
            assertEquals(tag, AppLanguage.SYSTEM, AppLanguages.fromLocale(Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun chineseScriptsAndRegionsSelectTheMatchingVariant() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-TW", "zh-Hans-HK")) {
            assertEquals(tag, AppLanguage.CHINESE, AppLanguages.fromLocale(Locale.forLanguageTag(tag)))
        }
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN", "zh-Hant-SG")) {
            assertEquals(tag, AppLanguage.TRADITIONAL_CHINESE, AppLanguages.fromLocale(Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun englishAndKoreanRegionalOverridesSelectTheirLanguage() {
        for (tag in listOf("en", "en-US", "en-GB")) {
            assertEquals(tag, AppLanguage.ENGLISH, AppLanguages.fromLocale(Locale.forLanguageTag(tag)))
        }
        for (tag in listOf("ko", "ko-KR", "ko-KP")) {
            assertEquals(tag, AppLanguage.KOREAN, AppLanguages.fromLocale(Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun japaneseRegionalOverridesSelectJapanese() {
        for (tag in listOf("ja", "ja-JP")) {
            assertEquals(tag, AppLanguage.JAPANESE, AppLanguages.fromLocale(Locale.forLanguageTag(tag)))
        }
    }
}
