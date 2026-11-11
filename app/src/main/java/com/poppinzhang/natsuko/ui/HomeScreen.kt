package com.poppinzhang.natsuko.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.FeatureOrderPreferences
import com.poppinzhang.natsuko.model.Feature

@Composable
internal fun HomeScreen(
    state: NatsukoUiState,
    onFeature: (Feature) -> Unit,
    onSettings: () -> Unit,
    onResults: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { FeatureOrderPreferences(context) }
    val listState = rememberLazyListState()
    val reorder = remember(preferences, listState) {
        HomeFeatureReorderState(listState, preferences.read()) { preferences.write(it) }
    }
    DisposableEffect(reorder, preferences) {
        val unsubscribe = preferences.observe(reorder::updateSavedOrder)
        onDispose { unsubscribe() }
    }
    val haptic = LocalHapticFeedback.current
    val edgeSize = with(LocalDensity.current) { 64.dp.toPx() }
    val scrollSpeed = with(LocalDensity.current) { 700.dp.toPx() }
    LaunchedEffect(reorder.draggedFeature, edgeSize, scrollSpeed) {
        if (reorder.draggedFeature != null) {
            var previousFrame = withFrameNanos { it }
            while (reorder.draggedFeature != null) {
                val frame = withFrameNanos { it }
                val elapsed = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
                previousFrame = frame
                reorder.scrollAndMove(edgeSize, scrollSpeed * elapsed)
            }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            state = listState,
            userScrollEnabled = reorder.draggedFeature == null,
            modifier = Modifier.fillMaxSize().testTag("home-list").pointerInput(reorder) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        if (reorder.start(position.y)) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        if (reorder.draggedFeature != null) {
                            change.consume()
                            reorder.dragBy(amount.y)
                        }
                    },
                    onDragEnd = reorder::finish,
                    onDragCancel = reorder::cancel,
                )
            },
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 28.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "hero") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Hero() }
                    TextButton(onClick = onSettings, modifier = Modifier.testTag("home-settings")) {
                        Text(stringResource(R.string.main_settings))
                    }
                }
            }
            state.successMessage?.let { message ->
                item(key = "success") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message.asString(), fontWeight = FontWeight.Bold)
                            if (state.isSavingToUserStorage) {
                                UserStorageProgress(state.progress)
                            } else {
                                Text(
                                    if (state.outcome?.outputs?.all { it.userStorage != null } == true) stringResource(R.string.main_saved_user_directory) else stringResource(R.string.main_saved_app_directory),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = onResults, modifier = Modifier.testTag("home-crop-results")) {
                                Text(stringResource(R.string.main_view_results))
                            }
                        }
                    }
                }
            }
            item(key = "reorder-hint") {
                Text(
                    stringResource(R.string.home_reorder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            itemsIndexed(reorder.order, key = { _, feature -> feature.name }) { index, feature ->
                val dragging = reorder.draggedFeature == feature
                val moveUp = stringResource(R.string.main_move_up)
                val moveDown = stringResource(R.string.main_move_down)
                val position = stringResource(R.string.home_feature_position, index + 1, reorder.order.size)
                val description = if (dragging) stringResource(R.string.home_feature_dragging, position) else position
                FeatureCard(
                    feature = feature,
                    dragging = dragging,
                    onClick = onFeature,
                    modifier = Modifier
                        .animateItem(placementSpec = if (dragging) null else androidx.compose.animation.core.spring())
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = reorder.translationFor(feature) }
                        .semantics {
                            stateDescription = description
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(moveUp) { reorder.moveBy(feature, -1) })
                                if (index < reorder.order.lastIndex) add(CustomAccessibilityAction(moveDown) { reorder.moveBy(feature, 1) })
                            }
                        },
                )
            }
            item(key = "storage-hint") {
                Text(
                    stringResource(R.string.main_storage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item(key = "about") {
                TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth().testTag("home-about")) {
                    Text(stringResource(R.string.main_about))
                }
            }
        }
    }
}

@Composable
private fun Hero() {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.padding(4.dp),
            )
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.main_tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeatureCard(feature: Feature, dragging: Boolean, onClick: (Feature) -> Unit, modifier: Modifier = Modifier) {
    val style = feature.homeStyle()
    val iconAccent = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) lerp(style.accent, Color.White, 0.45f) else style.accent
    val shape = RoundedCornerShape(24.dp)
    // Foundation click semantics remain attached when lazy rows move or leave the viewport.
    Card(
        modifier = modifier.fillMaxWidth()
            .shadow(if (dragging) 8.dp else 0.dp, shape)
            .clip(shape)
            .clickable(role = Role.Button) { onClick(feature) }
            .testTag(
                when (feature) {
                    Feature.DOWNLOAD_TWITTER_VIDEO -> "home-download-twitter"
                    Feature.IMAGE_ADJUSTMENT -> "home-image-adjustment"
                    Feature.COPY_LOCATION -> "home-copy-location"
                    else -> "home-feature-${feature.name}"
                },
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(iconAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(style.icon, contentDescription = null, tint = iconAccent, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(style.category), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(feature.titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(stringResource(feature.subtitleRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class HomeFeatureStyle(val icon: ImageVector, val accent: Color, @param:StringRes val category: Int)

private fun Feature.homeStyle(): HomeFeatureStyle = when (this) {
    Feature.IMAGE_MERGE -> HomeFeatureStyle(Icons.AutoMirrored.Outlined.List, Color(0xFF84546F), R.string.main_images)
    Feature.DOWNLOAD_TWITTER_VIDEO -> HomeFeatureStyle(Icons.Outlined.KeyboardArrowDown, Color(0xFF3E6F78), R.string.main_video_animation)
    Feature.IMAGES_TO_PDF -> HomeFeatureStyle(Icons.Outlined.Create, Color(0xFF9B493F), R.string.main_images)
    Feature.CROP_BLACK_BORDER -> HomeFeatureStyle(Icons.Outlined.Create, Color(0xFF3F7D55), R.string.main_images)
    Feature.IMAGE_ADJUSTMENT -> HomeFeatureStyle(AdjustmentIcons.Crop, Color(0xFF5C5A8E), R.string.main_images)
    Feature.COPY_LOCATION -> HomeFeatureStyle(Icons.Outlined.Share, Color(0xFF3D7564), R.string.main_images)
    Feature.PDF_TO_IMAGES -> HomeFeatureStyle(Icons.Outlined.AccountBox, Color(0xFF3E6F78), R.string.main_documents)
    Feature.VIDEO_TO_GIF -> HomeFeatureStyle(Icons.Outlined.PlayArrow, Color(0xFF6C6534), R.string.main_video_animation)
}
