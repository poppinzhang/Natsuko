package com.poppinzhang.natsuko

import android.app.Application
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.AppearancePreferences
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.model.ThemeMode
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceWorkflowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = AppearancePreferences(context)
    private val viewModelStore = ViewModelStore()
    private val fixtures = mutableListOf<File>()
    private var originalMode = ThemeMode.SYSTEM

    @Before
    fun resetAppearance() {
        originalMode = preferences.read()
        preferences.setThemeMode(ThemeMode.SYSTEM)
        composeRule.waitForIdle()
    }

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync { viewModelStore.clear() }
        fixtures.forEach { it.delete() }
        preferences.setThemeMode(originalMode)
        composeRule.waitForIdle()
    }

    @Test
    fun absentAndUnknownStoredModesDefaultToSystem() {
        val rawPreferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        assertTrue(rawPreferences.edit().remove("theme_mode").commit())
        assertEquals(ThemeMode.SYSTEM, AppearancePreferences(context).read())
        assertEquals(ThemeMode.SYSTEM, newViewModel("missing").themeMode.value)

        assertTrue(rawPreferences.edit().putString("theme_mode", "unknown-mode").commit())
        assertEquals(ThemeMode.SYSTEM, AppearancePreferences(context).read())
        assertEquals(ThemeMode.SYSTEM, newViewModel("unknown").themeMode.value)
    }

    @Test
    fun settingsOptionsImmediatelyChangeAppColorsAndSystemBarContrast() {
        openSettings()
        composeRule.onNodeWithTag("theme-mode-system").assertIsDisplayed()
        assertSelectedMode(ThemeMode.SYSTEM)

        selectMode(ThemeMode.DARK)
        assertAppearance(dark = true)

        selectMode(ThemeMode.LIGHT)
        assertAppearance(dark = false)

        selectMode(ThemeMode.SYSTEM)
        var systemIsDark = false
        composeRule.activityRule.scenario.onActivity { activity ->
            systemIsDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        assertAppearance(dark = systemIsDark)
    }

    @Test
    fun returningToSystemRestoresNativeThemeAndTracksDeviceChanges() {
        assumeTrue("Device-wide appearance changes are restricted to the emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val uiModeManager = checkNotNull(context.getSystemService(UiModeManager::class.java))
        val originalSystemMode = uiModeManager.nightMode
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE")
        try {
            uiModeManager.nightMode = UiModeManager.MODE_NIGHT_NO
            waitForNativeAppearance(dark = false)
            openSettings()
            assertAppearance(dark = false)

            selectMode(ThemeMode.DARK)
            waitForNativeAppearance(dark = true)
            assertEquals(UiModeManager.MODE_NIGHT_NO, uiModeManager.nightMode)
            assertAppearance(dark = true)

            selectMode(ThemeMode.SYSTEM)
            waitForNativeAppearance(dark = false)
            assertAppearance(dark = false)

            uiModeManager.nightMode = UiModeManager.MODE_NIGHT_YES
            waitForNativeAppearance(dark = true)
            assertSelectedMode(ThemeMode.SYSTEM)
            assertAppearance(dark = true)

            selectMode(ThemeMode.LIGHT)
            waitForNativeAppearance(dark = false)
            assertEquals(UiModeManager.MODE_NIGHT_YES, uiModeManager.nightMode)
            assertAppearance(dark = false)

            selectMode(ThemeMode.SYSTEM)
            waitForNativeAppearance(dark = true)
            assertAppearance(dark = true)

            uiModeManager.nightMode = UiModeManager.MODE_NIGHT_NO
            waitForNativeAppearance(dark = false)
            assertSelectedMode(ThemeMode.SYSTEM)
            assertAppearance(dark = false)
        } finally {
            try {
                uiModeManager.nightMode = originalSystemMode
                composeRule.waitUntil(10_000) { uiModeManager.nightMode == originalSystemMode }
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
    }

    @Test
    fun selectedModeSurvivesRecreationAndIsSharedWithNewViewModels() {
        openSettings()
        selectMode(ThemeMode.DARK)
        composeRule.activityRule.scenario.recreate()

        waitForSettings()
        assertSelectedMode(ThemeMode.DARK)
        assertAppearance(dark = true)
        val secondViewModel = newViewModel("second")
        assertEquals(ThemeMode.DARK, secondViewModel.themeMode.value)
        assertEquals(ThemeMode.DARK, AppearancePreferences(context).read())

        instrumentation.runOnMainSync { secondViewModel.setThemeMode(ThemeMode.LIGHT) }
        val firstViewModel = activityViewModel()
        composeRule.waitUntil(5_000) { firstViewModel.themeMode.value == ThemeMode.LIGHT }
        waitForSettings()
        assertSelectedMode(ThemeMode.LIGHT)
        assertAppearance(dark = false)
        assertEquals(AppScreen.SETTINGS, firstViewModel.state.value.screen)
        assertEquals(AppScreen.HOME, secondViewModel.state.value.screen)
    }

    @Test
    fun themeSwitchPreservesSharedImageConfigurationAndWorkflowResetPreservesTheme() {
        openSettings()
        selectMode(ThemeMode.DARK)
        val viewModel = activityViewModel()
        val file = File(context.filesDir, "appearance-share-${UUID.randomUUID()}.png").also(fixtures::add)
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF447755.toInt())
        }
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setComponent(ComponentName(context, "com.poppinzhang.natsuko.share.ImageMergeShare"))
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        instrumentation.runOnMainSync { viewModel.handleShareIntent(intent) }
        composeRule.waitUntil(5_000) { viewModel.state.value.screen == AppScreen.CONFIGURE }
        instrumentation.runOnMainSync { viewModel.updateMergeDirection(MergeDirection.HORIZONTAL) }
        val configured = viewModel.state.value
        assertEquals(Feature.IMAGE_MERGE, configured.feature)
        assertEquals(1, configured.inputs.size)
        assertEquals(ThemeMode.DARK, viewModel.themeMode.value)

        instrumentation.runOnMainSync { viewModel.openSettings() }
        selectMode(ThemeMode.LIGHT)
        instrumentation.runOnMainSync { viewModel.back() }
        assertEquals(configured, viewModel.state.value)
        assertEquals(ThemeMode.LIGHT, viewModel.themeMode.value)

        instrumentation.runOnMainSync { viewModel.reset() }
        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
        assertEquals(ThemeMode.LIGHT, viewModel.themeMode.value)
        assertEquals(ThemeMode.LIGHT, preferences.read())
    }

    private fun openSettings() {
        composeRule.onNodeWithTag("home-settings").performClick()
        waitForSettings()
    }

    private fun selectMode(mode: ThemeMode) {
        waitForSettings()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("theme-mode-${mode.preferenceValue}"))
        composeRule.onNodeWithTag("theme-mode-${mode.preferenceValue}").performClick()
        waitForSettings()
        assertSelectedMode(mode)
        assertEquals(mode, preferences.read())
    }

    private fun waitForSettings() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("theme-mode-system").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun assertSelectedMode(selected: ThemeMode) {
        ThemeMode.entries.forEach { mode ->
            val node = composeRule.onNodeWithTag("theme-mode-${mode.preferenceValue}")
            if (mode == selected) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    private fun assertAppearance(dark: Boolean) {
        composeRule.waitForIdle()
        val backgroundLuminance = composeRule.onRoot().captureToImage().toPixelMap()[0, 0].luminance()
        assertTrue("Expected ${if (dark) "dark" else "light"} app background, got $backgroundLuminance",
            if (dark) backgroundLuminance < 0.1f else backgroundLuminance > 0.7f)
        composeRule.activityRule.scenario.onActivity { activity ->
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertEquals(!dark, controller.isAppearanceLightStatusBars)
            assertEquals(!dark, controller.isAppearanceLightNavigationBars)
        }
    }

    private fun waitForNativeAppearance(dark: Boolean) {
        composeRule.waitUntil(10_000) {
            var matches = false
            composeRule.activityRule.scenario.onActivity { activity ->
                val nightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                matches = nightMode == if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            matches
        }
        composeRule.waitForIdle()
    }

    private fun activityViewModel(): NatsukoViewModel {
        lateinit var result: NatsukoViewModel
        composeRule.activityRule.scenario.onActivity {
            result = ViewModelProvider(it)[NatsukoViewModel::class.java]
        }
        return result
    }

    private fun newViewModel(key: String): NatsukoViewModel {
        lateinit var result: NatsukoViewModel
        instrumentation.runOnMainSync {
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            result = ViewModelProvider(viewModelStore, factory)[key, NatsukoViewModel::class.java]
        }
        return result
    }
}
