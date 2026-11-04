@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.poppinzhang.natsuko.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.AdjustmentEdits
import com.poppinzhang.natsuko.model.AdjustmentExportSettings
import com.poppinzhang.natsuko.model.AdjustmentFormat
import com.poppinzhang.natsuko.model.AdjustmentTool
import com.poppinzhang.natsuko.model.CropAspect
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.processing.AdjustmentEditing
import com.poppinzhang.natsuko.processing.AdjustmentGeometry
import com.poppinzhang.natsuko.processing.ResolvedAdjustment
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun ImageAdjustmentChooserScreen(
    choices: List<InputItem>,
    onBack: () -> Unit,
    onChoose: (InputItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.adjust_choose_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.main_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("adjust-choices"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    pluralStringResource(R.plurals.adjust_choose_hint, choices.size, choices.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(choices, key = { _, item -> item.uri.toString() }) { index, item ->
                val thumbnail = rememberInputThumbnailState(item, thumbnailSizePx = 192)
                Card(
                    onClick = { onChoose(item) },
                    modifier = Modifier.fillMaxWidth().testTag("adjust-choice-$index"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) {
                            ThumbnailContent(thumbnail, Modifier.fillMaxSize(), ContentScale.Crop)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            item.mimeType?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageAdjustmentEditorScreen(
    adjustment: ImageAdjustmentState,
    viewModel: NatsukoViewModel,
    onRepick: () -> Unit,
) {
    val source = adjustment.source
    val preview = adjustment.preview
    val resolved = adjustment.resolved
    val ready = source != null && preview != null && resolved != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_image_adjustment), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = viewModel::back, modifier = Modifier.testTag("adjust-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.main_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undoAdjustment, enabled = ready && adjustment.history.canUndo, modifier = Modifier.testTag("adjust-undo")) {
                        Icon(AdjustmentIcons.Undo, stringResource(R.string.adjust_undo))
                    }
                    IconButton(onClick = viewModel::redoAdjustment, enabled = ready && adjustment.history.canRedo, modifier = Modifier.testTag("adjust-redo")) {
                        Icon(AdjustmentIcons.Redo, stringResource(R.string.adjust_redo))
                    }
                    IconButton(
                        onClick = viewModel::restoreAdjustmentOriginal,
                        enabled = ready && adjustment.edits != AdjustmentEdits(),
                        modifier = Modifier.testTag("adjust-restore"),
                    ) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.adjust_restore_original))
                    }
                    TextButton(onClick = viewModel::openAdjustmentExport, enabled = ready, modifier = Modifier.testTag("adjust-export")) {
                        Text(stringResource(R.string.adjust_export), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val error = adjustment.loadError
            when {
                error != null -> LoadFailure(error.asString(), onBack = viewModel::back, onRepick = onRepick)
                !ready -> Column(
                    Modifier.fillMaxSize().testTag("adjust-loading"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.adjust_loading))
                }
                else -> EditorContent(adjustment, checkNotNull(resolved), viewModel)
            }
        }
    }
}

@Composable
private fun LoadFailure(message: String, onBack: () -> Unit, onRepick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("adjust-load-failed"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.adjust_load_failed), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.main_back)) }
            Button(onClick = onRepick, modifier = Modifier.testTag("adjust-repick")) { Text(stringResource(R.string.main_select_again)) }
        }
    }
}

@Composable
private fun EditorContent(adjustment: ImageAdjustmentState, resolved: ResolvedAdjustment, viewModel: NatsukoViewModel) {
    val source = checkNotNull(adjustment.source)
    val edits = adjustment.edits
    val mode = when (adjustment.tool) {
        AdjustmentTool.CROP -> AdjustmentCanvasMode.CROP
        AdjustmentTool.STRAIGHTEN -> AdjustmentCanvasMode.STRAIGHTEN
        else -> AdjustmentCanvasMode.RESULT
    }
    val canvas: @Composable (Modifier) -> Unit = { modifier ->
        Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            AdjustmentCanvas(
                preview = checkNotNull(adjustment.preview),
                source = source,
                edits = edits,
                resolved = resolved,
                mode = mode,
                description = stringResource(R.string.adjust_preview_description, resolved.outputWidth, resolved.outputHeight),
                modifier = Modifier.fillMaxSize().testTag("adjust-canvas"),
                onCropChange = viewModel::updateAdjustmentCrop,
                onCropCancel = viewModel::cancelAdjustmentGesture,
            )
        }
    }
    val controls: @Composable ColumnScope.() -> Unit = {
        OutputSizeLabel(resolved, edits.scale)
        ToolPanel(adjustment.tool, edits, resolved, source.width, source.height, viewModel)
        HorizontalDivider()
        ToolBar(adjustment.tool, viewModel::selectAdjustmentTool)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).navigationBarsPadding(), content = controls)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().navigationBarsPadding(), content = controls)
            }
        }
    }
}

@Composable
private fun OutputSizeLabel(resolved: ResolvedAdjustment, scale: Double) {
    val locale = LocalConfiguration.current.locales[0]
    val text = if (scale < 1.0) {
        val percent = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }.format(scale)
        stringResource(R.string.adjust_output_size_reduced, resolved.outputWidth, resolved.outputHeight, percent)
    } else {
        stringResource(R.string.adjust_output_size, resolved.outputWidth, resolved.outputHeight)
    }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("adjust-output-size"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ToolBar(selected: AdjustmentTool, onSelect: (AdjustmentTool) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).selectableGroup()) {
        AdjustmentTool.entries.forEach { tool ->
            val isSelected = tool == selected
            val color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .selectable(selected = isSelected, onClick = { onSelect(tool) }, role = Role.Tab)
                    .padding(vertical = 8.dp)
                    .testTag("adjust-tool-${tool.name.lowercase()}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(toolIcon(tool), contentDescription = null, tint = color)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(toolLabel(tool)),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun toolIcon(tool: AdjustmentTool): ImageVector = when (tool) {
    AdjustmentTool.CROP -> AdjustmentIcons.Crop
    AdjustmentTool.STRAIGHTEN -> AdjustmentIcons.Straighten
    AdjustmentTool.ROTATE -> AdjustmentIcons.RotateRight
    AdjustmentTool.MIRROR -> AdjustmentIcons.FlipHorizontal
    AdjustmentTool.RESIZE -> AdjustmentIcons.Resize
}

private fun toolLabel(tool: AdjustmentTool): Int = when (tool) {
    AdjustmentTool.CROP -> R.string.adjust_tool_crop
    AdjustmentTool.STRAIGHTEN -> R.string.adjust_tool_straighten
    AdjustmentTool.ROTATE -> R.string.adjust_tool_rotate
    AdjustmentTool.MIRROR -> R.string.adjust_tool_mirror
    AdjustmentTool.RESIZE -> R.string.adjust_tool_resize
}

@Composable
private fun ToolPanel(
    tool: AdjustmentTool,
    edits: AdjustmentEdits,
    resolved: ResolvedAdjustment,
    sourceWidth: Int,
    sourceHeight: Int,
    viewModel: NatsukoViewModel,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("adjust-panel-${tool.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (tool) {
            AdjustmentTool.CROP -> CropPanel(edits, sourceWidth, sourceHeight, viewModel)
            AdjustmentTool.STRAIGHTEN -> StraightenPanel(edits, viewModel)
            AdjustmentTool.ROTATE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(AdjustmentIcons.RotateLeft, R.string.adjust_rotate_left, "adjust-rotate-left", Modifier.weight(1f)) {
                        viewModel.rotateAdjustment(clockwise = false)
                    }
                    ActionButton(AdjustmentIcons.RotateRight, R.string.adjust_rotate_right, "adjust-rotate-right", Modifier.weight(1f)) {
                        viewModel.rotateAdjustment(clockwise = true)
                    }
                }
                HintWithReset(R.string.adjust_rotate_hint, R.string.adjust_reset_rotation, edits.quarterTurns != 0) {
                    viewModel.resetAdjustmentTool(AdjustmentTool.ROTATE)
                }
            }
            AdjustmentTool.MIRROR -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(AdjustmentIcons.FlipHorizontal, R.string.adjust_flip_horizontal, "adjust-flip-horizontal", Modifier.weight(1f)) {
                        viewModel.flipAdjustment(horizontal = true)
                    }
                    ActionButton(AdjustmentIcons.FlipVertical, R.string.adjust_flip_vertical, "adjust-flip-vertical", Modifier.weight(1f)) {
                        viewModel.flipAdjustment(horizontal = false)
                    }
                }
                HintWithReset(R.string.adjust_mirror_hint, R.string.adjust_reset_mirror, edits.mirrored) {
                    viewModel.resetAdjustmentTool(AdjustmentTool.MIRROR)
                }
            }
            AdjustmentTool.RESIZE -> ResizePanel(edits, resolved, viewModel)
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: Int, tag: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HintWithReset(hint: Int, reset: Int, resetEnabled: Boolean, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(hint),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onReset, enabled = resetEnabled, modifier = Modifier.testTag("adjust-reset-tool")) { Text(stringResource(reset)) }
    }
}

@Composable
private fun CropPanel(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int, viewModel: NatsukoViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CropAspect.entries.forEach { aspect ->
            val selected = edits.aspect == aspect
            FilterChip(
                selected = selected,
                onClick = { viewModel.selectAdjustmentAspect(aspect) },
                label = { Text(aspectLabel(aspect, selected && edits.aspectSwapped)) },
                modifier = Modifier.testTag("adjust-aspect-${aspect.name.lowercase()}"),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val ratio = AdjustmentGeometry.aspectRatio(edits, sourceWidth, sourceHeight)
        if (AdjustmentEditing.isOrientable(edits.aspect) && ratio != null && abs(ratio - 1) > 1e-9) {
            val description = stringResource(R.string.adjust_aspect_swap_description)
            FilterChip(
                selected = ratio < 1,
                onClick = viewModel::swapAdjustmentAspect,
                label = { Text(stringResource(R.string.adjust_aspect_portrait)) },
                leadingIcon = { Icon(AdjustmentIcons.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.semantics { contentDescription = description }.testTag("adjust-aspect-swap"),
            )
        } else {
            Text(
                stringResource(R.string.adjust_crop_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { viewModel.resetAdjustmentTool(AdjustmentTool.CROP) },
            enabled = edits.crop != null || edits.aspect != CropAspect.FREE,
            modifier = Modifier.testTag("adjust-reset-tool"),
        ) { Text(stringResource(R.string.adjust_reset_crop)) }
    }
}

@Composable
private fun aspectLabel(aspect: CropAspect, swapped: Boolean): String = when (aspect) {
    CropAspect.FREE -> stringResource(R.string.adjust_aspect_free)
    CropAspect.ORIGINAL -> stringResource(R.string.adjust_aspect_original)
    else -> if (swapped) {
        stringResource(R.string.adjust_aspect_ratio, aspect.ratioHeight, aspect.ratioWidth)
    } else {
        stringResource(R.string.adjust_aspect_ratio, aspect.ratioWidth, aspect.ratioHeight)
    }
}

@Composable
private fun StraightenPanel(edits: AdjustmentEdits, viewModel: NatsukoViewModel) {
    val tenths = edits.straightenTenths
    val label = stringResource(R.string.adjust_straighten_label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(
            stringResource(R.string.adjust_angle_value, tenths / 10.0),
            modifier = Modifier.testTag("adjust-straighten-value"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val decrease = stringResource(R.string.adjust_nudge_down_description)
        val increase = stringResource(R.string.adjust_nudge_up_description)
        TextButton(
            onClick = { viewModel.nudgeAdjustmentStraighten(-1) },
            enabled = tenths > -AdjustmentGeometry.MAX_STRAIGHTEN_TENTHS,
            modifier = Modifier.semantics { contentDescription = decrease }.testTag("adjust-straighten-down"),
        ) { Text(stringResource(R.string.adjust_nudge_down)) }
        Slider(
            value = tenths / 10f,
            onValueChange = { viewModel.updateAdjustmentStraighten((it * 10).roundToInt(), commit = false) },
            onValueChangeFinished = viewModel::commitAdjustmentGesture,
            valueRange = -45f..45f,
            modifier = Modifier.weight(1f).semantics { contentDescription = label }.testTag("adjust-straighten-slider"),
        )
        TextButton(
            onClick = { viewModel.nudgeAdjustmentStraighten(1) },
            enabled = tenths < AdjustmentGeometry.MAX_STRAIGHTEN_TENTHS,
            modifier = Modifier.semantics { contentDescription = increase }.testTag("adjust-straighten-up"),
        ) { Text(stringResource(R.string.adjust_nudge_up)) }
    }
    HintWithReset(R.string.adjust_straighten_hint, R.string.adjust_reset_straighten, tenths != 0) {
        viewModel.resetAdjustmentTool(AdjustmentTool.STRAIGHTEN)
    }
}

@Composable
private fun ResizePanel(edits: AdjustmentEdits, resolved: ResolvedAdjustment, viewModel: NatsukoViewModel) {
    val baseWidth = resolved.geometryWidth
    val baseHeight = resolved.geometryHeight
    Text(
        stringResource(R.string.adjust_resize_base, baseWidth, baseHeight),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(100, 75, 50, 25).forEach { percent ->
            FilterChip(
                selected = abs(edits.scale - percent / 100.0) < 1e-9,
                onClick = { viewModel.setAdjustmentScale(percent / 100.0) },
                label = { Text(stringResource(R.string.adjust_percent, percent)) },
                modifier = Modifier.testTag("adjust-scale-$percent"),
            )
        }
    }
    // Typing one side shows the linked other side; nothing changes until the size is applied.
    var width by remember(resolved.outputWidth, resolved.outputHeight) { mutableStateOf(resolved.outputWidth.toString()) }
    var height by remember(resolved.outputWidth, resolved.outputHeight) { mutableStateOf(resolved.outputHeight.toString()) }
    var editingWidth by remember(resolved.outputWidth, resolved.outputHeight) { mutableStateOf(true) }
    val typed = (if (editingWidth) width else height).toIntOrNull()
    val limit = if (editingWidth) baseWidth else baseHeight
    val valid = typed != null && typed in 1..limit
    val changed = width != resolved.outputWidth.toString() || height != resolved.outputHeight.toString()
    val apply = {
        if (valid) {
            if (editingWidth) viewModel.setAdjustmentTargetSize(width = typed) else viewModel.setAdjustmentTargetSize(height = typed)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = width,
            onValueChange = { value ->
                width = value.filter(Char::isDigit).take(6)
                editingWidth = true
                width.toIntOrNull()?.takeIf { it in 1..baseWidth }?.let {
                    height = AdjustmentGeometry.scaledSize(baseWidth, baseHeight, it.toDouble() / baseWidth).height.toString()
                }
            },
            label = { Text(stringResource(R.string.adjust_target_width)) },
            singleLine = true,
            isError = editingWidth && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply() }),
            modifier = Modifier.weight(1f).testTag("adjust-target-width"),
        )
        OutlinedTextField(
            value = height,
            onValueChange = { value ->
                height = value.filter(Char::isDigit).take(6)
                editingWidth = false
                height.toIntOrNull()?.takeIf { it in 1..baseHeight }?.let {
                    width = AdjustmentGeometry.scaledSize(baseWidth, baseHeight, it.toDouble() / baseHeight).width.toString()
                }
            },
            label = { Text(stringResource(R.string.adjust_target_height)) },
            singleLine = true,
            isError = !editingWidth && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply() }),
            modifier = Modifier.weight(1f).testTag("adjust-target-height"),
        )
        Button(onClick = apply, enabled = valid && changed, modifier = Modifier.testTag("adjust-apply-size")) {
            Text(stringResource(R.string.adjust_apply_size))
        }
    }
    if (!valid) {
        Text(
            stringResource(R.string.adjust_target_invalid, limit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    HintWithReset(R.string.adjust_resize_hint, R.string.adjust_reset_resize, edits.scale < 1.0) {
        viewModel.resetAdjustmentTool(AdjustmentTool.RESIZE)
    }
}

@Composable
internal fun ImageAdjustmentExportScreen(adjustment: ImageAdjustmentState, isBusy: Boolean, viewModel: NatsukoViewModel) {
    val source = adjustment.source ?: return
    val preview = adjustment.preview ?: return
    val resolved = adjustment.resolved ?: return
    val settings = adjustment.exportSettings ?: return
    val composite = settings.format == AdjustmentFormat.JPEG && source.hasTransparency
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.adjust_export_title)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::back, modifier = Modifier.testTag("adjust-export-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.main_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = viewModel::saveAdjustment,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp).testTag("adjust-save"),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(stringResource(R.string.adjust_save), fontWeight = FontWeight.Bold)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("adjust-export-list"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    AdjustmentCanvas(
                        preview = preview,
                        source = source,
                        edits = adjustment.edits,
                        resolved = resolved,
                        mode = AdjustmentCanvasMode.RESULT,
                        description = stringResource(R.string.adjust_export_preview_description, resolved.outputWidth, resolved.outputHeight),
                        modifier = Modifier.fillMaxSize().testTag("adjust-export-preview"),
                        background = settings.background.takeIf { composite },
                        interactive = false,
                    )
                }
            }
            item { ExportSummary(adjustment, resolved, isBusy, viewModel) }
            item {
                ExportChoice(stringResource(R.string.adjust_format)) {
                    AdjustmentFormat.entries.forEach { format ->
                        FilterChip(
                            selected = settings.format == format,
                            onClick = { viewModel.updateAdjustmentExportSettings(settings.copy(format = format)) },
                            label = { Text(stringResource(formatName(format))) },
                            enabled = !isBusy,
                            modifier = Modifier.testTag("adjust-format-${format.name.lowercase()}"),
                        )
                    }
                }
            }
            if (settings.format == AdjustmentFormat.WEBP) {
                item {
                    ExportChoice(stringResource(R.string.adjust_compression)) {
                        listOf(true to R.string.adjust_lossless, false to R.string.adjust_lossy).forEach { (lossless, label) ->
                            FilterChip(
                                selected = settings.webpLossless == lossless,
                                onClick = { viewModel.updateAdjustmentExportSettings(settings.copy(webpLossless = lossless)) },
                                label = { Text(stringResource(label)) },
                                enabled = !isBusy,
                                modifier = Modifier.testTag(if (lossless) "adjust-webp-lossless" else "adjust-webp-lossy"),
                            )
                        }
                    }
                }
            }
            if (settings.isLossy) {
                item {
                    ExportChoice(stringResource(R.string.adjust_quality)) {
                        AdjustmentExportSettings.QUALITIES.forEach { quality ->
                            FilterChip(
                                selected = settings.quality == quality,
                                onClick = { viewModel.updateAdjustmentExportSettings(settings.copy(quality = quality)) },
                                label = { Text(stringResource(R.string.adjust_percent, quality)) },
                                enabled = !isBusy,
                                modifier = Modifier.testTag("adjust-quality-$quality"),
                            )
                        }
                    }
                }
            }
            if (composite) {
                item {
                    ExportChoice(stringResource(R.string.adjust_background)) {
                        listOf(
                            AdjustmentExportSettings.BACKGROUND_WHITE to R.string.main_color_white,
                            AdjustmentExportSettings.BACKGROUND_BLACK to R.string.main_color_black,
                        ).forEach { (argb, name) ->
                            val isSelected = settings.background == argb
                            val colorName = stringResource(name)
                            Surface(
                                onClick = { viewModel.updateAdjustmentExportSettings(settings.copy(background = argb)) },
                                enabled = !isBusy,
                                modifier = Modifier.size(if (isSelected) 44.dp else 40.dp).semantics {
                                    contentDescription = colorName
                                    selected = isSelected
                                }.testTag("adjust-background-${if (argb == AdjustmentExportSettings.BACKGROUND_WHITE) "white" else "black"}"),
                                shape = CircleShape,
                                color = Color(argb),
                                border = BorderStroke(
                                    if (isSelected) 3.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                            ) {}
                        }
                    }
                }
            }
            item { ExportNotes(settings, source.hasTransparency, source.convertsColor) }
        }
    }
}

private fun formatName(format: AdjustmentFormat): Int = when (format) {
    AdjustmentFormat.PNG -> R.string.adjust_format_png
    AdjustmentFormat.JPEG -> R.string.main_jpeg
    AdjustmentFormat.WEBP -> R.string.adjust_format_webp
}

@Composable
private fun ExportChoice(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun ExportSummary(adjustment: ImageAdjustmentState, resolved: ResolvedAdjustment, isBusy: Boolean, viewModel: NatsukoViewModel) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryRow(
                stringResource(R.string.adjust_original_size),
                adjustment.source?.fileSize?.let { Formatter.formatShortFileSize(context, it) } ?: stringResource(R.string.adjust_unavailable),
            )
            SummaryRow(
                stringResource(R.string.adjust_final_dimensions),
                stringResource(R.string.adjust_dimensions, resolved.outputWidth, resolved.outputHeight),
                "adjust-final-dimensions",
            )
            when (val estimate = adjustment.estimate) {
                is AdjustmentEstimate.Ready -> SummaryRow(
                    stringResource(R.string.adjust_estimated_size),
                    Formatter.formatShortFileSize(context, estimate.bytes),
                    "adjust-estimated-size",
                )
                is AdjustmentEstimate.Failed -> {
                    SummaryRow(stringResource(R.string.adjust_estimated_size), stringResource(R.string.adjust_estimate_failed), "adjust-estimated-size")
                    Text(estimate.message.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = viewModel::retryAdjustmentEstimate, enabled = !isBusy) { Text(stringResource(R.string.adjust_retry)) }
                        if (estimate.suggestsReduction) {
                            TextButton(onClick = viewModel::reduceAdjustmentSize, enabled = !isBusy, modifier = Modifier.testTag("adjust-reduce-size")) {
                                Text(stringResource(R.string.adjust_tool_resize))
                            }
                        }
                    }
                }
                else -> {
                    SummaryRow(stringResource(R.string.adjust_estimated_size), stringResource(R.string.adjust_calculating), "adjust-estimated-size")
                    (estimate as? AdjustmentEstimate.Calculating)?.let {
                        Text(it.stage.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, tag: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
private fun ExportNotes(settings: AdjustmentExportSettings, hasTransparency: Boolean, convertsColor: Boolean) {
    val notes = buildList {
        when {
            settings.format == AdjustmentFormat.PNG -> add(R.string.adjust_note_png)
            settings.format == AdjustmentFormat.JPEG -> {
                add(R.string.adjust_note_jpeg)
                if (hasTransparency) add(R.string.adjust_note_jpeg_background)
            }
            settings.webpLossless -> add(R.string.adjust_note_webp_lossless)
            else -> add(R.string.adjust_note_webp_lossy)
        }
        if (settings.isLossy) add(R.string.adjust_note_quality)
        add(R.string.adjust_note_reencode)
        if (convertsColor) add(R.string.adjust_note_color)
        add(R.string.adjust_note_metadata)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("adjust-export-notes")) {
        notes.forEach {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DiscardAdjustmentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adjust_discard_title)) },
        text = { Text(stringResource(R.string.adjust_discard_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("adjust-discard-confirm")) { Text(stringResource(R.string.adjust_discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("adjust-discard-keep")) { Text(stringResource(R.string.adjust_discard_keep)) }
        },
    )
}
