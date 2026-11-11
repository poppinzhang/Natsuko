package com.poppinzhang.natsuko.ui

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.checkText
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poppinzhang.natsuko.data.AppearancePreferences
import com.poppinzhang.natsuko.data.DocumentResolver
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.data.OutputStorageSettings
import com.poppinzhang.natsuko.data.ShareImportParser
import com.poppinzhang.natsuko.data.UserStorageRepository
import com.poppinzhang.natsuko.data.update.AppUpdater
import com.poppinzhang.natsuko.data.update.GitHubAppUpdater
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import com.poppinzhang.natsuko.data.twitter.TwitterDownloadScope
import com.poppinzhang.natsuko.data.twitter.TwitterVideoSelection
import com.poppinzhang.natsuko.model.AdjustmentEdits
import com.poppinzhang.natsuko.model.AdjustmentExportSettings
import com.poppinzhang.natsuko.model.AdjustmentHistory
import com.poppinzhang.natsuko.model.AdjustmentSource
import com.poppinzhang.natsuko.model.AdjustmentTool
import com.poppinzhang.natsuko.model.CropAspect
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.GifOptions
import com.poppinzhang.natsuko.model.ImageOutputFormat
import com.poppinzhang.natsuko.model.ImagesToPdfOptions
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.model.MergeOptions
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.PdfOrientation
import com.poppinzhang.natsuko.model.PdfOutputMode
import com.poppinzhang.natsuko.model.PdfPagePreset
import com.poppinzhang.natsuko.model.PdfToImagesOptions
import com.poppinzhang.natsuko.model.PixelRect
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingProgress
import com.poppinzhang.natsuko.model.ThemeMode
import com.poppinzhang.natsuko.processing.ImageMergeProcessor
import com.poppinzhang.natsuko.processing.CropBlackBorderProcessor
import com.poppinzhang.natsuko.processing.ImagesToPdfProcessor
import com.poppinzhang.natsuko.processing.PdfToImagesProcessor
import com.poppinzhang.natsuko.processing.VideoToGifProcessor
import com.poppinzhang.natsuko.processing.DownloadTwitterVideoProcessor
import com.poppinzhang.natsuko.processing.AdjustmentEditing
import com.poppinzhang.natsuko.processing.AdjustmentExportRequest
import com.poppinzhang.natsuko.processing.AdjustmentGeometry
import com.poppinzhang.natsuko.processing.AdjustmentTooLargeException
import com.poppinzhang.natsuko.processing.ImageAdjustmentProcessor
import com.poppinzhang.natsuko.processing.ImageAdjustmentSession
import com.poppinzhang.natsuko.processing.location.CopyLocationProcessor
import com.poppinzhang.natsuko.processing.location.LocationConflictDecision
import com.poppinzhang.natsuko.processing.location.LocationSource
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { HOME, SETTINGS, ABOUT, CONFIGURE, TWITTER_DOWNLOAD, RESULT, ADJUST_CHOOSE, ADJUST_EDIT, ADJUST_EXPORT, COPY_LOCATION_SOURCE, COPY_LOCATION_TARGETS }

data class NatsukoUiState(
    val screen: AppScreen = AppScreen.HOME,
    val feature: Feature? = null,
    val inputs: List<InputItem> = emptyList(),
    val mergeOptions: MergeOptions = MergeOptions(),
    val imagesToPdfOptions: ImagesToPdfOptions = ImagesToPdfOptions(),
    val pdfToImagesOptions: PdfToImagesOptions = PdfToImagesOptions(),
    val gifOptions: GifOptions = GifOptions(),
    val twitterLink: String = "",
    val twitterVideoSelection: TwitterVideoSelection? = null,
    val isProcessing: Boolean = false,
    val progress: ProcessingProgress = ProcessingProgress(0, 1, text(R.string.backend_preparing)),
    val outcome: ProcessingOutcome? = null,
    val errorMessage: UiText? = null,
    val successMessage: UiText? = null,
    val importFailures: List<ProcessingFailure> = emptyList(),
    val storageSettings: OutputStorageSettings = OutputStorageSettings(),
    val isSavingToUserStorage: Boolean = false,
    val storageFailures: List<ProcessingFailure> = emptyList(),
    val directoryPickerRequested: Boolean = false,
    val aboutProjectId: String? = null,
    val adjustment: ImageAdjustmentState? = null,
    val copyLocation: CopyLocationUiState? = null,
    /** Several shared images: image adjustment edits only the one the user chooses. */
    val adjustmentChoices: List<InputItem> = emptyList(),
)

class NatsukoViewModel @JvmOverloads constructor(
    application: Application,
    appUpdater: AppUpdater = GitHubAppUpdater(application),
    private val downloadTwitter: suspend (
        String,
        suspend (TwitterVideoSelection) -> TwitterDownloadScope,
        (Int, Int, UiText) -> Unit,
    ) -> ProcessingOutcome =
        DownloadTwitterVideoProcessor(application, OutputRepository(application))::process,
) : AndroidViewModel(application) {
    private val resolver = DocumentResolver(application)
    private val outputRepository = OutputRepository(application)
    private val storagePreferences = OutputStoragePreferences(application)
    private val appearancePreferences = AppearancePreferences(application)
    private val mutableThemeMode = MutableStateFlow(appearancePreferences.read())
    val themeMode: StateFlow<ThemeMode> = mutableThemeMode.asStateFlow()
    private val stopObservingAppearance = appearancePreferences.observe { mutableThemeMode.value = it }
    private val userStorageRepository = UserStorageRepository(application)
    private val mergeProcessor = ImageMergeProcessor(application, outputRepository)
    private val cropBlackBorderProcessor = CropBlackBorderProcessor(application, outputRepository)
    private val imagesToPdfProcessor = ImagesToPdfProcessor(application, outputRepository)
    private val pdfToImagesProcessor = PdfToImagesProcessor(application, outputRepository)
    private val videoToGifProcessor = VideoToGifProcessor(application, outputRepository)
    private val imageAdjustmentProcessor = ImageAdjustmentProcessor(application, outputRepository)
    private val copyLocationProcessor = CopyLocationProcessor(application, outputRepository)
    private val locationSources = mutableSetOf<File>()
    private var locationDecision: CompletableDeferred<LocationConflictDecision>? = null
    private var locationImportGeneration = 0
    private val mutableState = MutableStateFlow(freshState())
    val state: StateFlow<NatsukoUiState> = mutableState.asStateFlow()
    private val stopObservingStorage = storagePreferences.observe { settings ->
        mutableState.update { it.copy(storageSettings = settings) }
    }

    /** App updates keep their own state: file workflows replace [state] wholesale when they reset. */
    val appUpdate = AppUpdateController(
        scope = viewModelScope,
        updater = appUpdater,
        isProcessing = { mutableState.value.let { it.isProcessing || it.isSavingToUserStorage } },
        onError = { message -> mutableState.update { it.copy(errorMessage = message) } },
    )
    private var processingJob: Job? = null
    private var importJob: Job? = null
    private var storageJob: Job? = null
    private var settingsReturnScreen = AppScreen.HOME
    private var enableAfterDirectorySelection = false
    private var twitterDownloadResult: NatsukoUiState? = null
    private var twitterScopeDecision: CompletableDeferred<TwitterDownloadScope>? = null
    private var adjustmentSession: ImageAdjustmentSession? = null
    private var adjustmentLoadJob: Job? = null
    private var estimateJob: Job? = null
    private sealed interface PendingImport {
        data class Files(val feature: Feature, val uris: List<Uri>) : PendingImport
        data class TwitterLink(val text: String) : PendingImport
        data object CopyLocation : PendingImport
    }

    /** An import that arrived while unexported adjustments were open; it waits for confirmation. */
    private var pendingImport: PendingImport? = null

    private fun freshState() = NatsukoUiState(storageSettings = storagePreferences.read())

    fun setThemeMode(mode: ThemeMode) {
        mutableThemeMode.value = appearancePreferences.setThemeMode(mode)
    }

    // Installing an update closes the app, so a download or installation blocks new work like processing does.
    private fun isBusy() = mutableState.value.let { it.isProcessing || it.isSavingToUserStorage } ||
        appUpdate.state.value.blocksOtherWork

    fun acceptSelection(feature: Feature, uris: List<Uri>) {
        if (feature == Feature.COPY_LOCATION) {
            importLocationSources(uris)
            return
        }
        if (feature == Feature.DOWNLOAD_TWITTER_VIDEO) {
            mutableState.update { it.copy(errorMessage = text(R.string.backend_provide_post_link)) }
            return
        }
        if (uris.isEmpty()) return
        if (isBusy()) {
            mutableState.update { it.copy(errorMessage = text(R.string.backend_wait_before_import)) }
            return
        }
        if (deferImportForAdjustment(PendingImport.Files(feature, uris))) return
        importJob?.cancel()
        twitterDownloadResult = null
        importJob = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { resolver.resolveAll(uris) }
            if (isBusy()) return@launch
            val (valid, invalid) = items.partition { accepts(feature, it) }
            if (valid.isEmpty()) {
                mutableState.update { it.copy(errorMessage = text(R.string.backend_feature_no_valid_files, text(feature.titleRes))) }
                return@launch
            }
            releaseLocationSources()
            if (feature == Feature.IMAGE_ADJUSTMENT) {
                val skipped = invalid.takeIf { it.isNotEmpty() }?.let { text(R.string.backend_files_skipped, it.size) }
                if (valid.size == 1) {
                    startAdjustment(valid.single(), skipped)
                } else {
                    // Never silently pick the first image: one image is edited, with no batch queue.
                    discardAdjustment()
                    mutableState.value = freshState().copy(
                        screen = AppScreen.ADJUST_CHOOSE,
                        feature = feature,
                        adjustmentChoices = valid,
                        errorMessage = skipped,
                    )
                }
                return@launch
            }
            discardAdjustment()
            val automatic = feature == Feature.CROP_BLACK_BORDER
            mutableState.value = NatsukoUiState(
                screen = if (automatic) AppScreen.HOME else AppScreen.CONFIGURE,
                feature = feature,
                inputs = if (feature == Feature.VIDEO_TO_GIF) valid.take(1) else valid,
                storageSettings = storagePreferences.read(),
                errorMessage = invalid.takeIf { it.isNotEmpty() && !automatic }?.let { text(R.string.backend_files_skipped, it.size) },
                importFailures = if (automatic) invalid.map { ProcessingFailure(it.displayName, text(R.string.backend_image_format_unsupported)) } else emptyList(),
            )
            if (automatic) {
                // This import is complete; process() must not cancel its own caller.
                importJob = null
                process()
            }
        }
    }

    fun openCopyLocation() {
        if (isBusy()) return
        if (deferImportForAdjustment(PendingImport.CopyLocation)) return
        importJob?.cancel()
        releaseLocationSources()
        twitterDownloadResult = null
        discardAdjustment()
        mutableState.value = freshState().copy(
            screen = AppScreen.COPY_LOCATION_SOURCE,
            feature = Feature.COPY_LOCATION,
            copyLocation = CopyLocationUiState(sourcePickerRequested = true),
        )
    }

    fun importLocationSources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (isBusy()) {
            mutableState.update { it.copy(errorMessage = text(R.string.backend_wait_before_import)) }
            return
        }
        if (deferImportForAdjustment(PendingImport.Files(Feature.COPY_LOCATION, uris))) return
        openCopyLocation()
        val generation = locationImportGeneration
        mutableState.update { it.copy(copyLocation = CopyLocationUiState(isLoading = true)) }
        importJob = viewModelScope.launch {
            val staged = mutableListOf<LocationSource>()
            var published = false
            try {
                val candidates = withContext(Dispatchers.IO) {
                    resolver.resolveAll(uris).map { input ->
                        try {
                            val source = copyLocationProcessor.importSource(input)
                            staged += source // Own it before the cancellable dispatch back to Main.
                            LocationCandidate(input, source)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            LocationCandidate(input, error = error.userMessage(R.string.location_read_failed))
                        }
                    }
                }
                if (generation != locationImportGeneration) return@launch
                locationSources += staged.map { it.file }
                mutableState.update { it.copy(copyLocation = CopyLocationUiState(candidates = candidates)) }
                published = true
                if (candidates.size == 1) candidates.single().source?.let(::chooseLocationSource)
            } finally {
                if (!published) staged.forEach { it.file.delete() }
                if (generation == locationImportGeneration) {
                    mutableState.update { it.copy(copyLocation = it.copyLocation?.copy(isLoading = false)) }
                }
            }
        }
    }

    fun chooseLocationSource(source: LocationSource) {
        val snapshot = mutableState.value
        val location = snapshot.copyLocation ?: return
        if (isBusy() || snapshot.screen != AppScreen.COPY_LOCATION_SOURCE || location.isLoading ||
            !source.location.hasCoordinates || location.candidates.none { it.source == source }) return
        locationSources.filter { it != source.file }.forEach { it.delete() }
        locationSources.retainAll(setOf(source.file))
        mutableState.update {
            it.copy(screen = AppScreen.COPY_LOCATION_TARGETS,
                copyLocation = CopyLocationUiState(source = source, targetsPickerRequested = true))
        }
    }

    fun onLocationSourcePickerLaunched() = mutableState.update {
        it.copy(copyLocation = it.copyLocation?.copy(sourcePickerRequested = false))
    }

    fun onLocationTargetsPickerLaunched() = mutableState.update {
        it.copy(copyLocation = it.copyLocation?.copy(targetsPickerRequested = false))
    }

    fun onLocationPickerFailure() = mutableState.update {
        it.copy(errorMessage = text(R.string.location_picker_failed),
            copyLocation = it.copyLocation?.copy(targetsPickerRequested = false, sourcePickerRequested = false))
    }

    fun acceptLocationTargets(uris: List<Uri>) {
        val snapshot = mutableState.value
        val source = snapshot.copyLocation?.source ?: return
        if (uris.isEmpty() || isBusy() || snapshot.screen != AppScreen.COPY_LOCATION_TARGETS) return
        // Mark busy before launching: duplicate picker results cannot start overlapping batches.
        mutableState.update { it.copy(isProcessing = true, errorMessage = null,
            progress = ProcessingProgress(0, 0, text(R.string.location_loading))) }
        processingJob = viewModelScope.launch {
            var unpublished: ProcessingOutcome? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val targets = resolver.resolveAll(uris)
                    copyLocationProcessor.process(source, targets,
                        onConflict = { conflict ->
                            withContext(Dispatchers.Main.immediate) {
                                val decision = CompletableDeferred<LocationConflictDecision>()
                                locationDecision = decision
                                mutableState.update { it.copy(copyLocation = it.copyLocation?.copy(conflict = conflict)) }
                                try { decision.await() } finally {
                                    locationDecision = null
                                    mutableState.update { it.copy(copyLocation = it.copyLocation?.copy(conflict = null)) }
                                }
                            }
                        },
                        onProgress = { completed, total, message ->
                            mutableState.update { it.copy(progress = ProcessingProgress(completed, total, message)) }
                        },
                    ).also { unpublished = it }
                }
                val success = result.failures.isEmpty() && result.skipped.isEmpty() && result.outputs.isNotEmpty()
                mutableState.update { it.copy(
                    screen = if (success) AppScreen.HOME else AppScreen.RESULT,
                    isProcessing = false, outcome = result, copyLocation = null,
                    successMessage = if (success) text(R.string.location_success, result.outputs.size) else null,
                ) }
                unpublished = null
                releaseLocationSources()
                if (mutableState.value.storageSettings.enabled) saveCurrentOutputs()
            } catch (_: CancellationException) {
                mutableState.update { it.copy(isProcessing = false, copyLocation = it.copyLocation?.copy(conflict = null)) }
            } catch (error: Exception) {
                mutableState.update { it.copy(isProcessing = false, errorMessage = error.userMessage(R.string.location_copy_failed)) }
            } finally {
                unpublished?.outputs?.forEach { it.file.delete() }
                unpublished?.outputs?.mapNotNull { it.file.parentFile }?.distinct()?.forEach { it.delete() }
            }
        }
    }

    fun chooseLocationConflict(decision: LocationConflictDecision) {
        locationDecision?.complete(decision)
    }

    private fun releaseLocationSources() {
        locationImportGeneration++
        locationSources.forEach { it.delete() }
        locationSources.clear()
    }

    fun handleShareIntent(intent: Intent) {
        val feature = ShareImportParser.feature(intent)
        if (feature == Feature.DOWNLOAD_TWITTER_VIDEO) {
            if (isBusy()) {
                mutableState.update { it.copy(errorMessage = text(R.string.backend_wait_before_link_import)) }
                return
            }
            val text = ShareImportParser.twitterText(intent)
            if (text == null) {
                mutableState.update { it.copy(errorMessage = text(R.string.backend_share_no_post_link)) }
                return
            }
            try {
                TwitterStatusLink.parse(text)
                openTwitterDownload(text)
            } catch (error: IllegalArgumentException) {
                mutableState.update { it.copy(errorMessage = error.userMessage(R.string.backend_share_valid_post_link)) }
            }
            return
        }
        val uris = ShareImportParser.uris(intent)
        when {
            feature == null -> mutableState.update { it.copy(errorMessage = text(R.string.backend_share_feature_unknown)) }
            uris.isEmpty() -> mutableState.update { it.copy(errorMessage = text(R.string.backend_share_no_readable_files)) }
            else -> acceptSelection(feature, uris)
        }
    }

    fun openTwitterDownload() = openTwitterDownload(mutableState.value.twitterLink)

    private fun openTwitterDownload(link: String) {
        if (isBusy()) return
        if (deferImportForAdjustment(PendingImport.TwitterLink(link))) return
        releaseLocationSources()
        importJob?.cancel()
        twitterDownloadResult = null
        discardAdjustment()
        mutableState.value = freshState().copy(
            screen = AppScreen.TWITTER_DOWNLOAD,
            feature = Feature.DOWNLOAD_TWITTER_VIDEO,
            twitterLink = link,
        )
    }

    fun updateTwitterLink(value: String) {
        if (isBusy()) return
        mutableState.update { it.copy(twitterLink = value.take(8192)) }
    }

    fun downloadTwitterVideo() {
        if (mutableState.value.feature != Feature.DOWNLOAD_TWITTER_VIDEO) return
        process()
    }

    fun chooseTwitterDownloadScope(scope: TwitterDownloadScope) {
        twitterScopeDecision?.complete(scope)
    }

    private suspend fun requestTwitterDownloadScope(selection: TwitterVideoSelection): TwitterDownloadScope =
        withContext(Dispatchers.Main.immediate) {
            val decision = CompletableDeferred<TwitterDownloadScope>()
            twitterScopeDecision = decision
            mutableState.update { it.copy(twitterVideoSelection = selection) }
            try {
                decision.await()
            } finally {
                if (twitterScopeDecision === decision) {
                    twitterScopeDecision = null
                    mutableState.update { it.copy(twitterVideoSelection = null) }
                }
            }
        }

    fun convertDownloadedVideoToGif(output: OutputArtifact) {
        val snapshot = mutableState.value
        if (isBusy() || snapshot.feature != Feature.DOWNLOAD_TWITTER_VIDEO ||
            output !in snapshot.outcome?.outputs.orEmpty() || !output.mimeType.startsWith("video/")) return
        try {
            checkText(output.file.isFile) { text(R.string.backend_downloaded_video_missing) }
            // Chain from the retained private copy even if the public directory grant later expires.
            val input = InputItem(outputRepository.shareUri(output.copy(userStorage = null)), output.file.name, output.mimeType)
            importJob?.cancel()
            twitterDownloadResult = snapshot
            mutableState.value = freshState().copy(
                screen = AppScreen.CONFIGURE,
                feature = Feature.VIDEO_TO_GIF,
                inputs = listOf(input),
                twitterLink = snapshot.twitterLink,
            )
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.userMessage(R.string.backend_downloaded_video_unreadable)) }
        }
    }

    fun reorderInput(from: Int, to: Int) {
        mutableState.update { state ->
            if (from !in state.inputs.indices || to !in state.inputs.indices) return@update state
            val changed = state.inputs.toMutableList()
            val item = changed.removeAt(from)
            changed.add(to, item)
            state.copy(inputs = changed)
        }
    }

    fun removeInput(index: Int) {
        mutableState.update { state -> state.copy(inputs = state.inputs.filterIndexed { itemIndex, _ -> itemIndex != index }) }
    }

    fun updateMergeDirection(value: MergeDirection) = mutableState.update {
        it.copy(mergeOptions = it.mergeOptions.copy(direction = value))
    }

    fun updateMergeColor(value: Int) = mutableState.update {
        it.copy(mergeOptions = it.mergeOptions.copy(fillColor = value))
    }

    fun updatePdfOutputMode(value: PdfOutputMode) = mutableState.update {
        it.copy(imagesToPdfOptions = it.imagesToPdfOptions.copy(outputMode = value))
    }

    fun updatePdfPagePreset(value: PdfPagePreset) = mutableState.update {
        it.copy(imagesToPdfOptions = it.imagesToPdfOptions.copy(pagePreset = value))
    }

    fun updatePdfOrientation(value: PdfOrientation) = mutableState.update {
        it.copy(imagesToPdfOptions = it.imagesToPdfOptions.copy(orientation = value))
    }

    fun updatePdfMargin(value: Int) = mutableState.update {
        it.copy(imagesToPdfOptions = it.imagesToPdfOptions.copy(marginPoints = value.coerceIn(0, 72)))
    }

    fun updateImageFormat(value: ImageOutputFormat) = mutableState.update {
        it.copy(pdfToImagesOptions = it.pdfToImagesOptions.copy(format = value))
    }

    fun updateImageScale(value: Float) = mutableState.update {
        it.copy(pdfToImagesOptions = it.pdfToImagesOptions.copy(scale = value.coerceIn(1f, 3f)))
    }

    fun updateJpegQuality(value: Int) = mutableState.update {
        it.copy(pdfToImagesOptions = it.pdfToImagesOptions.copy(jpegQuality = value.coerceIn(60, 100)))
    }

    fun updateGifFps(value: Int) = mutableState.update {
        it.copy(gifOptions = it.gifOptions.copy(framesPerSecond = value.coerceIn(1, 30)))
    }

    fun updateGifInterval(value: Long) = mutableState.update {
        it.copy(gifOptions = it.gifOptions.copy(sampleIntervalMs = value.coerceIn(50, 2000)))
    }

    fun updateGifWidth(value: Int) = mutableState.update {
        it.copy(gifOptions = it.gifOptions.copy(maxWidth = value.coerceIn(240, 1080)))
    }

    fun updateGifLoop(value: Int) = mutableState.update {
        it.copy(gifOptions = it.gifOptions.copy(loopCount = value.coerceIn(0, 100)))
    }

    fun process() {
        if (isBusy()) return
        importJob?.cancel()
        val snapshot = mutableState.value
        val feature = snapshot.feature ?: return
        if (feature == Feature.COPY_LOCATION) return // Targets start their own automatic batch.
        if (feature == Feature.IMAGE_ADJUSTMENT) {
            saveAdjustment()
            return
        }
        if (feature == Feature.DOWNLOAD_TWITTER_VIDEO) {
            try {
                TwitterStatusLink.parse(snapshot.twitterLink)
            } catch (error: IllegalArgumentException) {
                mutableState.update { it.copy(errorMessage = error.userMessage(R.string.backend_enter_valid_post_link)) }
                return
            }
        } else if (snapshot.inputs.isEmpty()) {
            mutableState.update { it.copy(errorMessage = text(R.string.backend_select_file)) }
            return
        }
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            var unpublishedDownload: ProcessingOutcome? = null
            suspend fun discardUnpublishedDownload() {
                val outputs = unpublishedDownload?.outputs ?: return
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        outputs.forEach { it.file.delete() }
                        outputs.mapNotNull { it.file.parentFile }.distinct().forEach { it.delete() }
                    }
                }
                unpublishedDownload = null
            }
            mutableState.update {
                    it.copy(isProcessing = true, progress = ProcessingProgress(0, 1, text(R.string.backend_preparing_processing)), errorMessage = null, storageFailures = emptyList())
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    val workContext = currentCoroutineContext()
                    val progress: (Int, Int, UiText) -> Unit = { completed, total, message ->
                        workContext.ensureActive()
                        mutableState.update {
                            it.copy(progress = ProcessingProgress(completed, total.coerceAtLeast(1), message))
                        }
                    }
                    when (feature) {
                        Feature.CROP_BLACK_BORDER -> cropBlackBorderProcessor.process(snapshot.inputs, progress)
                        Feature.IMAGE_MERGE -> mergeProcessor.process(snapshot.inputs, snapshot.mergeOptions, progress)
                        Feature.IMAGES_TO_PDF -> imagesToPdfProcessor.process(snapshot.inputs, snapshot.imagesToPdfOptions, progress)
                        Feature.PDF_TO_IMAGES -> pdfToImagesProcessor.process(snapshot.inputs, snapshot.pdfToImagesOptions, progress)
                        Feature.VIDEO_TO_GIF -> videoToGifProcessor.process(snapshot.inputs.first(), snapshot.gifOptions, progress)
                        Feature.DOWNLOAD_TWITTER_VIDEO -> downloadTwitter(snapshot.twitterLink, ::requestTwitterDownloadScope, progress).also {
                            // Retain ownership across withContext's cancellable dispatch back to Main.
                            unpublishedDownload = it
                        }
                        Feature.IMAGE_ADJUSTMENT -> error("Image adjustment exports through saveAdjustment()")
                        Feature.COPY_LOCATION -> error("Location copying starts after target selection")
                    }
                }
                val outcome = result.copy(failures = snapshot.importFailures + result.failures)
                val automaticSuccess = feature == Feature.CROP_BLACK_BORDER && outcome.failures.isEmpty() && outcome.outputs.isNotEmpty()
                mutableState.update {
                    it.copy(
                        screen = if (automaticSuccess) AppScreen.HOME else AppScreen.RESULT,
                        isProcessing = false,
                        outcome = outcome,
                        successMessage = if (automaticSuccess) text(R.string.backend_crop_success, outcome.outputs.size) else null,
                    )
                }
                unpublishedDownload = null
                if (mutableState.value.storageSettings.enabled) saveCurrentOutputs()
            } catch (_: CancellationException) {
                discardUnpublishedDownload()
                mutableState.update { it.copy(isProcessing = false) }
            } catch (error: Throwable) {
                discardUnpublishedDownload()
                mutableState.update {
                    it.copy(isProcessing = false, errorMessage = error.userMessage(R.string.backend_processing_failed))
                }
            }
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
    }

    fun dismissError() = mutableState.update { it.copy(errorMessage = null) }

    fun openResults() {
        if (mutableState.value.outcome == null || mutableState.value.isProcessing) return
        mutableState.update { it.copy(screen = AppScreen.RESULT) }
    }

    fun openSettings() {
        if (isBusy() || mutableState.value.screen == AppScreen.SETTINGS) return
        settingsReturnScreen = mutableState.value.screen
        appUpdate.clearResult()
        mutableState.update { it.copy(screen = AppScreen.SETTINGS) }
    }

    fun openAbout() {
        if (isBusy() || mutableState.value.screen != AppScreen.HOME) return
        importJob?.cancel()
        mutableState.update { it.copy(screen = AppScreen.ABOUT, aboutProjectId = null) }
    }

    fun openOpenSourceProject(id: String) {
        if (mutableState.value.screen != AppScreen.ABOUT) return
        mutableState.update { it.copy(aboutProjectId = id) }
    }

    fun setSaveToUserStorage(enabled: Boolean) {
        if (isBusy()) return
        if (enabled && !userStorageRepository.hasDirectoryPermission(mutableState.value.storageSettings)) {
            enableAfterDirectorySelection = true
            mutableState.update { it.copy(directoryPickerRequested = true) }
            return
        }
        val settings = storagePreferences.setEnabled(enabled)
        mutableState.update { it.copy(storageSettings = settings) }
        if (enabled) saveCurrentOutputs()
    }

    fun requestUserStorageDirectory() {
        if (isBusy()) return
        enableAfterDirectorySelection = false
        mutableState.update { it.copy(directoryPickerRequested = true) }
    }

    fun onDirectoryPickerLaunched() = mutableState.update { it.copy(directoryPickerRequested = false) }

    fun onDirectoryPickerFailure() {
        enableAfterDirectorySelection = false
        mutableState.update { it.copy(directoryPickerRequested = false, errorMessage = text(R.string.backend_directory_picker_failed)) }
    }

    fun onUserStorageDirectorySelected(uri: Uri?) {
        val enable = enableAfterDirectorySelection
        enableAfterDirectorySelection = false
        mutableState.update { it.copy(directoryPickerRequested = false) }
        if (uri == null || isBusy()) return
        mutableState.update { it.copy(isSavingToUserStorage = true, errorMessage = null, progress = ProcessingProgress(0, 1, text(R.string.backend_checking_directory_access))) }
        storageJob = viewModelScope.launch {
            var directoryAccepted = false
            try {
                val label = withContext(Dispatchers.IO) { userStorageRepository.retainDirectoryPermission(uri) }
                var settings = storagePreferences.setDirectory(uri, label)
                if (enable) settings = storagePreferences.setEnabled(true)
                mutableState.update { it.copy(storageSettings = settings, storageFailures = emptyList()) }
                directoryAccepted = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(errorMessage = error.userMessage(R.string.backend_directory_write_permission_failed)) }
            } finally {
                mutableState.update { it.copy(isSavingToUserStorage = false) }
            }
            if (directoryAccepted && mutableState.value.storageSettings.enabled) saveCurrentOutputs()
        }
    }

    fun restoreDefaultUserStorageDirectory() {
        if (isBusy()) return
        val settings = storagePreferences.setDirectory(null)
        mutableState.update { it.copy(storageSettings = settings, storageFailures = emptyList()) }
        if (settings.enabled) saveCurrentOutputs()
    }

    fun retryUserStorageSave() {
        if (isBusy()) return
        if (!userStorageRepository.hasDirectoryPermission(mutableState.value.storageSettings)) {
            enableAfterDirectorySelection = true
            mutableState.update { it.copy(directoryPickerRequested = true) }
        } else {
            saveCurrentOutputs()
        }
    }

    private fun saveCurrentOutputs() {
        val snapshot = mutableState.value
        val outcome = snapshot.outcome ?: return
        if (snapshot.isSavingToUserStorage || !snapshot.storageSettings.enabled || outcome.outputs.isEmpty()) return
        mutableState.update {
            it.copy(isSavingToUserStorage = true, storageFailures = emptyList(), progress = ProcessingProgress(0, outcome.outputs.size, text(R.string.backend_saving_user_storage)))
        }
        storageJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    userStorageRepository.export(outcome.outputs, snapshot.storageSettings) { completed, total, message ->
                        mutableState.update { it.copy(progress = ProcessingProgress(completed, total.coerceAtLeast(1), message)) }
                    }
                }
                mutableState.update { it.copy(outcome = outcome.copy(outputs = result.outputs), storageFailures = result.failures) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(storageFailures = listOf(ProcessingFailure(text(R.string.backend_user_storage_directory), error.userMessage(R.string.backend_save_retry)))) }
            } finally {
                mutableState.update {
                    val showFailures = it.feature in setOf(Feature.CROP_BLACK_BORDER, Feature.COPY_LOCATION) && it.storageFailures.isNotEmpty()
                    it.copy(
                        isSavingToUserStorage = false,
                        screen = if (showFailures) AppScreen.RESULT else it.screen,
                        successMessage = if (showFailures) null else it.successMessage,
                    )
                }
            }
        }
    }

    fun back() {
        if (isBusy()) return
        importJob?.cancel()
        val screen = mutableState.value.screen
        if (screen == AppScreen.ABOUT) {
            mutableState.update {
                if (it.aboutProjectId != null) it.copy(aboutProjectId = null)
                else it.copy(screen = AppScreen.HOME)
            }
        } else if (screen == AppScreen.SETTINGS) {
            mutableState.update { it.copy(screen = settingsReturnScreen) }
        } else if (screen == AppScreen.ADJUST_EXPORT) {
            // Returning to the editor keeps every edit and export choice without a discard prompt.
            leaveAdjustmentExport()
        } else if (screen == AppScreen.ADJUST_EDIT && hasUnexportedAdjustment()) {
            pendingImport = null
            mutableState.update { it.copy(adjustment = it.adjustment?.copy(confirmDiscard = true, gesture = null)) }
        } else if (twitterDownloadResult != null && mutableState.value.feature == Feature.VIDEO_TO_GIF) {
            mutableState.value = checkNotNull(twitterDownloadResult).copy(storageSettings = storagePreferences.read())
            twitterDownloadResult = null
        } else {
            twitterDownloadResult = null
            discardAdjustment()
            releaseLocationSources()
            mutableState.value = freshState()
        }
    }

    fun reset() {
        val snapshot = mutableState.value
        if (snapshot.isSavingToUserStorage || (snapshot.feature == Feature.COPY_LOCATION && snapshot.isProcessing)) return
        releaseLocationSources()
        importJob?.cancel()
        processingJob?.cancel()
        twitterDownloadResult = null
        discardAdjustment()
        mutableState.value = freshState()
    }

    fun chooseAdjustmentImage(item: InputItem) {
        val state = mutableState.value
        if (isBusy() || state.screen != AppScreen.ADJUST_CHOOSE || item !in state.adjustmentChoices) return
        startAdjustment(item)
    }

    fun selectAdjustmentTool(tool: AdjustmentTool) = mutableState.update { state ->
        val adjustment = state.adjustment
        if (adjustment == null || state.screen != AppScreen.ADJUST_EDIT) state
        else state.copy(adjustment = adjustment.copy(tool = tool, gesture = null))
    }

    fun rotateAdjustment(clockwise: Boolean) = editAdjustment { edits, source ->
        AdjustmentEditing.rotate(edits, source.width, source.height, clockwise)
    }

    fun flipAdjustment(horizontal: Boolean) = editAdjustment { edits, source ->
        AdjustmentEditing.flip(edits, source.width, source.height, horizontal)
    }

    fun selectAdjustmentAspect(aspect: CropAspect) = editAdjustment { edits, source ->
        AdjustmentEditing.selectAspect(edits, source.width, source.height, aspect)
    }

    fun swapAdjustmentAspect() = editAdjustment { edits, source ->
        AdjustmentEditing.swapAspect(edits, source.width, source.height)
    }

    fun nudgeAdjustmentStraighten(tenths: Int) = editAdjustment { edits, _ ->
        AdjustmentEditing.setStraighten(edits, edits.straightenTenths + tenths)
    }

    fun setAdjustmentScale(scale: Double) = editAdjustment { edits, _ -> AdjustmentEditing.setScale(edits, scale) }

    /** A typed width or height becomes one proportional reduction of the current edited size. */
    fun setAdjustmentTargetSize(width: Int? = null, height: Int? = null) = editAdjustment { edits, source ->
        val resolved = AdjustmentGeometry.resolve(source.width, source.height, edits)
        when {
            width != null -> AdjustmentEditing.setScale(edits, AdjustmentEditing.scaleForWidth(resolved, width))
            height != null -> AdjustmentEditing.setScale(edits, AdjustmentEditing.scaleForHeight(resolved, height))
            else -> edits
        }
    }

    fun resetAdjustmentTool(tool: AdjustmentTool) = editAdjustment { edits, source ->
        when (tool) {
            AdjustmentTool.CROP -> AdjustmentEditing.resetCrop(edits)
            AdjustmentTool.STRAIGHTEN -> AdjustmentEditing.resetStraighten(edits)
            AdjustmentTool.ROTATE -> AdjustmentEditing.resetRotation(edits, source.width, source.height)
            AdjustmentTool.MIRROR -> AdjustmentEditing.resetMirror(edits, source.width, source.height)
            AdjustmentTool.RESIZE -> AdjustmentEditing.resetScale(edits)
        }
    }

    /** Clears every edit, including the size reduction, as one undoable step. */
    fun restoreAdjustmentOriginal() = editAdjustment { _, _ -> AdjustmentEdits() }

    /** Continuous slider value; [commit] records the finished gesture as one undo step. */
    fun updateAdjustmentStraighten(tenths: Int, commit: Boolean) = updateAdjustmentGesture(commit) { edits, _ ->
        AdjustmentEditing.setStraighten(edits, tenths)
    }

    /** Continuous crop drag; [commit] records the finished gesture as one undo step. */
    fun updateAdjustmentCrop(crop: PixelRect, commit: Boolean) = updateAdjustmentGesture(commit) { edits, source ->
        AdjustmentEditing.setCrop(edits, source.width, source.height, crop)
    }

    fun cancelAdjustmentGesture() = mutableState.update { state ->
        state.copy(adjustment = state.adjustment?.copy(gesture = null))
    }

    /** Records the unfinished gesture, for example when a slider is released. */
    fun commitAdjustmentGesture() = mutableState.update { state ->
        val adjustment = state.adjustment
        val gesture = adjustment?.gesture
        if (gesture == null) state else state.copy(adjustment = adjustment.copy(history = adjustment.history.record(gesture), gesture = null))
    }

    fun undoAdjustment() = updateAdjustmentHistory { it.undo() }

    fun redoAdjustment() = updateAdjustmentHistory { it.redo() }

    fun openAdjustmentExport() {
        val state = mutableState.value
        val adjustment = state.adjustment ?: return
        if (isBusy() || state.screen != AppScreen.ADJUST_EDIT || adjustment.source == null || adjustment.exportSettings == null) return
        mutableState.update {
            it.copy(
                screen = AppScreen.ADJUST_EXPORT,
                adjustment = adjustment.copy(history = adjustment.history.record(adjustment.edits), gesture = null),
            )
        }
        requestAdjustmentEstimate(debounce = false)
    }

    fun updateAdjustmentExportSettings(settings: AdjustmentExportSettings) {
        if (isBusy() || mutableState.value.screen != AppScreen.ADJUST_EXPORT) return
        val checked = settings.copy(
            quality = settings.quality.takeIf { it in AdjustmentExportSettings.QUALITIES } ?: AdjustmentExportSettings.DEFAULT_QUALITY,
            background = settings.background.takeIf {
                it == AdjustmentExportSettings.BACKGROUND_WHITE || it == AdjustmentExportSettings.BACKGROUND_BLACK
            } ?: AdjustmentExportSettings.BACKGROUND_WHITE,
        )
        mutableState.update { it.copy(adjustment = it.adjustment?.copy(exportSettings = checked)) }
        requestAdjustmentEstimate(debounce = true)
    }

    fun retryAdjustmentEstimate() = requestAdjustmentEstimate(debounce = false, force = true)

    /** Leaves a size limit failure for the editor's size reduction tool; nothing is reduced automatically. */
    fun reduceAdjustmentSize() {
        if (isBusy() || mutableState.value.screen != AppScreen.ADJUST_EXPORT) return
        leaveAdjustmentExport(AdjustmentTool.RESIZE)
    }

    /** Commits the trial file that matches the current parameters, waiting for it if necessary. */
    fun saveAdjustment() {
        val state = mutableState.value
        val adjustment = state.adjustment ?: return
        val source = adjustment.source ?: return
        val request = adjustment.exportRequest ?: return
        val session = adjustmentSession ?: return
        if (isBusy() || state.screen != AppScreen.ADJUST_EXPORT) return
        val saved = adjustment.history.present
        // An encode already running for these parameters is reused through the session cache.
        val estimate = adjustment.estimate
        if (!(estimate is AdjustmentEstimate.Calculating && estimate.request == request && estimateJob?.isActive == true)) {
            estimateJob?.cancel()
        }
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            val stage: (UiText) -> Unit = { message -> mutableState.update { it.copy(progress = ProcessingProgress(0, 0, message)) } }
            mutableState.update {
                it.copy(
                    isProcessing = true,
                    progress = ProcessingProgress(0, 0, text(R.string.backend_adjust_waiting)),
                    errorMessage = null,
                    storageFailures = emptyList(),
                )
            }
            try {
                val output = withContext(Dispatchers.IO) {
                    val bytes = session.encode(adjustment.input, source, request, stage)
                    setAdjustmentEstimate(request, AdjustmentEstimate.Ready(request, bytes))
                    session.commit(adjustment.input, request, stage)
                }
                estimateJob?.cancel()
                mutableState.update {
                    it.copy(
                        screen = AppScreen.RESULT,
                        isProcessing = false,
                        outcome = ProcessingOutcome(listOf(output)),
                        successMessage = null,
                        adjustment = it.adjustment?.copy(exportedEdits = saved, estimate = AdjustmentEstimate.Idle),
                    )
                }
                if (mutableState.value.storageSettings.enabled) saveCurrentOutputs()
            } catch (_: CancellationException) {
                mutableState.update { it.copy(isProcessing = false) }
                requestAdjustmentEstimate(debounce = false)
            } catch (error: Throwable) {
                mutableState.update { it.copy(isProcessing = false, errorMessage = error.userMessage(R.string.backend_adjust_export_failed)) }
                requestAdjustmentEstimate(debounce = false)
            }
        }
    }

    /** From the result screen: continue from the saved edits and save another version. */
    fun continueAdjustmentEditing() {
        val state = mutableState.value
        if (isBusy() || state.screen != AppScreen.RESULT || state.adjustment?.source == null) return
        mutableState.update { it.copy(screen = AppScreen.ADJUST_EDIT) }
    }

    fun confirmDiscardAdjustment() {
        val pending = pendingImport
        pendingImport = null
        twitterDownloadResult = null
        discardAdjustment()
        mutableState.value = freshState()
        when (pending) {
            is PendingImport.Files -> acceptSelection(pending.feature, pending.uris)
            is PendingImport.TwitterLink -> openTwitterDownload(pending.text)
            PendingImport.CopyLocation -> openCopyLocation()
            null -> Unit
        }
    }

    fun dismissDiscardAdjustment() {
        pendingImport = null
        mutableState.update { it.copy(adjustment = it.adjustment?.copy(confirmDiscard = false)) }
    }

    private fun deferImportForAdjustment(request: PendingImport): Boolean {
        if (!hasUnexportedAdjustment()) return false
        pendingImport = request
        mutableState.update { it.copy(adjustment = it.adjustment?.copy(confirmDiscard = true)) }
        return true
    }

    private fun hasUnexportedAdjustment(): Boolean = mutableState.value.let {
        (it.screen == AppScreen.ADJUST_EDIT || it.screen == AppScreen.ADJUST_EXPORT) && it.adjustment?.hasUnexportedEdits == true
    }

    private fun startAdjustment(input: InputItem, message: UiText? = null) {
        discardAdjustment()
        val session = imageAdjustmentProcessor.openSession()
        adjustmentSession = session
        mutableState.value = freshState().copy(
            screen = AppScreen.ADJUST_EDIT,
            feature = Feature.IMAGE_ADJUSTMENT,
            adjustment = ImageAdjustmentState(input),
            errorMessage = message,
        )
        adjustmentLoadJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val work = currentCoroutineContext()
                    imageAdjustmentProcessor.load(input) { work.ensureActive() }
                }
                if (adjustmentSession !== session) return@launch
                mutableState.update { state ->
                    state.copy(
                        adjustment = state.adjustment?.copy(
                            source = loaded.source,
                            preview = loaded.preview,
                            exportSettings = loaded.source.defaultExportSettings(),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (adjustmentSession !== session) return@launch
                mutableState.update {
                    it.copy(adjustment = it.adjustment?.copy(loadError = error.userMessage(R.string.backend_image_invalid)))
                }
            }
        }
    }

    /** Ends the image adjustment workflow and deletes its temporary files. */
    private fun discardAdjustment() {
        adjustmentLoadJob?.cancel()
        adjustmentLoadJob = null
        estimateJob?.cancel()
        estimateJob = null
        adjustmentSession?.close()
        adjustmentSession = null
    }

    private fun leaveAdjustmentExport(tool: AdjustmentTool? = null) {
        estimateJob?.cancel()
        adjustmentSession?.releaseRender()
        mutableState.update { state ->
            val adjustment = state.adjustment ?: return@update state
            state.copy(
                screen = AppScreen.ADJUST_EDIT,
                adjustment = adjustment.copy(
                    tool = tool ?: adjustment.tool,
                    // A finished trial stays valid while the edits remain unchanged.
                    estimate = adjustment.estimate.takeIf { it is AdjustmentEstimate.Ready } ?: AdjustmentEstimate.Idle,
                ),
            )
        }
    }

    private fun editAdjustment(transform: (AdjustmentEdits, AdjustmentSource) -> AdjustmentEdits) = mutableState.update { state ->
        val adjustment = state.adjustment
        val source = adjustment?.source
        if (adjustment == null || source == null || state.screen != AppScreen.ADJUST_EDIT) return@update state
        val next = transform(adjustment.history.present, source)
        state.copy(adjustment = adjustment.copy(history = adjustment.history.record(next), gesture = null))
    }

    /** Gestures start from the last recorded edits, so each value replaces the previous preview. */
    private fun updateAdjustmentGesture(commit: Boolean, transform: (AdjustmentEdits, AdjustmentSource) -> AdjustmentEdits) =
        mutableState.update { state ->
            val adjustment = state.adjustment
            val source = adjustment?.source
            if (adjustment == null || source == null || state.screen != AppScreen.ADJUST_EDIT) return@update state
            val next = transform(adjustment.history.present, source)
            state.copy(
                adjustment = if (commit) {
                    adjustment.copy(history = adjustment.history.record(next), gesture = null)
                } else {
                    adjustment.copy(gesture = next)
                },
            )
        }

    private fun updateAdjustmentHistory(transform: (AdjustmentHistory) -> AdjustmentHistory) =
        mutableState.update { state ->
            val adjustment = state.adjustment
            if (adjustment?.source == null || state.screen != AppScreen.ADJUST_EDIT) return@update state
            state.copy(adjustment = adjustment.copy(history = transform(adjustment.history), gesture = null))
        }

    /**
     * Encodes the current parameters in the background. A change marks the previous size stale at
     * once; rapid changes are merged, and late results from superseded requests are ignored.
     */
    private fun requestAdjustmentEstimate(debounce: Boolean, force: Boolean = false) {
        val state = mutableState.value
        val adjustment = state.adjustment ?: return
        val source = adjustment.source ?: return
        val request = adjustment.exportRequest ?: return
        val session = adjustmentSession ?: return
        if (state.screen != AppScreen.ADJUST_EXPORT) return
        val current = adjustment.estimate
        if (!force && current.request == request &&
            (current is AdjustmentEstimate.Ready || current is AdjustmentEstimate.Calculating && estimateJob?.isActive == true)
        ) return
        estimateJob?.cancel()
        setAdjustmentEstimate(request, AdjustmentEstimate.Calculating(request, text(R.string.adjust_calculating)))
        estimateJob = viewModelScope.launch {
            if (debounce) delay(ESTIMATE_DELAY_MS)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    session.encode(adjustment.input, source, request) { stage ->
                        setAdjustmentEstimate(request, AdjustmentEstimate.Calculating(request, stage))
                    }
                }
                setAdjustmentEstimate(request, AdjustmentEstimate.Ready(request, bytes))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                setAdjustmentEstimate(
                    request,
                    AdjustmentEstimate.Failed(
                        request,
                        error.userMessage(R.string.backend_adjust_export_failed),
                        error is AdjustmentTooLargeException,
                    ),
                )
            }
        }
    }

    /** Applies an estimate only while it still describes the current edits and export settings. */
    private fun setAdjustmentEstimate(request: AdjustmentExportRequest, estimate: AdjustmentEstimate) = mutableState.update { state ->
        val adjustment = state.adjustment
        if (adjustment?.exportRequest != request) state else state.copy(adjustment = adjustment.copy(estimate = estimate))
    }

    override fun onCleared() {
        stopObservingAppearance()
        stopObservingStorage()
        releaseLocationSources()
        discardAdjustment()
        super.onCleared()
    }

    private fun accepts(feature: Feature, item: InputItem): Boolean {
        val mime = item.mimeType.orEmpty().lowercase()
        val extension = item.displayName.substringAfterLast('.', "").lowercase()
        return when (feature) {
            Feature.CROP_BLACK_BORDER, Feature.IMAGE_MERGE, Feature.IMAGES_TO_PDF, Feature.IMAGE_ADJUSTMENT ->
                mime.startsWith("image/") || extension in IMAGE_EXTENSIONS
            Feature.PDF_TO_IMAGES -> mime == "application/pdf" || extension == "pdf"
            Feature.VIDEO_TO_GIF -> mime.startsWith("video/") || extension in VIDEO_EXTENSIONS
            Feature.DOWNLOAD_TWITTER_VIDEO -> false
            Feature.COPY_LOCATION -> true // Content validation is performed by the metadata engine.
        }
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4v")
        private const val ESTIMATE_DELAY_MS = 300L
    }
}
