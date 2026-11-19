@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.poppinzhang.natsuko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.SplitDirection
import com.poppinzhang.natsuko.model.SplitMode
import com.poppinzhang.natsuko.model.SplitOptions
import com.poppinzhang.natsuko.processing.SplitDivider

private val SplitPartsPresets = listOf(2, 3, 4, 6, 8)
private val SplitPreviewSpacingPresets = listOf(0, 12, 24, 48, 96)

private data class PreviewBackgroundSwatch(
    val argb: Int,
    val nameRes: Int,
    val tag: String,
)

private val PreviewBackgroundSwatches = listOf(
    PreviewBackgroundSwatch(SplitOptions.BACKGROUND_WHITE, R.string.main_color_white, "white"),
    PreviewBackgroundSwatch(SplitOptions.BACKGROUND_BLACK, R.string.main_color_black, "black"),
    PreviewBackgroundSwatch(SplitOptions.BACKGROUND_DIM, R.string.split_color_dim, "dim"),
    PreviewBackgroundSwatch(SplitOptions.BACKGROUND_GRAY, R.string.split_color_gray, "gray"),
    PreviewBackgroundSwatch(SplitOptions.BACKGROUND_CREAM, R.string.main_color_cream, "cream"),
    PreviewBackgroundSwatch(SplitOptions.BACKGROUND_BLUE, R.string.main_color_blue, "blue"),
    PreviewBackgroundSwatch(SplitOptions.BACKGROUND_PINK, R.string.main_color_pink, "pink"),
)

@Composable
internal fun ImageSplitChooserScreen(
    choices: List<InputItem>,
    onBack: () -> Unit,
    onChoose: (InputItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_choose_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.main_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("split-choices"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    pluralStringResource(R.plurals.split_choose_hint, choices.size, choices.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(choices, key = { _, item -> item.uri.toString() }) { index, item ->
                val thumbnail = rememberInputThumbnailState(item, thumbnailSizePx = 192)
                Card(
                    onClick = { onChoose(item) },
                    modifier = Modifier.fillMaxWidth().testTag("split-choice-$index"),
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
internal fun ImageSplitEditorScreen(
    split: ImageSplitState,
    isBusy: Boolean,
    onBack: () -> Unit,
    onRepick: () -> Unit,
    onDirection: (SplitDirection) -> Unit,
    onParts: (Int) -> Unit,
    onDividerWidth: (Int) -> Unit,
    onMode: (SplitMode) -> Unit,
    onSelectDivider: (Int?) -> Unit,
    onMoveDivider: (Int, Double) -> Unit,
    onNudgeDivider: (Int, Int) -> Unit,
    onResetDividers: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onOpenPreview: () -> Unit,
    onProcess: () -> Unit,
) {
    val source = split.source
    val preview = split.preview
    val ready = source != null && preview != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_image_split), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("split-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.main_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = onRepick,
                        enabled = !isBusy,
                        modifier = Modifier.testTag("split-repick"),
                    ) {
                        Text(stringResource(R.string.main_select_again))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (ready) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = onProcess,
                        enabled = split.canSplit && !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .height(52.dp)
                            .testTag("split-start"),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.split_start), fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val error = split.loadError
            when {
                error != null -> SplitLoadFailure(error.asString(), onBack = onBack, onRepick = onRepick)
                !ready -> Column(
                    Modifier.fillMaxSize().testTag("split-loading"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.split_loading))
                }
                else -> SplitEditorBody(
                    split = split,
                    isBusy = isBusy,
                    onDirection = onDirection,
                    onParts = onParts,
                    onDividerWidth = onDividerWidth,
                    onMode = onMode,
                    onSelectDivider = onSelectDivider,
                    onMoveDivider = onMoveDivider,
                    onNudgeDivider = onNudgeDivider,
                    onResetDividers = onResetDividers,
                    onOpenFullscreen = onOpenFullscreen,
                    onOpenPreview = onOpenPreview,
                )
            }
        }
    }
}

@Composable
private fun SplitLoadFailure(message: String, onBack: () -> Unit, onRepick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("split-load-failed"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.split_load_failed),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.main_back)) }
            Button(onClick = onRepick, modifier = Modifier.testTag("split-load-repick")) {
                Text(stringResource(R.string.main_select_again))
            }
        }
    }
}

@Composable
private fun SplitEditorBody(
    split: ImageSplitState,
    isBusy: Boolean,
    onDirection: (SplitDirection) -> Unit,
    onParts: (Int) -> Unit,
    onDividerWidth: (Int) -> Unit,
    onMode: (SplitMode) -> Unit,
    onSelectDivider: (Int?) -> Unit,
    onMoveDivider: (Int, Double) -> Unit,
    onNudgeDivider: (Int, Int) -> Unit,
    onResetDividers: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onOpenPreview: () -> Unit,
) {
    val source = checkNotNull(split.source)
    val preview = checkNotNull(split.preview)
    val plan = split.plan
    val activeDividerWidth = plan?.dividerWidthPx ?: split.options.dividerWidthPx
    val diagramDescription = stringResource(
        R.string.split_diagram_description,
        source.width,
        source.height,
        plan?.parts ?: split.options.parts,
    )

    val diagram: @Composable (Modifier) -> Unit = { modifier ->
        Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            SplitDiagramCanvas(
                preview = preview,
                source = source,
                plan = plan,
                mode = split.options.mode,
                selectedDividerIndex = split.selectedDividerIndex,
                description = diagramDescription,
                modifier = Modifier.fillMaxSize().testTag("split-diagram-canvas"),
                onSelectDivider = onSelectDivider,
                onMoveDivider = onMoveDivider,
            )
        }
    }

    val quickActionBar: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (activeDividerWidth > 0) {
                        stringResource(
                            R.string.split_image_summary_with_width,
                            source.width,
                            source.height,
                            plan?.parts ?: split.options.parts,
                            activeDividerWidth,
                        )
                    } else {
                        stringResource(
                            R.string.split_image_summary,
                            source.width,
                            source.height,
                            plan?.parts ?: split.options.parts,
                        )
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("split-summary"),
                )
                Text(
                    split.input.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenFullscreen,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).testTag("split-open-fullscreen"),
                ) {
                    Icon(SplitIcons.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.split_fullscreen), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = onOpenPreview,
                    enabled = split.canSplit && !isBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).testTag("split-open-preview"),
                ) {
                    Icon(SplitIcons.Preview, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.split_preview), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    val controls: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("split-settings-panel"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!split.canSplit) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().testTag("split-too-small-warning"),
                ) {
                    Text(
                        stringResource(R.string.split_axis_too_small),
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.split_direction), fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = split.options.direction == SplitDirection.HORIZONTAL,
                        onClick = { onDirection(SplitDirection.HORIZONTAL) },
                        label = { Text(stringResource(R.string.main_horizontal)) },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("split-direction-horizontal"),
                    )
                    FilterChip(
                        selected = split.options.direction == SplitDirection.VERTICAL,
                        onClick = { onDirection(SplitDirection.VERTICAL) },
                        label = { Text(stringResource(R.string.main_vertical)) },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("split-direction-vertical"),
                    )
                }
                Text(
                    stringResource(R.string.split_direction_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.split_parts_label,
                        SplitOptions.MIN_PARTS,
                        split.effectiveMaxParts.coerceAtLeast(SplitOptions.MIN_PARTS),
                    ),
                    fontWeight = FontWeight.Medium,
                )
                SplitPartsControl(
                    parts = split.options.parts,
                    maxParts = split.effectiveMaxParts.coerceAtLeast(SplitOptions.MIN_PARTS),
                    enabled = split.canSplit && !isBusy,
                    onParts = onParts,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.split_divider_width), fontWeight = FontWeight.Medium)
                SplitDividerWidthControl(
                    dividerWidthPx = split.options.dividerWidthPx,
                    maxDividerWidthPx = split.effectiveMaxDividerWidthPx,
                    enabled = split.canSplit && !isBusy,
                    tagPrefix = "split-divider-width",
                    onDividerWidth = onDividerWidth,
                )
                Text(
                    stringResource(R.string.split_divider_width_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.split_mode), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (split.options.mode == SplitMode.FREE) {
                        TextButton(
                            onClick = onResetDividers,
                            enabled = split.hasCustomDividers && !isBusy,
                            modifier = Modifier.testTag("split-reset-dividers"),
                        ) {
                            Text(stringResource(R.string.split_reset_equal))
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = split.options.mode == SplitMode.EQUAL,
                        onClick = { onMode(SplitMode.EQUAL) },
                        label = { Text(stringResource(R.string.split_mode_equal)) },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("split-mode-equal"),
                    )
                    FilterChip(
                        selected = split.options.mode == SplitMode.FREE,
                        onClick = { onMode(SplitMode.FREE) },
                        label = { Text(stringResource(R.string.split_mode_free)) },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("split-mode-free"),
                    )
                }
                Text(
                    stringResource(
                        if (split.options.mode == SplitMode.FREE) R.string.split_mode_free_hint
                        else R.string.split_mode_equal_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (split.options.mode == SplitMode.FREE && plan != null) {
                    val selectedIndex = split.selectedDividerIndex?.takeIf { it in plan.dividers.indices }
                    if (selectedIndex != null) {
                        SelectedDividerNudgeCard(
                            divider = plan.dividers[selectedIndex],
                            enabled = !isBusy,
                            onNudge = { delta -> onNudgeDivider(selectedIndex, delta) },
                        )
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    diagram(Modifier.weight(1f).fillMaxWidth())
                    quickActionBar()
                }
                VerticalDivider()
                controls(Modifier.width(360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                diagram(Modifier.weight(0.46f).fillMaxWidth())
                quickActionBar()
                HorizontalDivider()
                controls(Modifier.weight(0.54f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SplitPartsControl(
    parts: Int,
    maxParts: Int,
    enabled: Boolean,
    onParts: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(parts.toString()) }
    LaunchedEffect(parts) {
        if (text.toIntOrNull() != parts) {
            text = parts.toString()
        }
    }
    val commitText = {
        val parsed = text.toIntOrNull()
        val clamped = (parsed ?: parts).coerceIn(SplitOptions.MIN_PARTS, maxParts)
        text = clamped.toString()
        if (clamped != parts) onParts(clamped)
    }
    val decreaseDescription = stringResource(R.string.split_parts_decrease)
    val increaseDescription = stringResource(R.string.split_parts_increase)
    val inputDescription = stringResource(R.string.split_parts_input_description)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val next = (parts - 1).coerceIn(SplitOptions.MIN_PARTS, maxParts)
                    text = next.toString()
                    onParts(next)
                },
                enabled = enabled && parts > SplitOptions.MIN_PARTS,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = decreaseDescription }
                    .testTag("split-parts-decrease"),
            ) {
                Text("-", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(2)
                    text = digits
                    val parsed = digits.toIntOrNull()
                    if (parsed != null) {
                        if (parsed > maxParts) {
                            text = maxParts.toString()
                            onParts(maxParts)
                        } else if (parsed >= SplitOptions.MIN_PARTS) {
                            onParts(parsed)
                        }
                    }
                },
                enabled = enabled,
                singleLine = true,
                suffix = { Text(stringResource(R.string.split_parts_unit)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commitText() }),
                modifier = Modifier
                    .width(128.dp)
                    .onFocusChanged { focus -> if (!focus.isFocused) commitText() }
                    .semantics { contentDescription = inputDescription }
                    .testTag("split-parts-input"),
            )
            OutlinedButton(
                onClick = {
                    val next = (parts + 1).coerceIn(SplitOptions.MIN_PARTS, maxParts)
                    text = next.toString()
                    onParts(next)
                },
                enabled = enabled && parts < maxParts,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = increaseDescription }
                    .testTag("split-parts-increase"),
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplitPartsPresets.filter { it <= maxParts }.forEach { preset ->
                FilterChip(
                    selected = parts == preset,
                    onClick = {
                        text = preset.toString()
                        onParts(preset)
                    },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.split_parts_preset, preset)) },
                    modifier = Modifier.testTag("split-parts-preset-$preset"),
                )
            }
        }
    }
}

@Composable
private fun SplitDividerWidthControl(
    dividerWidthPx: Int,
    maxDividerWidthPx: Int,
    enabled: Boolean,
    tagPrefix: String,
    onDividerWidth: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(dividerWidthPx.toString()) }
    LaunchedEffect(dividerWidthPx) {
        val currentTyped = text.toIntOrNull() ?: 0
        if (dividerWidthPx != currentTyped) {
            text = dividerWidthPx.toString()
        }
    }
    val decreaseDescription = stringResource(R.string.split_divider_width_decrease)
    val increaseDescription = stringResource(R.string.split_divider_width_increase)
    val inputDescription = stringResource(R.string.split_divider_width)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                val next = (dividerWidthPx - 1).coerceIn(SplitOptions.MIN_DIVIDER_WIDTH_PX, maxDividerWidthPx)
                text = next.toString()
                onDividerWidth(next)
            },
            enabled = enabled && dividerWidthPx > SplitOptions.MIN_DIVIDER_WIDTH_PX,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = decreaseDescription }
                .testTag("$tagPrefix-decrease"),
        ) {
            Text("-", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(4)
                if (digits.isEmpty()) {
                    text = ""
                    onDividerWidth(0)
                } else {
                    val clamped = digits.toInt().coerceIn(SplitOptions.MIN_DIVIDER_WIDTH_PX, maxDividerWidthPx)
                    text = clamped.toString()
                    onDividerWidth(clamped)
                }
            },
            enabled = enabled,
            singleLine = true,
            suffix = { Text(stringResource(R.string.main_merge_spacing_unit)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (text.isEmpty()) text = "0" }),
            modifier = Modifier
                .width(128.dp)
                .onFocusChanged { focus -> if (!focus.isFocused && text.isEmpty()) text = "0" }
                .semantics { contentDescription = inputDescription }
                .testTag("$tagPrefix-input"),
        )
        OutlinedButton(
            onClick = {
                val next = (dividerWidthPx + 1).coerceIn(SplitOptions.MIN_DIVIDER_WIDTH_PX, maxDividerWidthPx)
                text = next.toString()
                onDividerWidth(next)
            },
            enabled = enabled && dividerWidthPx < maxDividerWidthPx,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = increaseDescription }
                .testTag("$tagPrefix-increase"),
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SelectedDividerNudgeCard(
    divider: SplitDivider,
    enabled: Boolean,
    onNudge: (Int) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag("split-divider-nudge-panel"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (divider.width > 0) {
                    stringResource(R.string.split_divider_range, divider.index + 1, divider.start, divider.end)
                } else {
                    stringResource(R.string.split_divider_position, divider.index + 1, divider.start)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("split-divider-position-text"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(-10, -1, 1, 10).forEach { delta ->
                    val label = if (delta > 0) "+$delta" else "$delta"
                    OutlinedButton(
                        onClick = { onNudge(delta) },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).testTag("split-divider-nudge-$delta"),
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Fullscreen interactive split workspace.
 * MainActivity adapts device orientation to landscape for horizontal images and portrait for vertical images;
 * the control chrome adapts into a slim side rail in landscape or slim top bar in portrait so the image fills the viewport.
 */
@Composable
internal fun ImageSplitFullscreenScreen(
    split: ImageSplitState,
    onExit: () -> Unit,
    onMode: (SplitMode) -> Unit,
    onSelectDivider: (Int?) -> Unit,
    onMoveDivider: (Int, Double) -> Unit,
    onResetDividers: () -> Unit,
    onOpenPreview: () -> Unit,
) {
    val source = split.source ?: return
    val preview = split.preview ?: return
    val plan = split.plan
    val diagramDescription = stringResource(
        R.string.split_diagram_description,
        source.width,
        source.height,
        plan?.parts ?: split.options.parts,
    )

    Surface(
        modifier = Modifier.fillMaxSize().testTag("split-fullscreen-root"),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().displayCutoutPadding()) {
            val isLandscapeViewport = maxWidth > maxHeight
            if (isLandscapeViewport) {
                Row(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        SplitDiagramCanvas(
                            preview = preview,
                            source = source,
                            plan = plan,
                            mode = split.options.mode,
                            selectedDividerIndex = split.selectedDividerIndex,
                            description = diagramDescription,
                            modifier = Modifier.fillMaxSize().testTag("split-fullscreen-canvas"),
                            onSelectDivider = onSelectDivider,
                            onMoveDivider = onMoveDivider,
                        )
                    }
                    VerticalDivider()
                    Column(
                        modifier = Modifier
                            .width(96.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = onExit,
                            modifier = Modifier.testTag("split-fullscreen-exit"),
                        ) {
                            Icon(SplitIcons.FullscreenExit, stringResource(R.string.split_fullscreen_exit))
                        }
                        FilterChip(
                            selected = split.options.mode == SplitMode.EQUAL,
                            onClick = { onMode(SplitMode.EQUAL) },
                            label = { Text(stringResource(R.string.split_mode_equal_short), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("split-fullscreen-mode-equal"),
                        )
                        FilterChip(
                            selected = split.options.mode == SplitMode.FREE,
                            onClick = { onMode(SplitMode.FREE) },
                            label = { Text(stringResource(R.string.split_mode_free_short), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("split-fullscreen-mode-free"),
                        )
                        if (split.options.mode == SplitMode.FREE) {
                            IconButton(
                                onClick = onResetDividers,
                                enabled = split.hasCustomDividers,
                                modifier = Modifier.testTag("split-fullscreen-reset"),
                            ) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.split_reset_equal))
                            }
                        }
                        IconButton(
                            onClick = onOpenPreview,
                            enabled = split.canSplit,
                            modifier = Modifier.testTag("split-fullscreen-preview"),
                        ) {
                            Icon(SplitIcons.Preview, stringResource(R.string.split_preview))
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.split_parts_preset, plan?.parts ?: split.options.parts),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(
                            onClick = onExit,
                            modifier = Modifier.testTag("split-fullscreen-exit"),
                        ) {
                            Icon(SplitIcons.FullscreenExit, stringResource(R.string.split_fullscreen_exit))
                        }
                        FilterChip(
                            selected = split.options.mode == SplitMode.EQUAL,
                            onClick = { onMode(SplitMode.EQUAL) },
                            label = { Text(stringResource(R.string.split_mode_equal_short), maxLines = 1) },
                            modifier = Modifier.testTag("split-fullscreen-mode-equal"),
                        )
                        FilterChip(
                            selected = split.options.mode == SplitMode.FREE,
                            onClick = { onMode(SplitMode.FREE) },
                            label = { Text(stringResource(R.string.split_mode_free_short), maxLines = 1) },
                            modifier = Modifier.testTag("split-fullscreen-mode-free"),
                        )
                        Spacer(Modifier.weight(1f))
                        if (split.options.mode == SplitMode.FREE) {
                            IconButton(
                                onClick = onResetDividers,
                                enabled = split.hasCustomDividers,
                                modifier = Modifier.testTag("split-fullscreen-reset"),
                            ) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.split_reset_equal))
                            }
                        }
                        IconButton(
                            onClick = onOpenPreview,
                            enabled = split.canSplit,
                            modifier = Modifier.testTag("split-fullscreen-preview"),
                        ) {
                            Icon(SplitIcons.Preview, stringResource(R.string.split_preview))
                        }
                    }
                    HorizontalDivider()
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        SplitDiagramCanvas(
                            preview = preview,
                            source = source,
                            plan = plan,
                            mode = split.options.mode,
                            selectedDividerIndex = split.selectedDividerIndex,
                            description = diagramDescription,
                            modifier = Modifier.fillMaxSize().testTag("split-fullscreen-canvas"),
                            onSelectDivider = onSelectDivider,
                            onMoveDivider = onMoveDivider,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageSplitPreviewScreen(
    split: ImageSplitState,
    isBusy: Boolean,
    onBack: () -> Unit,
    onDividerWidth: (Int) -> Unit,
    onSpacing: (Int) -> Unit,
    onBackground: (Int) -> Unit,
    onProcess: () -> Unit,
) {
    val preview = split.preview ?: return
    val plan = split.plan ?: return
    val directionLabel = stringResource(
        if (plan.direction == SplitDirection.HORIZONTAL) R.string.main_horizontal else R.string.main_vertical,
    )
    val previewDescription = if (plan.dividerWidthPx > 0) {
        stringResource(
            R.string.split_preview_canvas_description_with_width,
            plan.parts,
            directionLabel,
            plan.dividerWidthPx,
            split.options.previewSpacingPx,
        )
    } else {
        stringResource(
            R.string.split_preview_canvas_description,
            plan.parts,
            directionLabel,
            split.options.previewSpacingPx,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("split-preview-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.main_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).height(50.dp).testTag("split-preview-return-edit"),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.split_preview_back_to_edit), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = onProcess,
                        enabled = split.canSplit && !isBusy,
                        modifier = Modifier.weight(1f).height(50.dp).testTag("split-preview-start"),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.split_start), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    ) { padding ->
        val canvas: @Composable (Modifier) -> Unit = { modifier ->
            SplitPreviewCanvas(
                preview = preview,
                plan = plan,
                spacingPx = split.options.previewSpacingPx,
                backgroundColor = split.options.previewBackgroundColor,
                description = previewDescription,
                modifier = modifier.testTag("split-preview-canvas"),
            )
        }
        val controls: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier = modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag("split-preview-controls"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    if (plan.dividerWidthPx > 0) {
                        stringResource(
                            R.string.split_preview_summary_with_width,
                            plan.parts,
                            directionLabel,
                            plan.dividerWidthPx,
                            split.options.previewSpacingPx,
                        )
                    } else {
                        stringResource(
                            R.string.split_preview_summary,
                            plan.parts,
                            directionLabel,
                            split.options.previewSpacingPx,
                        )
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("split-preview-summary"),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.split_divider_width), fontWeight = FontWeight.Medium)
                    SplitDividerWidthControl(
                        dividerWidthPx = split.options.dividerWidthPx,
                        maxDividerWidthPx = split.effectiveMaxDividerWidthPx,
                        enabled = split.canSplit && !isBusy,
                        tagPrefix = "split-preview-divider-width",
                        onDividerWidth = onDividerWidth,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.split_preview_spacing), fontWeight = FontWeight.Medium)
                    SplitPreviewSpacingControl(
                        spacingPx = split.options.previewSpacingPx,
                        enabled = !isBusy,
                        onSpacing = onSpacing,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.split_preview_background), fontWeight = FontWeight.Medium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PreviewBackgroundSwatches.forEach { swatch ->
                            val isSelected = split.options.previewBackgroundColor == swatch.argb
                            val colorName = stringResource(swatch.nameRes)
                            Surface(
                                onClick = { onBackground(swatch.argb) },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .size(if (isSelected) 44.dp else 40.dp)
                                    .semantics {
                                        contentDescription = colorName
                                        selected = isSelected
                                    }
                                    .testTag("split-preview-bg-${swatch.tag}"),
                                shape = CircleShape,
                                color = Color(swatch.argb),
                                border = BorderStroke(
                                    if (isSelected) 3.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                            ) {}
                        }
                    }
                }
                Text(
                    stringResource(R.string.split_preview_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                Row(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    controls(Modifier.width(360.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(0.55f).fillMaxWidth())
                    HorizontalDivider()
                    controls(Modifier.weight(0.45f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SplitPreviewSpacingControl(
    spacingPx: Int,
    enabled: Boolean,
    onSpacing: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(spacingPx.toString()) }
    LaunchedEffect(spacingPx) {
        val currentTyped = text.toIntOrNull() ?: 0
        if (spacingPx != currentTyped) {
            text = spacingPx.toString()
        }
    }
    val decreaseDescription = stringResource(R.string.split_preview_spacing_decrease)
    val increaseDescription = stringResource(R.string.split_preview_spacing_increase)
    val inputDescription = stringResource(R.string.split_preview_spacing)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val next = (spacingPx - 1).coerceIn(SplitOptions.MIN_PREVIEW_SPACING_PX, SplitOptions.MAX_PREVIEW_SPACING_PX)
                    text = next.toString()
                    onSpacing(next)
                },
                enabled = enabled && spacingPx > SplitOptions.MIN_PREVIEW_SPACING_PX,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = decreaseDescription }
                    .testTag("split-preview-spacing-decrease"),
            ) {
                Text("-", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(4)
                    if (digits.isEmpty()) {
                        text = ""
                        onSpacing(0)
                    } else {
                        val clamped = digits.toInt().coerceIn(SplitOptions.MIN_PREVIEW_SPACING_PX, SplitOptions.MAX_PREVIEW_SPACING_PX)
                        text = clamped.toString()
                        onSpacing(clamped)
                    }
                },
                enabled = enabled,
                singleLine = true,
                suffix = { Text(stringResource(R.string.main_merge_spacing_unit)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (text.isEmpty()) text = "0" }),
                modifier = Modifier
                    .width(128.dp)
                    .onFocusChanged { focus -> if (!focus.isFocused && text.isEmpty()) text = "0" }
                    .semantics { contentDescription = inputDescription }
                    .testTag("split-preview-spacing-input"),
            )
            OutlinedButton(
                onClick = {
                    val next = (spacingPx + 1).coerceIn(SplitOptions.MIN_PREVIEW_SPACING_PX, SplitOptions.MAX_PREVIEW_SPACING_PX)
                    text = next.toString()
                    onSpacing(next)
                },
                enabled = enabled && spacingPx < SplitOptions.MAX_PREVIEW_SPACING_PX,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = increaseDescription }
                    .testTag("split-preview-spacing-increase"),
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplitPreviewSpacingPresets.forEach { preset ->
                FilterChip(
                    selected = spacingPx == preset,
                    onClick = {
                        text = preset.toString()
                        onSpacing(preset)
                    },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.main_pixel_width, preset)) },
                    modifier = Modifier.testTag("split-preview-spacing-preset-$preset"),
                )
            }
        }
    }
}
