package com.poppinzhang.natsuko

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.model.ThemeMode
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeConfigurationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun systemChangesUpdateThemeAndExplicitModesOverrideBothConfigurations() {
        val baseConfiguration = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        val configuration = mutableStateOf(configurationFor(baseConfiguration, dark = false))
        val mode = mutableStateOf(ThemeMode.SYSTEM)
        var backgroundLuminance = -1f
        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                NatsukoTheme(themeMode = mode.value) {
                    val background = MaterialTheme.colorScheme.background
                    SideEffect { backgroundLuminance = background.luminance() }
                }
            }
        }

        for (selectedMode in ThemeMode.entries) {
            for (systemIsDark in listOf(false, true)) {
                composeRule.runOnIdle {
                    mode.value = selectedMode
                    configuration.value = configurationFor(baseConfiguration, dark = systemIsDark)
                }
                composeRule.runOnIdle {
                    val dark = selectedMode.usesDarkTheme(systemIsDark)
                    assertTrue("$selectedMode with system dark=$systemIsDark gave $backgroundLuminance",
                        if (dark) backgroundLuminance < 0.1f else backgroundLuminance > 0.7f)
                }
            }
        }
    }

    private fun configurationFor(base: Configuration, dark: Boolean) = Configuration(base).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
}
