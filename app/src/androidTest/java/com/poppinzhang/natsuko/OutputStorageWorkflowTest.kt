package com.poppinzhang.natsuko

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutputStorageWorkflowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = OutputStoragePreferences(context)
    private val viewModels = mutableListOf<NatsukoViewModel>()
    private val fixtures = mutableListOf<File>()
    private val outputs = mutableListOf<OutputArtifact>()

    @Before
    fun resetPreferences() {
        preferences.setEnabled(false)
        preferences.setDirectory(null)
    }

    @After
    fun cleanUp() = runBlocking {
        viewModels.forEach { viewModel ->
            awaitState(viewModel) { !it.isProcessing && !it.isSavingToUserStorage }
            outputs += viewModel.state.value.outcome?.outputs.orEmpty()
            viewModel.reset()
        }
        outputs.mapNotNull { it.userStorage?.uri }.distinct().forEach { uri ->
            context.contentResolver.delete(uri, null, null)
        }
        outputs.map { it.file }.distinct().forEach { it.delete() }
        fixtures.forEach { it.delete() }
        resetPreferences()
    }

    @Test
    fun disabledSettingKeepsFinishedOutputInAppStorage() = runBlocking {
        val viewModel = newViewModel()
        val result = processImage(viewModel)
        val output = result.outcome!!.outputs.single()

        assertFalse(result.storageSettings.enabled)
        assertFalse(preferences.read().enabled)
        assertNull(output.userStorage)
        assertTrue(output.file.isFile)
        val privateRoot = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, Environment.DIRECTORY_PICTURES)
        assertTrue(output.file.canonicalPath.startsWith(privateRoot.canonicalPath + File.separator))
    }

    @Test
    fun resultSwitchSavesCurrentResultAndAutomaticallySavesNextBatch() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        processImage(viewModel)

        composeRule.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("result-user-storage-switch"))
        composeRule.onNodeWithTag("result-user-storage-switch").assertIsOff().performClick()
        val firstResult = awaitState(viewModel) {
            it.storageSettings.enabled && !it.isSavingToUserStorage &&
                it.outcome?.outputs?.all { output -> output.userStorage != null } == true
        }
        rememberOutputs(firstResult)
        assertTrue(preferences.read().enabled)
        assertTrue(firstResult.storageFailures.isEmpty())
        assertPublicCopyMatches(firstResult.outcome!!.outputs.single())

        viewModel.reset()
        val secondResult = processImage(viewModel, expectPublicCopy = true)
        assertTrue(secondResult.storageSettings.enabled)
        assertTrue(secondResult.storageFailures.isEmpty())
        assertPublicCopyMatches(secondResult.outcome!!.outputs.single())
        assertTrue(
            firstResult.outcome!!.outputs.single().userStorage!!.uri !=
                secondResult.outcome!!.outputs.single().userStorage!!.uri,
        )
    }

    @Test
    fun settingsSwitchPersistsAcrossNavigationSelectionResetAndNewViewModel() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }

        composeRule.onNodeWithTag("home-settings").performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("user-storage-directory"))
        composeRule.onNodeWithTag("user-storage-directory").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("user-storage-switch"))
        composeRule.onNodeWithTag("user-storage-switch").assertIsOff().performClick()
        awaitState(viewModel) { it.storageSettings.enabled }
        val enabledSettings = viewModel.state.value.storageSettings
        assertEquals(enabledSettings, preferences.read())

        viewModel.back()
        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
        assertEquals(enabledSettings, viewModel.state.value.storageSettings)
        viewModel.acceptSelection(Feature.IMAGE_MERGE, listOf(createImage()))
        assertEquals(enabledSettings, awaitState(viewModel) { it.screen == AppScreen.CONFIGURE }.storageSettings)
        viewModel.reset()
        assertEquals(enabledSettings, viewModel.state.value.storageSettings)
        assertEquals(enabledSettings, newViewModel().state.value.storageSettings)

        viewModel.openSettings()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("user-storage-switch"))
        composeRule.onNodeWithTag("user-storage-switch").performClick()
        awaitState(viewModel) { !it.storageSettings.enabled }
        assertFalse(preferences.read().enabled)
        assertFalse(newViewModel().state.value.storageSettings.enabled)
    }

    @Test
    fun existingViewModelsStayInSyncWhenStorageSettingsChange() = runBlocking {
        val first = newViewModel()
        val second = newViewModel()
        first.openSettings()
        second.openSettings()

        first.setSaveToUserStorage(true)
        val secondEnabled = awaitState(second) { it.storageSettings.enabled }
        assertEquals(first.state.value.storageSettings, secondEnabled.storageSettings)

        second.setSaveToUserStorage(false)
        val firstDisabled = awaitState(first) { !it.storageSettings.enabled }
        assertEquals(second.state.value.storageSettings, firstDisabled.storageSettings)
        assertFalse(preferences.read().enabled)

        val customDirectory = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ANatsuko-sync-test",
        )
        preferences.setDirectory(customDirectory, "同步测试目录")
        awaitState(first) { it.storageSettings.treeUri == customDirectory }
        val secondCustom = awaitState(second) { it.storageSettings.treeUri == customDirectory }
        assertEquals("同步测试目录", secondCustom.storageSettings.directoryLabel)
        assertEquals(first.state.value.storageSettings, secondCustom.storageSettings)

        second.restoreDefaultUserStorageDirectory()
        val firstDefault = awaitState(first) { it.storageSettings.treeUri == null }
        assertEquals("Download/Natsuko", firstDefault.storageSettings.directoryLabel)
        assertEquals(second.state.value.storageSettings, firstDefault.storageSettings)
        assertEquals(preferences.read(), firstDefault.storageSettings)
        assertEquals(AppScreen.SETTINGS, firstDefault.screen)
        assertEquals(AppScreen.SETTINGS, second.state.value.screen)
    }

    @Test
    fun cancellingDirectoryPickerPreservesDisabledAndEnabledSettings() = runBlocking {
        for (enabled in listOf(false, true)) {
            preferences.setEnabled(enabled)
            val viewModel = newViewModel()
            val originalSettings = viewModel.state.value.storageSettings
            viewModel.openSettings()
            viewModel.requestUserStorageDirectory()
            assertTrue(viewModel.state.value.directoryPickerRequested)
            viewModel.onDirectoryPickerLaunched()
            assertFalse(viewModel.state.value.directoryPickerRequested)

            viewModel.onUserStorageDirectorySelected(null)

            assertEquals(originalSettings, viewModel.state.value.storageSettings)
            assertEquals(originalSettings, preferences.read())
            assertEquals(AppScreen.SETTINGS, viewModel.state.value.screen)
        }
    }

    @Test
    fun enablingInaccessibleCustomDirectoryRequestsPermissionAndCancellationKeepsItDisabled() = runBlocking {
        val inaccessibleDirectory = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ANatsuko-no-grant",
        )
        preferences.setDirectory(inaccessibleDirectory)
        val viewModel = newViewModel()
        val originalSettings = viewModel.state.value.storageSettings
        viewModel.openSettings()

        viewModel.setSaveToUserStorage(true)
        val pending = awaitState(viewModel) { it.directoryPickerRequested }
        assertFalse(pending.storageSettings.enabled)
        assertFalse(preferences.read().enabled)
        viewModel.onDirectoryPickerLaunched()
        viewModel.onUserStorageDirectorySelected(null)

        assertEquals(originalSettings, viewModel.state.value.storageSettings)
        assertEquals(originalSettings, preferences.read())
        assertFalse(viewModel.state.value.directoryPickerRequested)
    }

    private fun newViewModel() = NatsukoViewModel(context.applicationContext as Application)
        .also(viewModels::add)

    private suspend fun processImage(
        viewModel: NatsukoViewModel,
        expectPublicCopy: Boolean = false,
    ): NatsukoUiState {
        viewModel.acceptSelection(Feature.IMAGE_MERGE, listOf(createImage()))
        awaitState(viewModel) { it.screen == AppScreen.CONFIGURE }
        viewModel.process()
        return awaitState(viewModel) {
            it.screen == AppScreen.RESULT && !it.isProcessing && !it.isSavingToUserStorage &&
                (!expectPublicCopy || it.outcome?.outputs?.all { output -> output.userStorage != null } == true)
        }.also { state ->
            rememberOutputs(state)
            assertNull(state.errorMessage)
            val outcome = checkNotNull(state.outcome)
            assertTrue(outcome.failures.isEmpty())
            assertEquals(1, outcome.outputs.size)
        }
    }

    private suspend fun awaitState(
        viewModel: NatsukoViewModel,
        predicate: (NatsukoUiState) -> Boolean,
    ) = withTimeout(15_000) { viewModel.state.first(predicate) }

    private fun rememberOutputs(state: NatsukoUiState) {
        outputs += state.outcome?.outputs.orEmpty()
    }

    private fun assertPublicCopyMatches(artifact: OutputArtifact) {
        val saved = artifact.userStorage
        assertNotNull(saved)
        assertEquals("Download/Natsuko/ImageMerge", saved!!.directoryLabel)
        val uri = saved.uri
        assertEquals("content", uri.scheme)
        context.contentResolver.openInputStream(uri).use { input ->
            assertNotNull(input)
            assertArrayEquals(artifact.file.readBytes(), input!!.readBytes())
        }
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null,
        ).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
            assertEquals("${Environment.DIRECTORY_DOWNLOADS}/Natsuko/ImageMerge/", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
    }

    private fun createImage(): Uri {
        val file = File(context.filesDir, "storage-workflow-${UUID.randomUUID()}.png")
        fixtures += file
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFB84E62.toInt())
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
