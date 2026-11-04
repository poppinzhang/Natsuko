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
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import com.poppinzhang.natsuko.i18n.AppLanguage
import com.poppinzhang.natsuko.i18n.AppLanguages
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LanguageWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var originalLocales: LocaleList

    @Before
    fun startInEnglish() {
        compose.activityRule.scenario.onActivity {
            originalLocales = it.getSystemService(LocaleManager::class.java).applicationLocales
            AppLanguages.select(it, AppLanguage.ENGLISH)
        }
        waitForLanguage(AppLanguage.ENGLISH)
    }

    @After
    fun restoreLanguage() {
        compose.activityRule.scenario.onActivity {
            it.getSystemService(LocaleManager::class.java).applicationLocales = originalLocales
        }
        compose.waitForIdle()
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
        choose(AppLanguage.CHINESE)
        waitForLanguage(AppLanguage.CHINESE)
        compose.onNodeWithTag("language-picker").assertTextEquals("中文")
        compose.activityRule.scenario.recreate()
        waitForLanguage(AppLanguage.CHINESE)
        compose.activityRule.scenario.onActivity {
            val current = ViewModelProvider(it)[NatsukoViewModel::class.java]
            assertSame(retainedViewModel, current)
            assertEquals(AppScreen.SETTINGS, current.state.value.screen)
            assertEquals("https://x.com/example/status/1234567890", current.state.value.twitterLink)
            assertEquals("设置", it.getString(R.string.settings_title))
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
        compose.activityRule.scenario.onActivity {
            // The same framework preference is changed by Android's App languages screen.
            it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh")
        }
        waitForLanguage(AppLanguage.CHINESE)
        compose.onNodeWithTag("language-picker").assertTextEquals("中文")
        compose.activityRule.scenario.onActivity { AppLanguages.select(it, AppLanguage.SYSTEM) }
        waitForLanguage(AppLanguage.SYSTEM)
        compose.activityRule.scenario.onActivity {
            assertEquals(true, it.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
        }
    }

    private fun choose(language: AppLanguage) {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("language-picker"))
        compose.onNodeWithTag("language-picker").performClick()
        compose.onNodeWithTag("language-${language.name.lowercase(java.util.Locale.ROOT)}").performClick()
    }

    private fun waitForLanguage(language: AppLanguage) {
        compose.waitUntil(10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity {
                val expectedText = when (language) {
                    AppLanguage.CHINESE -> "设置"
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
}
