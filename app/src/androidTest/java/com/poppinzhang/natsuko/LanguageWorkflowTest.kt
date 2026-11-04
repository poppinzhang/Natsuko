package com.poppinzhang.natsuko

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.i18n.AppLanguage
import com.poppinzhang.natsuko.i18n.AppLanguages
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class LanguageWorkflowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var originalLocales: LocaleList

    private val languageOverride = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            originalLocales = context.getSystemService(LocaleManager::class.java).applicationLocales
            // Set the override before launch so initial Compose synchronization cannot race recreation.
            AppLanguages.select(context, AppLanguage.ENGLISH)
        }

        override fun after() {
            // The inner Activity rule has already closed its Activity before this override is restored.
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(LocaleManager::class.java).applicationLocales = originalLocales
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(languageOverride).around(compose)

    @Before
    fun waitForInitialLanguage() {
        waitForLanguage(AppLanguage.ENGLISH)
    }

    @Test
    fun pickerSwitchesImmediatelyAndPreservesWorkflowThroughRecreation() {
        lateinit var retainedViewModel: NatsukoViewModel
        compose.activityRule.scenario.onActivity {
            retainedViewModel = ViewModelProvider(it)[NatsukoViewModel::class.java]
            retainedViewModel.openTwitterDownload()
            retainedViewModel.updateTwitterLink("https://x.com/example/status/1234567890")
            retainedViewModel.openSettings()
        }
        for (language in listOf(AppLanguage.CHINESE, AppLanguage.TRADITIONAL_CHINESE, AppLanguage.KOREAN)) {
            choose(language)
            waitForLanguage(language)
            compose.onNodeWithTag("language-picker").assertTextEquals(nativeLabel(language))
            compose.activityRule.scenario.recreate()
            waitForLanguage(language)
            compose.onNodeWithTag("language-picker").assertTextEquals(nativeLabel(language))
            compose.activityRule.scenario.onActivity {
                val current = ViewModelProvider(it)[NatsukoViewModel::class.java]
                assertSame(retainedViewModel, current)
                assertEquals(AppScreen.SETTINGS, current.state.value.screen)
                assertEquals("https://x.com/example/status/1234567890", current.state.value.twitterLink)
                assertEquals(language.languageTag, it.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
            }
        }
        choose(AppLanguage.ENGLISH)
        waitForLanguage(AppLanguage.ENGLISH)
        compose.onNodeWithTag("language-picker").assertTextEquals("English")
        choose(AppLanguage.SYSTEM)
        waitForLanguage(AppLanguage.SYSTEM)
        compose.onNodeWithTag("language-picker").performClick()
        compose.onNodeWithTag("language-system").assertIsSelected()
    }

    @Test
    fun nativeApplicationLanguageChangesUpdateThePicker() {
        compose.onNodeWithTag("home-settings").performClick()
        for ((tag, language) in listOf(
            "zh" to AppLanguage.CHINESE,
            "zh-Hant" to AppLanguage.TRADITIONAL_CHINESE,
            "zh-TW" to AppLanguage.TRADITIONAL_CHINESE,
            "ko-KR" to AppLanguage.KOREAN,
        )) {
            compose.activityRule.scenario.onActivity {
                // The same framework preference is changed by Android's App languages screen.
                it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
            }
            waitForLanguage(language)
            compose.onNodeWithTag("language-picker").assertTextEquals(nativeLabel(language))
        }
        compose.activityRule.scenario.onActivity { AppLanguages.select(it, AppLanguage.SYSTEM) }
        waitForLanguage(AppLanguage.SYSTEM)
        compose.activityRule.scenario.onActivity {
            assertEquals(true, it.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
        }
    }

    private fun choose(language: AppLanguage) {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("language-picker"))
        compose.onNodeWithTag("language-picker").performClick()
        compose.onNodeWithTag("language-${language.name.lowercase(java.util.Locale.ROOT)}").performScrollTo().performClick()
    }

    private fun waitForLanguage(language: AppLanguage) {
        compose.waitUntil(10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity {
                val expectedText = when (language) {
                    AppLanguage.CHINESE -> "设置"
                    AppLanguage.TRADITIONAL_CHINESE -> "設定"
                    AppLanguage.KOREAN -> "설정"
                    AppLanguage.ENGLISH -> "Settings"
                    AppLanguage.SYSTEM -> it.createConfigurationContext(
                        Configuration(it.resources.configuration).apply {
                            setLocales(it.getSystemService(LocaleManager::class.java).systemLocales)
                        },
                    ).getString(R.string.settings_title)
                }
                matches = AppLanguages.current(it) == language && it.getString(R.string.settings_title) == expectedText
            }
            matches
        }
        compose.waitForIdle()
    }

    private fun nativeLabel(language: AppLanguage): String = when (language) {
        AppLanguage.CHINESE -> "简体中文"
        AppLanguage.TRADITIONAL_CHINESE -> "繁體中文"
        AppLanguage.KOREAN -> "한국어"
        AppLanguage.ENGLISH -> "English"
        AppLanguage.SYSTEM -> error("The system option has a localized label")
    }
}
