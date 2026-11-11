package com.poppinzhang.natsuko

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.data.OutputStorageSettings
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.processing.location.LocationConflictDecision
import com.poppinzhang.natsuko.processing.location.LocationMetadataEngine
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import java.io.File
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
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end state transitions use the bundled engine and a real GPS / place-name fixture. */
@RunWith(AndroidJUnit4::class)
class CopyLocationWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = OutputStoragePreferences(context)
    private val store = ViewModelStore()
    private val viewModels = mutableListOf<NatsukoViewModel>()
    private val fixtures = mutableListOf<File>()
    private val outputs = mutableListOf<OutputArtifact>()
    private val engine = LocationMetadataEngine(context)
    private lateinit var originalSettings: OutputStorageSettings

    @Before
    fun disablePublicCopies() {
        originalSettings = preferences.read()
        preferences.setEnabled(false)
    }

    @After
    fun cleanUp() = runBlocking {
        try {
            for (viewModel in viewModels) {
                instrumentation.runOnMainSync { viewModel.cancelProcessing() }
                awaitState(viewModel) { !it.isProcessing && !it.isSavingToUserStorage }
                outputs += viewModel.state.value.outcome?.outputs.orEmpty()
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            outputs.mapNotNull { it.userStorage?.uri }.distinct().forEach {
                runCatching { context.contentResolver.delete(it, null, null) }
            }
            outputs.map { it.file }.distinct().forEach { it.delete() }
            outputs.mapNotNull { it.file.parentFile }.distinct().forEach { it.delete() }
            fixtures.forEach { it.delete() }
            preferences.setDirectory(originalSettings.treeUri, originalSettings.directoryLabel)
            preferences.setEnabled(originalSettings.enabled)
        }
    }

    @Test
    fun singleSourceStartsTargetSelectionAndAutomaticallyReturnsHomeAfterCopy() = runBlocking {
        val viewModel = newViewModel()
        val originalSource = sourceFile()
        val sourceBytes = originalSource.readBytes()
        instrumentation.runOnMainSync { viewModel.importLocationSources(listOf(uri(originalSource))) }
        val ready = awaitSource(viewModel)
        assertEquals(Feature.COPY_LOCATION, ready.feature)
        assertTrue(ready.copyLocation!!.targetsPickerRequested)
        assertTrue(ready.copyLocation.source!!.location.displayText.contains("Tokyo"))
        assertNull(ready.outcome)
        val stagedSource = ready.copyLocation.source!!.file

        val target = plainImage()
        val targetBytes = target.readBytes()
        instrumentation.runOnMainSync {
            viewModel.onLocationTargetsPickerLaunched()
            viewModel.acceptLocationTargets(listOf(uri(target)))
        }
        val completed = awaitOutcome(viewModel)

        assertSuccessfulHome(completed, 1)
        val output = completed.outcome!!.outputs.single()
        val location = engine.read(output.file)
        assertNotNull(location)
        assertEquals(35.6717, checkNotNull(location).latitude!!, 0.000001)
        assertEquals(139.6949, location.longitude!!, 0.000001)
        assertTrue(location.displayText.contains("Tokyo"))
        assertTrue(location.displayText.contains("代々木公園"))
        val picturesRoot = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, Environment.DIRECTORY_PICTURES)
        assertTrue(output.file.canonicalPath.startsWith(picturesRoot.canonicalPath + File.separator))
        assertArrayEquals(targetBytes, target.readBytes())
        assertArrayEquals(sourceBytes, originalSource.readBytes())
        assertFalse(stagedSource.exists())
        assertNull(completed.copyLocation)
    }

    @Test
    fun singleShareUsesSharedImageAsSourceAndMultipleShareRequiresEligibleChoice() = runBlocking {
        val single = newViewModel()
        val shared = sourceFile()
        instrumentation.runOnMainSync {
            single.handleShareIntent(shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri(shared)))
        }
        val singleReady = awaitSource(single)
        assertEquals(uri(shared), singleReady.copyLocation!!.source!!.input.uri)

        val multiple = newViewModel()
        val invalid = plainImage()
        instrumentation.runOnMainSync {
            multiple.handleShareIntent(shareIntent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri(invalid), uri(shared))))
        }
        val choices = awaitState(multiple) { it.copyLocation?.candidates?.size == 2 && it.copyLocation.isLoading.not() }
        assertEquals(AppScreen.COPY_LOCATION_SOURCE, choices.screen)
        assertNull(choices.copyLocation!!.source)
        assertNull(choices.copyLocation.candidates.first().source)
        assertNotNull(choices.copyLocation.candidates.first().error)
        val accepted = checkNotNull(choices.copyLocation.candidates[1].source)
        instrumentation.runOnMainSync { multiple.chooseLocationSource(accepted) }
        assertEquals(accepted, awaitSource(multiple).copyLocation!!.source)
    }

    @Test
    fun sourceWithoutGpsCannotAdvanceAndReturningHomeClearsValidationState() = runBlocking {
        val viewModel = newViewModel()
        instrumentation.runOnMainSync { viewModel.importLocationSources(listOf(uri(plainImage()))) }
        val rejected = awaitState(viewModel) { it.copyLocation?.candidates?.size == 1 && it.copyLocation.isLoading.not() }
        assertEquals(AppScreen.COPY_LOCATION_SOURCE, rejected.screen)
        assertNull(rejected.copyLocation!!.source)
        assertNull(rejected.copyLocation.candidates.single().source)
        assertNotNull(rejected.copyLocation.candidates.single().error)
        assertFalse(rejected.copyLocation.targetsPickerRequested)
        assertNull(rejected.outcome)
        instrumentation.runOnMainSync { viewModel.back() }
        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
        assertNull(viewModel.state.value.copyLocation)
    }

    @Test
    fun malformedOrUnsupportedReplacementSharePreservesAcceptedSource() = runBlocking {
        val viewModel = newViewModel()
        val ready = selectSource(viewModel)
        val staged = ready.copyLocation!!.source!!.file
        instrumentation.runOnMainSync { viewModel.handleShareIntent(shareIntent(Intent.ACTION_SEND)) }
        assertNotNull(viewModel.state.value.errorMessage)
        assertEquals(AppScreen.COPY_LOCATION_TARGETS, viewModel.state.value.screen)
        assertTrue(staged.isFile)
        val target = plainImage()
        instrumentation.runOnMainSync {
            viewModel.dismissError()
            viewModel.handleShareIntent(
                Intent(Intent.ACTION_SEND)
                    .setComponent(ComponentName(context, "com.poppinzhang.natsuko.share.PdfToImagesShare"))
                    .putExtra(Intent.EXTRA_STREAM, uri(target)),
            )
        }
        awaitState(viewModel) { it.errorMessage != null }
        assertEquals(AppScreen.COPY_LOCATION_TARGETS, viewModel.state.value.screen)
        assertTrue(staged.isFile)
        instrumentation.runOnMainSync {
            viewModel.dismissError()
            viewModel.acceptLocationTargets(listOf(uri(target)))
        }
        assertSuccessfulHome(awaitOutcome(viewModel), 1)
    }

    @Test
    fun existingLocationPausesForDecisionAndSkipReturnsNeutralReviewStatus() = runBlocking {
        val viewModel = newViewModel()
        selectSource(viewModel)
        val target = sourceFile()
        val original = target.readBytes()
        instrumentation.runOnMainSync { viewModel.acceptLocationTargets(listOf(uri(target))) }
        val paused = awaitState(viewModel) { it.copyLocation?.conflict != null }
        assertTrue(paused.isProcessing)
        assertEquals(target.name, paused.copyLocation!!.conflict!!.target.displayName)
        assertNotNull(paused.copyLocation.conflict!!.before.latitude)
        instrumentation.runOnMainSync { viewModel.chooseLocationConflict(LocationConflictDecision.SKIP) }
        val result = awaitOutcome(viewModel)
        assertEquals(AppScreen.RESULT, result.screen)
        assertTrue(result.outcome!!.outputs.isEmpty())
        assertEquals(target.name, result.outcome.skipped.single().source.resolve(context))
        assertTrue(result.outcome.failures.isEmpty())
        assertArrayEquals(original, target.readBytes())
    }

    @Test
    fun cancellingConflictKeepsSourceAvailableForAnotherTargetSelection() = runBlocking {
        val viewModel = newViewModel()
        val source = checkNotNull(selectSource(viewModel).copyLocation!!.source)
        val existing = sourceFile()
        instrumentation.runOnMainSync { viewModel.acceptLocationTargets(listOf(uri(existing))) }
        awaitState(viewModel) { it.copyLocation?.conflict != null }
        instrumentation.runOnMainSync { viewModel.cancelProcessing() }
        val cancelled = awaitState(viewModel) { !it.isProcessing }
        assertEquals(AppScreen.COPY_LOCATION_TARGETS, cancelled.screen)
        assertNull(cancelled.copyLocation!!.conflict)
        assertTrue(source.file.isFile)
        val plain = plainImage()
        instrumentation.runOnMainSync { viewModel.acceptLocationTargets(listOf(uri(plain))) }
        assertSuccessfulHome(awaitOutcome(viewModel), 1)
    }

    @Test
    fun automaticOutputCanAlsoBeSavedToConfiguredUserStorage() = runBlocking {
        preferences.setDirectory(null)
        preferences.setEnabled(true)
        val viewModel = newViewModel()
        selectSource(viewModel)
        val target = plainImage()
        instrumentation.runOnMainSync { viewModel.acceptLocationTargets(listOf(uri(target))) }
        val result = awaitState(viewModel) {
            !it.isProcessing && !it.isSavingToUserStorage && it.outcome?.outputs?.singleOrNull()?.userStorage != null
        }
        outputs += result.outcome!!.outputs
        assertSuccessfulHome(result, 1)
        assertTrue(result.storageFailures.isEmpty())
        val output = result.outcome.outputs.single()
        val saved = context.contentResolver.openInputStream(output.userStorage!!.uri)!!.use { it.readBytes() }
        assertArrayEquals(output.file.readBytes(), saved)
    }

    private suspend fun selectSource(viewModel: NatsukoViewModel): NatsukoUiState {
        val file = sourceFile()
        instrumentation.runOnMainSync { viewModel.importLocationSources(listOf(uri(file))) }
        return awaitSource(viewModel)
    }

    private fun newViewModel(): NatsukoViewModel {
        lateinit var viewModel: NatsukoViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[
                "copy-location-${viewModels.size}", NatsukoViewModel::class.java,
            ]
        }
        viewModels += viewModel
        return viewModel
    }

    private suspend fun awaitSource(viewModel: NatsukoViewModel) = awaitState(viewModel) {
        it.screen == AppScreen.COPY_LOCATION_TARGETS && it.copyLocation?.source != null && !it.copyLocation.isLoading
    }

    private suspend fun awaitOutcome(viewModel: NatsukoViewModel) = awaitState(viewModel) {
        it.outcome != null && !it.isProcessing && !it.isSavingToUserStorage
    }.also { outputs += it.outcome!!.outputs }

    private suspend fun awaitState(viewModel: NatsukoViewModel, predicate: (NatsukoUiState) -> Boolean): NatsukoUiState =
        withTimeout(45_000) { viewModel.state.first(predicate) }

    private fun assertSuccessfulHome(state: NatsukoUiState, expectedCount: Int) {
        assertEquals(AppScreen.HOME, state.screen)
        assertFalse(state.isProcessing)
        assertNull(state.errorMessage)
        assertNotNull(state.successMessage)
        assertEquals(expectedCount, state.outcome!!.outputs.size)
        assertTrue(state.outcome.failures.isEmpty())
        assertTrue(state.outcome.skipped.isEmpty())
    }

    private fun sourceFile(): File = fixture("jpg").also { file ->
        instrumentation.context.assets.open("location/source.jpg").use { input -> file.outputStream().use(input::copyTo) }
    }

    private fun plainImage(): File = fixture("png").also { file ->
        val image = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(0xff665544.toInt())
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun fixture(extension: String) = File(context.filesDir, "location-workflow-${UUID.randomUUID()}.$extension").also { fixtures += it }
    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    private fun shareIntent(action: String) = Intent(action)
        .setComponent(ComponentName(context, "com.poppinzhang.natsuko.share.CopyLocationShare"))
        .setType("image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
