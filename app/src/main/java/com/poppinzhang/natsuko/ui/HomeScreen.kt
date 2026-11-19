package com.poppinzhang.natsuko.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.FeatureOrderPreferences
import com.poppinzhang.natsuko.model.Feature

private val HomeHorizontalPadding = 14.dp
private val HomeGridSpacing = 8.dp
private val FeatureCardPaddingHorizontal = 12.dp
private val FeatureCardPaddingTop = 12.dp
private val FeatureCardPaddingBottom = 13.dp
private val FeatureIconBadgeSize = 40.dp
private val FeatureIconCategoryGap = 8.dp

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
    val gridState = rememberLazyGridState()
    val reorder = remember(preferences, gridState) {
        HomeFeatureReorderState(gridState, preferences.read()) { preferences.write(it) }
    }
    DisposableEffect(reorder, preferences) {
        val unsubscribe = preferences.observe(reorder::updateSavedOrder)
        onDispose { unsubscribe() }
    }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val edgeSize = with(density) { 64.dp.toPx() }
    val scrollSpeed = with(density) { 700.dp.toPx() }
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
        val gridContentPadding = PaddingValues(
            start = HomeHorizontalPadding,
            end = HomeHorizontalPadding,
            top = padding.calculateTopPadding() + 20.dp,
            bottom = padding.calculateBottomPadding() + 20.dp,
        )
        val startContentPaddingPx = with(density) {
            gridContentPadding.calculateLeftPadding(layoutDirection).roundToPx().toFloat()
        }
        val categoryStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
        val titleStyle = MaterialTheme.typography.titleSmall.copy(
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val subtitleStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp)
        val featureTexts = reorder.order.map { feature ->
            Triple(
                stringResource(feature.homeStyle().category),
                stringResource(feature.titleRes),
                stringResource(feature.subtitleRes),
            )
        }
        val textMeasurer = rememberTextMeasurer()

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columnWidth = ((maxWidth - HomeHorizontalPadding * 2 - HomeGridSpacing) / 2f).coerceAtLeast(1.dp)
            val textMaxWidthPx = with(density) {
                (columnWidth - FeatureCardPaddingHorizontal * 2).coerceAtLeast(1.dp).roundToPx()
            }
            val categoryMaxWidthPx = with(density) {
                (columnWidth - FeatureCardPaddingHorizontal * 2 - FeatureIconBadgeSize - FeatureIconCategoryGap)
                    .coerceAtLeast(1.dp)
                    .roundToPx()
            }
            val uniformCardHeight = remember(
                featureTexts,
                textMaxWidthPx,
                categoryMaxWidthPx,
                density,
                categoryStyle,
                titleStyle,
                subtitleStyle,
            ) {
                val maxCardPx = featureTexts.maxOfOrNull { (category, title, subtitle) ->
                    val categoryHeight = textMeasurer.measure(
                        text = category,
                        style = categoryStyle,
                        constraints = Constraints(maxWidth = categoryMaxWidthPx),
                    ).size.height
                    val titleHeight = textMeasurer.measure(
                        text = title,
                        style = titleStyle,
                        constraints = Constraints(maxWidth = textMaxWidthPx),
                    ).size.height
                    val subtitleHeight = textMeasurer.measure(
                        text = subtitle,
                        style = subtitleStyle,
                        constraints = Constraints(maxWidth = textMaxWidthPx),
                    ).size.height
                    val topRowPx = maxOf(with(density) { FeatureIconBadgeSize.roundToPx() }, categoryHeight)
                    val verticalSpacingPx = with(density) {
                        (FeatureCardPaddingTop + 10.dp + 4.dp + FeatureCardPaddingBottom).roundToPx()
                    }
                    verticalSpacingPx + topRowPx + titleHeight + subtitleHeight
                } ?: 0
                with(density) { maxCardPx.toDp() }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                userScrollEnabled = reorder.draggedFeature == null,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("home-list")
                    .pointerInput(reorder, startContentPaddingPx) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { position ->
                                if (reorder.start(position, startContentPaddingPx)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onDrag = { change, amount ->
                                if (reorder.draggedFeature != null) {
                                    change.consume()
                                    reorder.dragBy(amount)
                                }
                            },
                            onDragEnd = reorder::finish,
                            onDragCancel = reorder::cancel,
                        )
                    },
                contentPadding = gridContentPadding,
                horizontalArrangement = Arrangement.spacedBy(HomeGridSpacing),
                verticalArrangement = Arrangement.spacedBy(HomeGridSpacing),
            ) {
                item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Hero() }
                        TextButton(onClick = onSettings, modifier = Modifier.testTag("home-settings")) {
                            Text(stringResource(R.string.main_settings))
                        }
                    }
                }
                state.successMessage?.let { message ->
                    item(key = "success", span = { GridItemSpan(maxLineSpan) }) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(message.asString(), fontWeight = FontWeight.Bold)
                                if (state.isSavingToUserStorage) {
                                    UserStorageProgress(state.progress)
                                } else {
                                    Text(
                                        if (state.outcome?.outputs?.all { it.userStorage != null } == true) {
                                            stringResource(R.string.main_saved_user_directory)
                                        } else {
                                            stringResource(R.string.main_saved_app_directory)
                                        },
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
                item(key = "reorder-hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.home_reorder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                    )
                }
                itemsIndexed(reorder.order, key = { _, feature -> feature.name }) { index, feature ->
                    val dragging = reorder.draggedFeature == feature
                    val translation = reorder.translationFor(feature)
                    val moveEarlier = stringResource(R.string.main_move_earlier)
                    val moveLater = stringResource(R.string.main_move_later)
                    val position = stringResource(R.string.home_feature_position, index + 1, reorder.order.size)
                    val description = if (dragging) stringResource(R.string.home_feature_dragging, position) else position
                    FeatureCard(
                        feature = feature,
                        dragging = dragging,
                        minHeight = uniformCardHeight,
                        categoryStyle = categoryStyle,
                        titleStyle = titleStyle,
                        subtitleStyle = subtitleStyle,
                        onClick = onFeature,
                        modifier = Modifier
                            .animateItem(placementSpec = if (dragging) null else spring())
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = translation.x
                                translationY = translation.y
                            }
                            .semantics {
                                stateDescription = description
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(moveEarlier) { reorder.moveBy(feature, -1) })
                                    if (index < reorder.order.lastIndex) add(CustomAccessibilityAction(moveLater) { reorder.moveBy(feature, 1) })
                                }
                            },
                    )
                }
                item(key = "storage-hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.main_storage_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, top = 8.dp),
                    )
                }
                item(key = "about", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth().testTag("home-about")) {
                        Text(stringResource(R.string.main_about))
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero() {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(66.dp),
            shape = RoundedCornerShape(21.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.padding(3.5.dp).clip(RoundedCornerShape(17.5.dp)),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.main_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeatureCard(
    feature: Feature,
    dragging: Boolean,
    minHeight: Dp,
    categoryStyle: TextStyle,
    titleStyle: TextStyle,
    subtitleStyle: TextStyle,
    onClick: (Feature) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = feature.homeStyle()
    val iconAccent = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) lerp(style.accent, Color.White, 0.45f) else style.accent
    val shape = RoundedCornerShape(20.dp)
    // Foundation click semantics remain attached when lazy grid items move or leave the viewport.
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
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
        colors = CardDefaults.cardColors(
            containerColor = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = FeatureCardPaddingHorizontal,
                    top = FeatureCardPaddingTop,
                    end = FeatureCardPaddingHorizontal,
                    bottom = FeatureCardPaddingBottom,
                ),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(FeatureIconBadgeSize)
                        .clip(RoundedCornerShape(13.dp))
                        .background(iconAccent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(style.icon, contentDescription = null, tint = iconAccent, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(FeatureIconCategoryGap))
                Text(
                    stringResource(style.category),
                    style = categoryStyle,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(feature.titleRes),
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(feature.subtitleRes),
                style = subtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    Feature.DOWNLOAD_TWITTER_IMAGES -> HomeFeatureStyle(Icons.Outlined.KeyboardArrowDown, Color(0xFF43648A), R.string.main_images)
    Feature.PDF_TO_IMAGES -> HomeFeatureStyle(Icons.Outlined.AccountBox, Color(0xFF3E6F78), R.string.main_documents)
    Feature.VIDEO_TO_GIF -> HomeFeatureStyle(Icons.Outlined.PlayArrow, Color(0xFF6C6534), R.string.main_video_animation)
}
