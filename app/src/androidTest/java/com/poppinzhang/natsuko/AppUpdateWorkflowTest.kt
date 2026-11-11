package com.poppinzhang.natsuko

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.update.AppRelease
import com.poppinzhang.natsuko.data.update.AppUpdater
import com.poppinzhang.natsuko.data.update.ReleaseApk
import com.poppinzhang.natsuko.data.update.ReleaseVersion
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.AppUpdateState
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings entry drives a scripted updater: nothing is requested from GitHub or installed. */
@RunWith(AndroidJUnit4::class)
class AppUpdateWorkflowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = ViewModelStore()
    private val updater = ScriptedUpdater()
    private val release = AppRelease(
        version = ReleaseVersion(9, 0, 0),
        pageUrl = "https://github.com/poppinzhang/Natsuko/releases/tag/v9.0.0",
        apk = ReleaseApk("https://github.com/poppinzhang/Natsuko/releases/download/v9.0.0/Natsuko-9.0.0.apk", 4_000_000, null),
    )
    private val apk = File(context.cacheDir, "update/natsuko-9.0.0.apk")
    private lateinit var viewModel: NatsukoViewModel

    @Before
    fun openSettings() {
        instrumentation.runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NatsukoViewModel(context.applicationContext as Application, appUpdater = updater) as T
            }
            viewModel = ViewModelProvider(store, factory)["app-update", NatsukoViewModel::class.java]
        }
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        composeRule.onNodeWithTag("home-settings").performClick()
        showUpdateSetting()
    }

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync { store.clear() }
    }

    @Test
    fun settingsShowTheInstalledVersionAndReportACurrentInstallation() {
        composeRule.onNodeWithTag("update-current-version")
            .assertTextEquals(context.getString(R.string.update_current_version, BuildConfig.VERSION_NAME))
        assertStatus(R.string.update_hint)
        // Opening settings never contacts the network on its own.
        assertEquals(0, updater.checks)

        composeRule.onNodeWithTag("check-update").assertIsEnabled().performClick()
        assertStatus(R.string.update_checking)
        composeRule.onNodeWithTag("check-update").assertIsNotEnabled()
        assertEquals(1, updater.checks)

        updater.checked.complete(null)
        awaitUpdate { it == AppUpdateState.UpToDate }
        assertStatus(R.string.update_up_to_date)
        composeRule.onNodeWithTag("check-update").assertIsEnabled()
        assertNull(viewModel.state.value.errorMessage)

        // The result of an earlier check is not shown again when settings are reopened.
        instrumentation.runOnMainSync { viewModel.back() }
        composeRule.onNodeWithTag("home-settings").performClick()
        showUpdateSetting()
        assertStatus(R.string.update_hint)
    }

    @Test
    fun aNewerReleaseAsksBeforeDownloadingAndStaysOfferedAcrossScreens() {
        offerRelease()

        val offer = context.getString(R.string.update_available_title, "9.0.0")
        composeRule.onNodeWithTag("update-available-title").assertIsDisplayed().assertTextEquals(offer)
        composeRule.onNodeWithTag("update-status").assertTextEquals(offer)
        composeRule.onNodeWithText(
            context.getString(R.string.update_available_message, BuildConfig.VERSION_NAME, formatSize(4_000_000)),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("update-release-notes").assertIsDisplayed()
        // The offer belongs to the app, not to the settings screen or the current file workflow.
        instrumentation.runOnMainSync { viewModel.back() }
        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
        composeRule.onNodeWithTag("update-install").assertIsDisplayed()

        composeRule.onNodeWithTag("update-later").performClick()

        awaitUpdate { it == AppUpdateState.Idle }
        assertGone("update-install")
        assertEquals(0, updater.downloads)
    }

    @Test
    fun acceptingShowsDownloadProgressAndHandsThePackageToAndroid() {
        offerRelease()
        composeRule.onNodeWithTag("update-install").performClick()

        awaitUpdate { it is AppUpdateState.Downloading }
        assertProgress(context.getString(R.string.update_download_progress, formatSize(0), formatSize(4_000_000)))
        updater.onBytes(1_000_000)
        awaitUpdate { (it as? AppUpdateState.Downloading)?.bytes == 1_000_000L }
        assertProgress(context.getString(R.string.update_download_progress, formatSize(1_000_000), formatSize(4_000_000)))
        // Installing closes the app, so nothing else may start meanwhile.
        instrumentation.runOnMainSync {
            viewModel.back()
            viewModel.openTwitterDownload()
            viewModel.acceptSelection(Feature.IMAGE_MERGE, listOf(Uri.parse("content://com.poppinzhang.natsuko.test/image")))
        }
        assertEquals(AppScreen.SETTINGS, viewModel.state.value.screen)
        assertEquals(text(R.string.backend_wait_before_import), viewModel.state.value.errorMessage)
        assertTrue(viewModel.appUpdate.state.value is AppUpdateState.Downloading)
        instrumentation.runOnMainSync { viewModel.dismissError() }

        updater.downloaded.complete(apk)
        awaitUpdate { it is AppUpdateState.Installing }
        assertProgress(context.getString(R.string.update_installing_message))
        assertSame(apk, updater.installing)

        // Android reports that the user declined its confirmation.
        updater.installed.complete(false)
        awaitUpdate { it == AppUpdateState.Idle }
        assertGone("update-progress")
        assertNull(viewModel.state.value.errorMessage)
        instrumentation.runOnMainSync { viewModel.back() }
        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
    }

    @Test
    fun cancellingTheDownloadStopsItAndReturnsToSettings() {
        offerRelease()
        composeRule.onNodeWithTag("update-install").performClick()
        awaitUpdate { it is AppUpdateState.Downloading }

        composeRule.onNodeWithTag("update-cancel").performClick()

        awaitUpdate { it == AppUpdateState.Idle }
        assertGone("update-progress")
        assertTrue(updater.downloaded.isCancelled)
        assertNull(viewModel.state.value.errorMessage)
        assertStatus(R.string.update_hint)
        composeRule.onNodeWithTag("check-update").assertIsEnabled()
    }

    @Test
    fun failuresAreExplainedAndTheCheckCanBeRepeated() {
        updater.checked.completeExceptionally(LocalizedIllegalStateException(text(R.string.update_error_rate_limited)))
        composeRule.onNodeWithTag("check-update").performClick()

        composeRule.waitUntil(5_000) { viewModel.state.value.errorMessage != null }
        composeRule.onNodeWithText(context.getString(R.string.update_error_rate_limited)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.main_ok)).performClick()
        assertStatus(R.string.update_hint)

        updater.checked = CompletableDeferred(release)
        updater.downloaded.completeExceptionally(LocalizedIllegalStateException(text(R.string.update_error_checksum)))
        composeRule.onNodeWithTag("check-update").performClick()
        awaitUpdate { it is AppUpdateState.Available }
        composeRule.onNodeWithTag("update-install").performClick()

        composeRule.waitUntil(5_000) { viewModel.state.value.errorMessage != null }
        composeRule.onNodeWithText(context.getString(R.string.update_error_checksum)).assertIsDisplayed()
        assertEquals(AppUpdateState.Idle, viewModel.appUpdate.state.value)
        assertGone("update-progress")
        assertEquals(2, updater.checks)
    }

    @Test
    fun aSystemConfirmationThatCannotBeOpenedAbandonsTheInstallation() {
        offerRelease()
        updater.downloaded.complete(apk)
        composeRule.onNodeWithTag("update-install").performClick()
        awaitUpdate { it is AppUpdateState.Installing }

        // No activity handles this action, as on a device without a package installer.
        updater.onUserAction(Intent("com.poppinzhang.natsuko.test.NO_SUCH_CONFIRMATION"))

        composeRule.waitUntil(5_000) { viewModel.state.value.errorMessage != null }
        assertEquals(text(R.string.update_error_install_failed), viewModel.state.value.errorMessage)
        awaitUpdate { it == AppUpdateState.Idle }
        assertTrue(updater.installed.isCancelled)
    }

    private fun offerRelease() {
        updater.checked.complete(release)
        composeRule.onNodeWithTag("check-update").performClick()
        awaitUpdate { it is AppUpdateState.Available }
    }

    private fun showUpdateSetting() {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("check-update"))
    }

    private fun awaitUpdate(condition: (AppUpdateState) -> Boolean) {
        composeRule.waitUntil(5_000) { condition(viewModel.appUpdate.state.value) }
        composeRule.waitForIdle()
    }

    private fun assertStatus(resource: Int) {
        composeRule.onNodeWithTag("update-status").assertTextEquals(context.getString(resource))
    }

    private fun assertProgress(message: String) {
        composeRule.onNodeWithTag("update-progress").assertTextEquals(message)
    }

    private fun assertGone(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    private fun formatSize(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    private class ScriptedUpdater : AppUpdater {
        @Volatile var checked = CompletableDeferred<AppRelease?>()
        val downloaded = CompletableDeferred<File>()
        val installed = CompletableDeferred<Boolean>()
        @Volatile var checks = 0
        @Volatile var downloads = 0
        @Volatile var installing: File? = null
        @Volatile lateinit var onBytes: (Long) -> Unit
        @Volatile lateinit var onUserAction: (Intent) -> Unit

        override suspend fun check(): AppRelease? {
            checks++
            return checked.await()
        }

        override suspend fun download(release: AppRelease, onBytes: (Long) -> Unit): File {
            downloads++
            this.onBytes = onBytes
            return awaitOrCancel(downloaded)
        }

        override suspend fun install(apk: File, onUserAction: (Intent) -> Unit): Boolean {
            installing = apk
            this.onUserAction = onUserAction
            return awaitOrCancel(installed)
        }

        /** Records that cancelling the caller reached the running download or installation. */
        private suspend fun <T> awaitOrCancel(result: CompletableDeferred<T>): T = try {
            result.await()
        } catch (cancelled: CancellationException) {
            result.cancel()
            throw cancelled
        }
    }
}
