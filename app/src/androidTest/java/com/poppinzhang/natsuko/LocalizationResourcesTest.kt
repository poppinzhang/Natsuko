package com.poppinzhang.natsuko

import android.app.LocaleConfig
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationResourcesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun supportedLanguagesAndEnglishFallbackAreDeclared() {
        val config = LocaleConfig.fromContextIgnoringOverride(context)
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        assertEquals("en,zh", config.supportedLocales!!.toLanguageTags())
        assertEquals("en", config.defaultLocale!!.language)
    }

    @Test
    fun englishAndUnsupportedLocalesUseEnglish() {
        for (tag in listOf("en", "en-US", "en-GB", "ja-JP", "fr-FR", "ar-EG")) {
            val localized = localizedContext(tag)
            assertEquals(tag, "Settings", localized.getString(R.string.settings_title))
            assertEquals(tag, "Remove black borders · Natsuko", localized.getString(R.string.share_crop_black_border))
        }
    }

    @Test
    fun chineseLocalesUseTheChineseTranslation() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-TW", "zh-HK", "zh-Hans", "zh-Hant")) {
            val localized = localizedContext(tag)
            assertEquals(tag, "设置", localized.getString(R.string.settings_title))
            assertEquals(tag, "去除黑边 · Natsuko", localized.getString(R.string.share_crop_black_border))
        }
    }

    private fun localizedContext(languageTags: String) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTags))
        },
    )
}
