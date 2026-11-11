@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.poppinzhang.natsuko.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.Feature
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
import com.poppinzhang.natsuko.model.ProcessingProgress
import com.poppinzhang.natsuko.processing.ImageSize
import com.poppinzhang.natsuko.processing.PdfPageLayout
import java.text.NumberFormat
import java.util.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PdfPreviewViewportHeight = 360.dp
private val PdfPreviewPageMaxSize = 280.dp

@Composable
fun NatsukoApp(viewModel: NatsukoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val updateState by viewModel.appUpdate.state.collectAsStateWithLifecycle()
    val cropPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100),
    ) { viewModel.acceptSelection(Feature.CROP_BLACK_BORDER, it) }
    val mergePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100),
    ) { viewModel.acceptSelection(Feature.IMAGE_MERGE, it) }
    val pdfImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100),
    ) { viewModel.acceptSelection(Feature.IMAGES_TO_PDF, it) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.acceptSelection(Feature.PDF_TO_IMAGES, it)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.acceptSelection(Feature.VIDEO_TO_GIF, listOf(it)) }
    }
    val adjustmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.acceptSelection(Feature.IMAGE_ADJUSTMENT, listOf(it)) }
    }
    val locationSourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocationSources(listOf(it)) }
    }
    val locationTargetsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.acceptLocationTargets(it)
    }
    val pickLocationSource: () -> Unit = {
        try {
            locationSourcePicker.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            viewModel.onLocationPickerFailure()
        }
    }
    val pickLocationTargets: () -> Unit = {
        try {
            locationTargetsPicker.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            viewModel.onLocationPickerFailure()
        }
    }
    LaunchedEffect(state.copyLocation?.sourcePickerRequested) {
        if (state.copyLocation?.sourcePickerRequested == true) {
            viewModel.onLocationSourcePickerLaunched()
            pickLocationSource()
        }
    }
    LaunchedEffect(state.copyLocation?.targetsPickerRequested) {
        if (state.copyLocation?.targetsPickerRequested == true) {
            viewModel.onLocationTargetsPickerLaunched()
            pickLocationTargets()
        }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onUserStorageDirectorySelected(it)
    }
    LaunchedEffect(state.directoryPickerRequested) {
        if (state.directoryPickerRequested) {
            viewModel.onDirectoryPickerLaunched()
            try {
                directoryPicker.launch(state.storageSettings.treeUri ?: "content://com.android.externalstorage.documents/document/primary%3ADownload".toUri())
            } catch (_: Exception) {
                viewModel.onDirectoryPickerFailure()
            }
        }
    }

    val pick: (Feature) -> Unit = { feature ->
        when (feature) {
            Feature.CROP_BLACK_BORDER -> cropPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            Feature.IMAGE_MERGE -> mergePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            Feature.IMAGES_TO_PDF -> pdfImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            Feature.PDF_TO_IMAGES -> pdfPicker.launch(arrayOf("application/pdf"))
            Feature.VIDEO_TO_GIF -> videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            Feature.DOWNLOAD_TWITTER_VIDEO -> viewModel.openTwitterDownload()
            Feature.COPY_LOCATION -> viewModel.openCopyLocation()
            Feature.IMAGE_ADJUSTMENT -> adjustmentPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    BackHandler(enabled = state.screen != AppScreen.HOME || state.isProcessing || state.isSavingToUserStorage) { viewModel.back() }

    when (state.screen) {
        AppScreen.HOME -> HomeScreen(
            state = state,
            onFeature = pick,
            onSettings = viewModel::openSettings,
            onResults = viewModel::openResults,
            onAbout = viewModel::openAbout,
        )
        AppScreen.ABOUT -> AboutScreen(
            selectedProjectId = state.aboutProjectId,
            onProject = viewModel::openOpenSourceProject,
            onBack = viewModel::back,
        )
        AppScreen.SETTINGS -> OutputStorageSettingsScreen(
            state = state,
            themeMode = themeMode,
            onThemeModeChange = viewModel::setThemeMode,
            onBack = viewModel::back,
            onEnabledChange = viewModel::setSaveToUserStorage,
            onChooseDirectory = viewModel::requestUserStorageDirectory,
            onRestoreDefault = viewModel::restoreDefaultUserStorageDirectory,
            updateState = updateState,
            onCheckUpdate = viewModel.appUpdate::check,
        )
        AppScreen.TWITTER_DOWNLOAD -> TwitterDownloadScreen(
            link = state.twitterLink,
            isBusy = state.isProcessing || state.isSavingToUserStorage,
            onLinkChange = viewModel::updateTwitterLink,
            onDownload = viewModel::downloadTwitterVideo,
            onBack = viewModel::back,
            sessionActive = state.twitterSessionActive,
            onSignOut = viewModel::forgetTwitterSession,
            onSignIn = viewModel::signInToTwitter,
        )
        AppScreen.COPY_LOCATION_SOURCE -> state.copyLocation?.let {
            CopyLocationSourceScreen(
                state = it,
                onBack = viewModel::back,
                onPick = pickLocationSource,
                onChoose = viewModel::chooseLocationSource,
            )
        }
        AppScreen.COPY_LOCATION_TARGETS -> state.copyLocation?.let {
            CopyLocationTargetsScreen(
                state = it,
                isBusy = state.isProcessing || state.isSavingToUserStorage,
                onBack = viewModel::back,
                onPick = pickLocationTargets,
            )
        }
        AppScreen.CONFIGURE -> ConfigurationScreen(
            state = state,
            onBack = viewModel::back,
            onRepick = { state.feature?.let(pick) },
            onMove = viewModel::reorderInput,
            onRemove = viewModel::removeInput,
            onMergeDirection = viewModel::updateMergeDirection,
            onMergeColor = viewModel::updateMergeColor,
            onPdfMode = viewModel::updatePdfOutputMode,
            onPdfPage = viewModel::updatePdfPagePreset,
            onPdfOrientation = viewModel::updatePdfOrientation,
            onPdfMargin = viewModel::updatePdfMargin,
            onImageFormat = viewModel::updateImageFormat,
            onImageScale = viewModel::updateImageScale,
            onJpegQuality = viewModel::updateJpegQuality,
            onGifFps = viewModel::updateGifFps,
            onGifInterval = viewModel::updateGifInterval,
            onGifWidth = viewModel::updateGifWidth,
            onGifLoop = viewModel::updateGifLoop,
            onProcess = viewModel::process,
        )
        AppScreen.RESULT -> ResultScreen(
            state = state,
            onHome = viewModel::reset,
            onStorageEnabledChange = viewModel::setSaveToUserStorage,
            onRetrySave = viewModel::retryUserStorageSave,
            onSettings = viewModel::openSettings,
            onDownloadAgain = viewModel::openTwitterDownload,
            onConvertToGif = viewModel::convertDownloadedVideoToGif,
            onContinueEditing = viewModel::continueAdjustmentEditing,
        )
        AppScreen.ADJUST_CHOOSE -> ImageAdjustmentChooserScreen(
            choices = state.adjustmentChoices,
            onBack = viewModel::back,
            onChoose = viewModel::chooseAdjustmentImage,
        )
        AppScreen.ADJUST_EDIT -> state.adjustment?.let {
            ImageAdjustmentEditorScreen(it, viewModel, onRepick = { pick(Feature.IMAGE_ADJUSTMENT) })
        }
        AppScreen.ADJUST_EXPORT -> state.adjustment?.let {
            ImageAdjustmentExportScreen(it, isBusy = state.isProcessing || state.isSavingToUserStorage, viewModel = viewModel)
        }
    }

    if (state.adjustment?.confirmDiscard == true) {
        DiscardAdjustmentDialog(onConfirm = viewModel::confirmDiscardAdjustment, onDismiss = viewModel::dismissDiscardAdjustment)
    }

    AppUpdateDialogs(updateState, viewModel.appUpdate)

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(stringResource(R.string.main_operation_failed)) },
            text = { Text(message.asString()) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.main_ok)) } },
        )
    }

    state.copyLocation?.conflict?.let { conflict ->
        CopyLocationConflictDialog(conflict, viewModel::chooseLocationConflict)
    }
    state.twitterVideoSelection?.let { selection ->
        TwitterVideoSelectionDialog(selection, viewModel::chooseTwitterDownloadScope, viewModel::cancelProcessing)
    }
    when (state.twitterLoginStep) {
        TwitterLoginStep.CONSENT -> TwitterLoginConsentDialog(viewModel::acceptTwitterLogin, viewModel::cancelProcessing)
        TwitterLoginStep.SIGN_IN -> TwitterLoginDialog(viewModel::completeTwitterLogin, viewModel::cancelProcessing)
        null -> Unit
    }
    if (state.isProcessing && state.copyLocation?.conflict == null && state.twitterVideoSelection == null && state.twitterLoginStep == null) {
        ProcessingDialog(state.progress, viewModel::cancelProcessing)
    }
}

@Composable
private fun ConfigurationScreen(
    state: NatsukoUiState,
    onBack: () -> Unit,
    onRepick: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMergeDirection: (MergeDirection) -> Unit,
    onMergeColor: (Int) -> Unit,
    onPdfMode: (PdfOutputMode) -> Unit,
    onPdfPage: (PdfPagePreset) -> Unit,
    onPdfOrientation: (PdfOrientation) -> Unit,
    onPdfMargin: (Int) -> Unit,
    onImageFormat: (ImageOutputFormat) -> Unit,
    onImageScale: (Float) -> Unit,
    onJpegQuality: (Int) -> Unit,
    onGifFps: (Int) -> Unit,
    onGifInterval: (Long) -> Unit,
    onGifWidth: (Int) -> Unit,
    onGifLoop: (Int) -> Unit,
    onProcess: () -> Unit,
) {
    val feature = state.feature ?: return
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(feature.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.main_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = onProcess,
                    enabled = state.inputs.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(stringResource(R.string.main_start_processing), fontWeight = FontWeight.Bold)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                InputSection(
                    inputs = state.inputs,
                    reorderable = feature != Feature.VIDEO_TO_GIF,
                    mergeOptions = state.mergeOptions.takeIf { feature == Feature.IMAGE_MERGE },
                    pdfOptions = state.imagesToPdfOptions.takeIf { feature == Feature.IMAGES_TO_PDF },
                    onRepick = onRepick,
                    onMove = onMove,
                    onRemove = onRemove,
                )
            }
            item { HorizontalDivider() }
            when (feature) {
                Feature.CROP_BLACK_BORDER, Feature.DOWNLOAD_TWITTER_VIDEO, Feature.IMAGE_ADJUSTMENT, Feature.COPY_LOCATION -> Unit
                Feature.IMAGE_MERGE -> item { MergeSettings(state.mergeOptions, onMergeDirection, onMergeColor) }
                Feature.IMAGES_TO_PDF -> item {
                    PdfSettings(state.imagesToPdfOptions, onPdfMode, onPdfPage, onPdfOrientation, onPdfMargin)
                }
                Feature.PDF_TO_IMAGES -> item {
                    PdfImageSettings(state.pdfToImagesOptions, onImageFormat, onImageScale, onJpegQuality)
                }
                Feature.VIDEO_TO_GIF -> item {
                    GifSettings(state.gifOptions.framesPerSecond, state.gifOptions.sampleIntervalMs, state.gifOptions.maxWidth, state.gifOptions.loopCount, onGifFps, onGifInterval, onGifWidth, onGifLoop)
                }
            }
        }
    }
}

@Composable
private fun InputSection(
    inputs: List<InputItem>,
    reorderable: Boolean,
    mergeOptions: MergeOptions?,
    pdfOptions: ImagesToPdfOptions?,
    onRepick: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val previewImages = mergeOptions != null
    val previewPdfPages = pdfOptions != null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (previewImages || previewPdfPages) pluralStringResource(R.plurals.main_images_added, inputs.size, inputs.size) else pluralStringResource(R.plurals.main_files_selected, inputs.size, inputs.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (previewImages) {
                Text(stringResource(R.string.main_merge_reorder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (previewPdfPages) {
                Text(stringResource(R.string.main_pdf_reorder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (reorderable) {
                Text(stringResource(R.string.main_output_order_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(onClick = onRepick) { Text(stringResource(R.string.main_select_again)) }
    }
    Spacer(Modifier.height(10.dp))
    if (mergeOptions != null) {
        MergeImagePreview(inputs, mergeOptions, onMove, onRemove)
        return
    }
    if (pdfOptions != null) {
        PdfImagePreview(inputs, pdfOptions, onMove, onRemove)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        inputs.forEachIndexed { index, item ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.main_item_number, index + 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item.mimeType?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (reorderable) {
                        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.main_move_up))
                        }
                        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < inputs.lastIndex) {
                            Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.main_move_down))
                        }
                    }
                    IconButton(onClick = { onRemove(index) }) { Icon(Icons.Outlined.Close, stringResource(R.string.main_remove)) }
                }
            }
        }
    }
}

@Composable
private fun PdfImagePreview(
    inputs: List<InputItem>,
    options: ImagesToPdfOptions,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(PdfPreviewViewportHeight)
            .testTag("pdf-page-preview"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("pdf-page-preview-list"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(inputs, key = { index, item -> "$index:${item.uri}" }) { index, item ->
                PdfPagePreviewItem(
                    item = item,
                    index = index,
                    itemCount = inputs.size,
                    options = options,
                    onMove = onMove,
                    onRemove = { onRemove(index) },
                )
            }
        }
    }
}

@Composable
private fun PdfPagePreviewItem(
    item: InputItem,
    index: Int,
    itemCount: Int,
    options: ImagesToPdfOptions,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit,
) {
    val thumbnail = rememberInputThumbnailState(item, thumbnailSizePx = 512)
    val bitmap = (thumbnail as? ThumbnailState.Loaded)?.bitmap
    val imageSize = bitmap?.let { ImageSize(it.width, it.height) } ?: ImageSize(595, 842)
    val pageSize = PdfPageLayout.pageSize(imageSize, options)
    val pageAspectRatio = pageSize.width.toFloat() / pageSize.height
    val previewScale = 280f / maxOf(pageSize.width, pageSize.height)
    val safeMarginPoints = options.marginPoints.coerceIn(0, minOf(pageSize.width, pageSize.height) / 3)
    val previewMargin = (safeMarginPoints * previewScale).dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier
                .then(
                    if (pageAspectRatio >= 1f) {
                        Modifier.width(PdfPreviewPageMaxSize).aspectRatio(pageAspectRatio)
                    } else {
                        Modifier.height(PdfPreviewPageMaxSize).aspectRatio(pageAspectRatio)
                    },
                )
                .testTag("pdf-page-preview-page-$index"),
            color = Color.White,
            contentColor = Color(0xFF202124),
            shadowElevation = 5.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(previewMargin).testTag("pdf-page-preview-image-frame-$index"),
                contentAlignment = Alignment.Center,
            ) {
                ThumbnailContent(
                    thumbnail = thumbnail,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.main_page_number, index + 1),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.main_page_move_up, index + 1))
            }
            IconButton(onClick = { onMove(index, index + 1) }, enabled = index < itemCount - 1) {
                Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.main_page_move_down, index + 1))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, stringResource(R.string.main_remove_named, item.displayName))
            }
        }
    }
}

@Composable
internal fun rememberInputThumbnailState(item: InputItem, thumbnailSizePx: Int): ThumbnailState {
    val context = LocalContext.current
    val thumbnail by produceState<ThumbnailState>(
        initialValue = ThumbnailState.Loading,
        key1 = item.uri,
        key2 = thumbnailSizePx,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching<Bitmap> {
                context.contentResolver.loadThumbnail(item.uri, android.util.Size(thumbnailSizePx, thumbnailSizePx), null)
            }.fold(
                onSuccess = ThumbnailState::Loaded,
                onFailure = { ThumbnailState.Failed },
            )
        }
    }
    val bitmap = (thumbnail as? ThumbnailState.Loaded)?.bitmap
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    return thumbnail
}

@Composable
internal fun ThumbnailContent(
    thumbnail: ThumbnailState,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val bitmap = (thumbnail as? ThumbnailState.Loaded)?.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (thumbnail == ThumbnailState.Loading) stringResource(R.string.main_preview_loading) else stringResource(R.string.main_preview_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal sealed interface ThumbnailState {
    data object Loading : ThumbnailState
    data object Failed : ThumbnailState
    data class Loaded(val bitmap: Bitmap) : ThumbnailState
}

@Composable
private fun SettingsTitle(title: String, description: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    description?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelected: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelected(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun MergeSettings(options: MergeOptions, onDirection: (MergeDirection) -> Unit, onColor: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsTitle(stringResource(R.string.main_merge_settings), stringResource(R.string.main_merge_settings_hint))
        Text(stringResource(R.string.main_layout), fontWeight = FontWeight.Medium)
        ChoiceChips(
            listOf(
                MergeDirection.VERTICAL to stringResource(R.string.main_vertical),
                MergeDirection.HORIZONTAL to stringResource(R.string.main_horizontal),
                MergeDirection.GRID to stringResource(R.string.main_grid),
            ),
            options.direction,
            onDirection,
        )
        if (options.direction == MergeDirection.GRID) {
            Text(stringResource(R.string.main_grid_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.main_fill_color), fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                0xFFFFFFFF.toInt() to R.string.main_color_white,
                0xFF000000.toInt() to R.string.main_color_black,
                0xFFF5EEE8.toInt() to R.string.main_color_cream,
                0xFFE9EEF5.toInt() to R.string.main_color_blue,
                0xFFFFE4E8.toInt() to R.string.main_color_pink,
            ).forEach { (argb, colorNameRes) ->
                val isSelected = options.fillColor == argb
                val colorName = stringResource(colorNameRes)
                Surface(
                    onClick = { onColor(argb) },
                    modifier = Modifier.size(if (isSelected) 44.dp else 40.dp).semantics {
                        contentDescription = colorName
                        selected = isSelected
                    },
                    shape = CircleShape,
                    color = Color(argb),
                    border = androidx.compose.foundation.BorderStroke(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                ) {}
            }
        }
    }
}

@Composable
private fun PdfSettings(
    options: ImagesToPdfOptions,
    onMode: (PdfOutputMode) -> Unit,
    onPage: (PdfPagePreset) -> Unit,
    onOrientation: (PdfOrientation) -> Unit,
    onMargin: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsTitle(stringResource(R.string.main_pdf_settings))
        Text(stringResource(R.string.main_output_mode), fontWeight = FontWeight.Medium)
        ChoiceChips(listOf(PdfOutputMode.COMBINED to stringResource(R.string.main_pdf_combined), PdfOutputMode.SEPARATE to stringResource(R.string.main_pdf_separate)), options.outputMode, onMode)
        Text(stringResource(R.string.main_page_size), fontWeight = FontWeight.Medium)
        ChoiceChips(listOf(PdfPagePreset.A4 to stringResource(R.string.main_a4), PdfPagePreset.FIT_IMAGE to stringResource(R.string.main_fit_image)), options.pagePreset, onPage)
        Text(stringResource(R.string.main_page_orientation), fontWeight = FontWeight.Medium)
        ChoiceChips(listOf(PdfOrientation.AUTO to stringResource(R.string.main_automatic), PdfOrientation.PORTRAIT to stringResource(R.string.main_portrait), PdfOrientation.LANDSCAPE to stringResource(R.string.main_landscape)), options.orientation, onOrientation)
        Text(stringResource(R.string.main_page_margin, options.marginPoints), fontWeight = FontWeight.Medium)
        Slider(value = options.marginPoints.toFloat(), onValueChange = { onMargin(it.toInt()) }, valueRange = 0f..72f, steps = 11)
    }
}

@Composable
private fun PdfImageSettings(
    options: PdfToImagesOptions,
    onFormat: (ImageOutputFormat) -> Unit,
    onScale: (Float) -> Unit,
    onQuality: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsTitle(stringResource(R.string.main_image_settings), stringResource(R.string.main_image_settings_hint))
        Text(stringResource(R.string.main_format), fontWeight = FontWeight.Medium)
        ChoiceChips(listOf(ImageOutputFormat.PNG to stringResource(R.string.main_png), ImageOutputFormat.JPEG to stringResource(R.string.main_jpeg)), options.format, onFormat)
        Text(stringResource(R.string.main_image_scale, options.scale), fontWeight = FontWeight.Medium)
        Slider(value = options.scale, onValueChange = onScale, valueRange = 1f..3f, steps = 3)
        if (options.format == ImageOutputFormat.JPEG) {
            Text(stringResource(R.string.main_jpeg_quality, options.jpegQuality), fontWeight = FontWeight.Medium)
            Slider(value = options.jpegQuality.toFloat(), onValueChange = { onQuality(it.toInt()) }, valueRange = 60f..100f, steps = 7)
        }
    }
}

@Composable
private fun GifSettings(
    fps: Int,
    interval: Long,
    width: Int,
    loop: Int,
    onFps: (Int) -> Unit,
    onInterval: (Long) -> Unit,
    onWidth: (Int) -> Unit,
    onLoop: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsTitle(stringResource(R.string.main_gif_settings), stringResource(R.string.main_gif_settings_hint))
        Text(stringResource(R.string.main_gif_fps, fps), fontWeight = FontWeight.Medium)
        Slider(value = fps.toFloat(), onValueChange = { onFps(it.toInt()) }, valueRange = 1f..30f, steps = 28)
        Text(stringResource(R.string.main_gif_interval, interval), fontWeight = FontWeight.Medium)
        Slider(value = interval.toFloat(), onValueChange = { onInterval(it.toLong()) }, valueRange = 50f..1000f, steps = 18)
        Text(stringResource(R.string.main_maximum_width), fontWeight = FontWeight.Medium)
        ChoiceChips(listOf(360, 480, 720, 1080).map { it to stringResource(R.string.main_pixel_width, it) }, width, onWidth)
        Text(stringResource(R.string.main_loop, if (loop == 0) stringResource(R.string.main_infinite) else pluralStringResource(R.plurals.main_loop_count, loop, loop)), fontWeight = FontWeight.Medium)
        ChoiceChips(listOf(0 to stringResource(R.string.main_infinite), 1 to pluralStringResource(R.plurals.main_loop_count, 1, 1), 3 to pluralStringResource(R.plurals.main_loop_count, 3, 3)), loop, onLoop)
        Text(stringResource(R.string.main_gif_frame_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProcessingDialog(progress: ProcessingProgress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { CircularProgressIndicator(modifier = Modifier.size(34.dp)) },
        title = { Text(stringResource(R.string.main_processing)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(progress.message.asString())
                if (progress.isIndeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(progress.fraction), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.main_cancel)) } },
    )
}

@Composable
private fun ResultScreen(
    state: NatsukoUiState,
    onHome: () -> Unit,
    onStorageEnabledChange: (Boolean) -> Unit,
    onRetrySave: () -> Unit,
    onSettings: () -> Unit,
    onDownloadAgain: () -> Unit = {},
    onConvertToGif: ((OutputArtifact) -> Unit)? = null,
    onContinueEditing: () -> Unit = {},
) {
    val context = LocalContext.current
    val feature = state.feature
    val outcome = state.outcome
    val outputs = outcome?.outputs.orEmpty()
    val isTwitterDownload = feature == Feature.DOWNLOAD_TWITTER_VIDEO
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(feature?.titleRes ?: R.string.main_results)) }) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onHome, enabled = !state.isSavingToUserStorage, modifier = Modifier.weight(1f).height(50.dp)) {
                        Icon(Icons.Outlined.Home, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.main_return_home))
                    }
                    Button(onClick = { shareArtifacts(context, outputs) }, enabled = outputs.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) {
                        Icon(Icons.Outlined.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.main_share_results))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("result-list"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isTwitterDownload && outputs.isEmpty()) Icons.Outlined.Close else Icons.Outlined.CheckCircle,
                        null,
                        tint = if (isTwitterDownload && outputs.isEmpty()) MaterialTheme.colorScheme.error else Color(0xFF3F7D55),
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (isTwitterDownload) {
                                if (outputs.isEmpty()) stringResource(R.string.main_download_failed) else stringResource(R.string.main_download_complete)
                            } else stringResource(R.string.main_processing_complete),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (isTwitterDownload) pluralStringResource(R.plurals.main_videos_downloaded, outputs.size, outputs.size) else pluralStringResource(R.plurals.main_files_created, outputs.size, outputs.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (feature == Feature.IMAGE_ADJUSTMENT && state.adjustment != null) {
                item {
                    OutlinedButton(
                        onClick = onContinueEditing,
                        enabled = !state.isSavingToUserStorage,
                        modifier = Modifier.testTag("adjust-continue"),
                    ) { Text(stringResource(R.string.adjust_continue_editing)) }
                }
            }
            if (isTwitterDownload) {
                item {
                    TextButton(
                        onClick = onDownloadAgain,
                        enabled = !state.isSavingToUserStorage,
                        modifier = Modifier.testTag("twitter-download-again"),
                    ) { Text(stringResource(R.string.main_download_again)) }
                }
            }
            itemsIndexed(outputs) { index, output ->
                OutputRow(
                    output = output,
                    context = context,
                    onConvertToGif = onConvertToGif?.takeIf { isTwitterDownload && output.mimeType.startsWith("video/") },
                    conversionEnabled = !state.isSavingToUserStorage,
                    conversionTag = "twitter-convert-gif-$index",
                )
            }
            outcome?.skipped?.takeIf { it.isNotEmpty() }?.let { skipped ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.location_skipped_summary, skipped.size), fontWeight = FontWeight.Bold)
                            skipped.forEach {
                                Text(stringResource(R.string.main_failure_detail, it.source.asString(), it.reason.asString()), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            outcome?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (isTwitterDownload) pluralStringResource(R.plurals.main_downloads_failed, failures.size, failures.size) else pluralStringResource(R.plurals.main_files_failed, failures.size, failures.size),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            failures.forEach { Text(stringResource(R.string.main_failure_detail, it.source.asString(), it.reason.asString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                        }
                    }
                }
            }
            if (outputs.isNotEmpty()) {
                item {
                    val locations = outputs.map { it.userStorage?.directoryLabel ?: it.file.parentFile?.absolutePath.orEmpty() }.distinct()
                    Text(stringResource(R.string.main_save_location, locations.joinToString("\n")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    UserStorageSwitch(
                        checked = state.storageSettings.enabled,
                        enabled = !state.isSavingToUserStorage,
                        description = if (state.storageSettings.enabled) stringResource(R.string.main_future_storage, state.storageSettings.directoryLabel) else stringResource(R.string.main_enable_storage_hint),
                        onCheckedChange = onStorageEnabledChange,
                        testTag = "result-user-storage-switch",
                    )
                }
                if (state.isSavingToUserStorage) {
                    item { UserStorageProgress(state.progress) }
                } else {
                    val savedCount = outputs.count { it.userStorage != null }
                    if (savedCount > 0) {
                        item { Text(pluralStringResource(R.plurals.main_results_saved, savedCount, savedCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                if (state.storageFailures.isNotEmpty()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(pluralStringResource(R.plurals.main_results_save_failed, state.storageFailures.size, state.storageFailures.size), fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.main_storage_failed_hint), style = MaterialTheme.typography.bodySmall)
                                state.storageFailures.forEach { Text(stringResource(R.string.main_failure_detail, it.source.asString(), it.reason.asString()), style = MaterialTheme.typography.bodySmall) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = onRetrySave, enabled = state.storageSettings.enabled && !state.isSavingToUserStorage) { Text(stringResource(R.string.main_retry_save)) }
                                    TextButton(onClick = onSettings, enabled = !state.isSavingToUserStorage) { Text(stringResource(R.string.main_change_storage)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputRow(
    output: OutputArtifact,
    context: Context,
    onConvertToGif: ((OutputArtifact) -> Unit)? = null,
    conversionEnabled: Boolean = true,
    conversionTag: String = "output-convert-gif",
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(output.label.asString(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                output.detail?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Text(android.text.format.Formatter.formatShortFileSize(context, output.file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { openArtifact(context, output) }) { Icon(Icons.AutoMirrored.Outlined.ExitToApp, stringResource(R.string.main_open)) }
            IconButton(onClick = { shareArtifacts(context, listOf(output)) }) { Icon(Icons.Outlined.Share, stringResource(R.string.main_share)) }
        }
        if (onConvertToGif != null) {
            OutlinedButton(
                onClick = { onConvertToGif(output) },
                enabled = conversionEnabled,
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).testTag(conversionTag),
            ) {
                Icon(Icons.Outlined.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.main_video_to_gif))
            }
        }
    }
}

private fun openArtifact(context: Context, artifact: OutputArtifact) {
    val uri = OutputRepository(context).shareUri(artifact)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, artifact.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, context.getString(R.string.main_no_viewer), Toast.LENGTH_SHORT).show()
    }
}

private fun shareArtifacts(context: Context, outputs: List<OutputArtifact>) {
    if (outputs.isEmpty()) return
    val repository = OutputRepository(context)
    val uris = outputs.map(repository::shareUri)
    val type = outputs.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.main_clipboard_label), uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.main_share_chooser))) }.onFailure {
        Toast.makeText(context, context.getString(R.string.main_no_share_receiver), Toast.LENGTH_SHORT).show()
    }
}
