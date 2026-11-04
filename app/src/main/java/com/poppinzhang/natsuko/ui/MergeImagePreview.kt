package com.poppinzhang.natsuko.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.MergeOptions
import com.poppinzhang.natsuko.processing.BitmapLoader
import com.poppinzhang.natsuko.processing.ImagePlacement
import com.poppinzhang.natsuko.processing.ImagePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MergeImagePreview(
    inputs: List<InputItem>,
    options: MergeOptions,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    // URI order is deliberately independent of the user's arrangement: moving an image
    // retains its decoded preview and never briefly displays another image's bitmap.
    val uris = inputs.map { it.uri }.distinct().sortedBy { it.toString() }
    val previews = rememberMergePreviews(uris)
    var selectedUri by remember { mutableStateOf(inputs.firstOrNull()?.uri) }
    val selectedIndex = inputs.indexOfFirst { it.uri == selectedUri }.coerceAtLeast(0)
    val loadedIndices = inputs.indices.filter { previews[inputs[it].uri] != null }
    val loading = uris.any { !previews.containsKey(it) }
    val failureCount = inputs.count { previews.containsKey(it.uri) && previews[it.uri] == null }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(360.dp).testTag("merge-preview"),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            BoxWithConstraints(
                modifier = Modifier.padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (loading || loadedIndices.isEmpty()) {
                    Text(
                        when {
                            inputs.isEmpty() -> stringResource(R.string.main_merge_select_preview)
                            loading -> stringResource(R.string.main_merge_loading, previews.size, uris.size)
                            else -> stringResource(R.string.main_merge_preview_unavailable)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val sizes = loadedIndices.map { checkNotNull(previews[inputs[it].uri]).mergeSize }
                    val plan = remember(sizes, options.direction, constraints.maxWidth, constraints.maxHeight) {
                        mergePreviewLayout(sizes, options.direction, constraints.maxWidth, constraints.maxHeight)
                    }
                    val density = LocalDensity.current
                    var draggedPosition by remember(inputs, plan) { mutableStateOf<Int?>(null) }
                    var dropPosition by remember(inputs, plan) { mutableStateOf<Int?>(null) }
                    var dragOffset by remember(inputs, plan) { mutableStateOf(Offset.Zero) }
                    val highlight = MaterialTheme.colorScheme.primary

                    Layout(
                        modifier = Modifier
                            .size(with(density) { plan.width.toDp() }, with(density) { plan.height.toDp() })
                            .background(Color(options.fillColor))
                            .testTag("merge-preview-canvas")
                            .pointerInput(inputs, plan) {
                                var pointer = Offset.Zero
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start ->
                                        pointer = start
                                        draggedPosition = closestPlacement(start, plan.placements)
                                        dropPosition = draggedPosition
                                        dragOffset = Offset.Zero
                                        draggedPosition?.let { selectedUri = inputs[loadedIndices[it]].uri }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        pointer += amount
                                        dragOffset += amount
                                        dropPosition = closestPlacement(pointer, plan.placements)
                                    },
                                    onDragEnd = {
                                        val from = draggedPosition
                                        val to = dropPosition
                                        draggedPosition = null
                                        dropPosition = null
                                        dragOffset = Offset.Zero
                                        if (from != null && to != null && from != to) {
                                            onMove(loadedIndices[from], loadedIndices[to])
                                        }
                                    },
                                    onDragCancel = {
                                        draggedPosition = null
                                        dropPosition = null
                                        dragOffset = Offset.Zero
                                    },
                                )
                            },
                        content = {
                            loadedIndices.forEachIndexed { position, index ->
                                val item = inputs[index]
                                val moveEarlierLabel = stringResource(R.string.main_move_earlier)
                                val moveLaterLabel = stringResource(R.string.main_move_later)
                                val removeLabel = stringResource(R.string.main_remove_named, item.displayName)
                                key(item.uri, index) {
                                    Image(
                                        bitmap = checkNotNull(previews[item.uri]).bitmap.asImageBitmap(),
                                        contentDescription = stringResource(R.string.main_image_description, index + 1, item.displayName),
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .testTag("merge-preview-item-$index")
                                            .zIndex(if (draggedPosition == position) 1f else 0f)
                                            .graphicsLayer {
                                                translationX = if (draggedPosition == position) dragOffset.x else 0f
                                                translationY = if (draggedPosition == position) dragOffset.y else 0f
                                                alpha = if (draggedPosition == position) 0.8f else 1f
                                            }
                                            .then(
                                                if (dropPosition == position && draggedPosition != position) {
                                                    Modifier.border(2.dp, highlight)
                                                } else Modifier,
                                            )
                                            .clickable { selectedUri = item.uri }
                                            .semantics {
                                                customActions = buildList {
                                                    if (index > 0) add(CustomAccessibilityAction(moveEarlierLabel) {
                                                        onMove(index, index - 1)
                                                        true
                                                    })
                                                    if (index < inputs.lastIndex) add(CustomAccessibilityAction(moveLaterLabel) {
                                                        onMove(index, index + 1)
                                                        true
                                                    })
                                                    add(CustomAccessibilityAction(removeLabel) {
                                                        onRemove(index)
                                                        true
                                                    })
                                                }
                                            },
                                    )
                                }
                            }
                        },
                    ) { measurables, _ ->
                        val children = measurables.zip(plan.placements).map { (measurable, placement) ->
                            measurable.measure(Constraints.fixed(placement.width, placement.height))
                        }
                        layout(plan.width, plan.height) {
                            children.zip(plan.placements).forEach { (child, placement) ->
                                child.place(placement.left, placement.top)
                            }
                        }
                    }
                }
            }
        }
        if (failureCount > 0) {
            Text(
                pluralStringResource(R.plurals.main_previews_failed, failureCount, failureCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        inputs.getOrNull(selectedIndex)?.let { selected ->
            Text(
                stringResource(R.string.main_image_selection, selectedIndex + 1, selected.displayName),
                modifier = Modifier.fillMaxWidth().testTag("merge-preview-selection"),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { selectedUri = inputs[selectedIndex - 1].uri },
                    enabled = selectedIndex > 0,
                ) { Text(stringResource(R.string.main_previous_image)) }
                TextButton(
                    onClick = { selectedUri = inputs[selectedIndex + 1].uri },
                    enabled = selectedIndex < inputs.lastIndex,
                ) { Text(stringResource(R.string.main_next_image)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    selectedUri = selected.uri
                    onMove(selectedIndex, selectedIndex - 1)
                }, enabled = selectedIndex > 0) {
                    Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.main_move_earlier))
                }
                IconButton(onClick = {
                    selectedUri = selected.uri
                    onMove(selectedIndex, selectedIndex + 1)
                }, enabled = selectedIndex < inputs.lastIndex) {
                    Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.main_move_later))
                }
                IconButton(onClick = { onRemove(selectedIndex) }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.main_remove_named, selected.displayName))
                }
            }
        }
    }
}

@Composable
private fun rememberMergePreviews(uris: List<Uri>): Map<Uri, ImagePreview?> {
    val context = LocalContext.current
    val previews = remember(uris) { mutableStateMapOf<Uri, ImagePreview?>() }
    DisposableEffect(uris) {
        onDispose { previews.values.forEach { it?.bitmap?.recycle() } }
    }
    LaunchedEffect(uris) {
        val loader = BitmapLoader(context)
        for (uri in uris) {
            // Retain ownership across the dispatcher boundary so cancellation cannot leak
            // a bitmap decoded just before the UI stops waiting for it.
            var decoded: ImagePreview? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { loader.decodePreview(uri).also { decoded = it } }.getOrNull()
                }
                previews[uri] = result
                decoded = null
            } finally {
                decoded?.bitmap?.recycle()
            }
        }
    }
    return previews
}

private fun closestPlacement(pointer: Offset, placements: List<ImagePlacement>): Int? =
    placements.indices.minByOrNull { index ->
        val rect = placements[index]
        val dx = pointer.x - pointer.x.coerceIn(rect.left.toFloat(), (rect.left + rect.width).toFloat())
        val dy = pointer.y - pointer.y.coerceIn(rect.top.toFloat(), (rect.top + rect.height).toFloat())
        dx * dx + dy * dy
    }
