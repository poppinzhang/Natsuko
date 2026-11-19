package com.poppinzhang.natsuko.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.poppinzhang.natsuko.model.SplitDirection
import com.poppinzhang.natsuko.model.SplitMode
import com.poppinzhang.natsuko.model.SplitSource
import com.poppinzhang.natsuko.processing.ImageSplitGeometry
import com.poppinzhang.natsuko.processing.SplitPlan
import kotlin.math.roundToInt

private const val MAX_SPLIT_VIEW_ZOOM = 8f

private class DiagramViewState(
    val imageRect: Rect,
    val plan: SplitPlan?,
    val mode: SplitMode,
    val onSelectDivider: (Int?) -> Unit,
    val onMoveDivider: (Int, Double) -> Unit,
)

/**
 * Interactive split diagram showing the original image with live divider lines.
 * Supports dragging individual lines in [SplitMode.FREE], plus pinch-to-zoom, pan,
 * and double-tap zoom reset in both normal and fullscreen screens.
 */
@Composable
internal fun SplitDiagramCanvas(
    preview: Bitmap,
    source: SplitSource,
    plan: SplitPlan?,
    mode: SplitMode,
    selectedDividerIndex: Int?,
    description: String,
    modifier: Modifier = Modifier,
    onSelectDivider: (Int?) -> Unit = {},
    onMoveDivider: (Int, Double) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val resetKey = listOf(source.width, source.height)
    var zoom by remember(resetKey) { mutableFloatStateOf(1f) }
    var pan by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var draggingDivider by remember { mutableStateOf<Int?>(null) }

    val margin = with(density) { 16.dp.toPx() }
    val hitRadius = with(density) { 26.dp.toPx() }
    val fit = if (viewport.width == 0 || viewport.height == 0) {
        0f
    } else {
        minOf(
            (viewport.width - margin * 2f) / source.width.toFloat(),
            (viewport.height - margin * 2f) / source.height.toFloat(),
        ).coerceAtLeast(1e-6f)
    }
    val drawWidth = source.width * fit * zoom
    val drawHeight = source.height * fit * zoom
    val imageRect = Rect(
        left = (viewport.width - drawWidth) / 2f + pan.x,
        top = (viewport.height - drawHeight) / 2f + pan.y,
        right = (viewport.width + drawWidth) / 2f + pan.x,
        bottom = (viewport.height + drawHeight) / 2f + pan.y,
    )
    val latest by rememberUpdatedState(
        DiagramViewState(imageRect, plan, mode, onSelectDivider, onMoveDivider),
    )

    fun clampPan(value: Offset, zoomValue: Float): Offset {
        if (viewport.width == 0 || viewport.height == 0 || fit == 0f) return Offset.Zero
        val maxX = maxOf(0f, (source.width * fit * zoomValue - viewport.width) / 2f + margin)
        val maxY = maxOf(0f, (source.height * fit * zoomValue - viewport.height) / 2f + margin)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    val gestures = Modifier
        .pointerInput(resetKey) {
            detectTapGestures(
                onDoubleTap = {
                    zoom = 1f
                    pan = Offset.Zero
                },
                onTap = { tap ->
                    val current = latest
                    val currentPlan = current.plan
                    if (current.mode == SplitMode.FREE && currentPlan != null) {
                        val hit = hitDivider(tap, current.imageRect, currentPlan, hitRadius)
                        current.onSelectDivider(hit)
                    }
                },
            )
        }
        .pointerInput(resetKey) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val initial = latest
                val hit = if (initial.mode == SplitMode.FREE && initial.plan != null) {
                    hitDivider(down.position, initial.imageRect, initial.plan, hitRadius)
                } else {
                    null
                }
                var dragging = false
                var transformed = false
                var travelled = Offset.Zero
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1 || transformed) {
                            if (dragging) {
                                dragging = false
                                draggingDivider = null
                            }
                            transformed = true
                            if (pressed.size > 1) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_SPLIT_VIEW_ZOOM)
                                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                val anchored = centroid - center - (centroid - center - pan) * (newZoom / zoom)
                                pan = clampPan(anchored + event.calculatePan(), newZoom)
                                zoom = newZoom
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            continue
                        }
                        val change = pressed.first()
                        val delta = change.positionChange()
                        travelled += delta
                        if (hit != null) {
                            if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop / 2f) {
                                dragging = true
                                draggingDivider = hit
                                latest.onSelectDivider(hit)
                            }
                            if (dragging) {
                                val current = latest
                                val currentPlan = current.plan
                                if (currentPlan != null) {
                                    val fraction = if (currentPlan.direction == SplitDirection.HORIZONTAL) {
                                        ((change.position.x - current.imageRect.left) / current.imageRect.width.coerceAtLeast(1f)).toDouble()
                                    } else {
                                        ((change.position.y - current.imageRect.top) / current.imageRect.height.coerceAtLeast(1f)).toDouble()
                                    }
                                    current.onMoveDivider(hit, fraction)
                                }
                                change.consume()
                            }
                        } else if (zoom > 1f && delta != Offset.Zero) {
                            pan = clampPan(pan + delta, zoom)
                            change.consume()
                        }
                    }
                } finally {
                    draggingDivider = null
                }
            }
        }

    val checker = rememberSplitCheckerBrush()
    val bitmapPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val dstRect = remember { RectF() }
    val badgeTextPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = with(density) { 12.dp.toPx() }
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    val readoutTextPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = with(density) { 13.dp.toPx() }
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .then(gestures)
            .semantics { contentDescription = description },
    ) {
        if (fit == 0f || imageRect.width <= 0f || imageRect.height <= 0f) return@Canvas

        if (source.hasAlpha) {
            drawRect(checker, imageRect.topLeft, imageRect.size)
        }
        dstRect.set(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(preview, null, dstRect, bitmapPaint)
        }

        // Outer frame border keeps bright images clearly bounded against light backgrounds.
        drawRect(
            color = Color.Black.copy(alpha = 0.28f),
            topLeft = imageRect.topLeft,
            size = imageRect.size,
            style = Stroke(width = 1.dp.toPx()),
        )

        if (plan != null) {
            drawSliceBadges(imageRect, plan, badgeTextPaint)
            drawSplitDividers(
                imageRect = imageRect,
                plan = plan,
                freeMode = mode == SplitMode.FREE,
                activeIndex = draggingDivider ?: selectedDividerIndex,
                accentColor = primaryColor,
            )
            val active = draggingDivider
            if (active != null && active in plan.dividers.indices) {
                drawDragReadout(plan, active, readoutTextPaint)
            }
        }
    }
}

/**
 * Displays all sliced parts arranged along the split direction separated by [spacingPx]
 * over [backgroundColor], using [ImageSplitGeometry.previewLayout].
 */
@Composable
internal fun SplitPreviewCanvas(
    preview: Bitmap,
    plan: SplitPlan,
    spacingPx: Int,
    backgroundColor: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val resetKey = listOf(plan.sourceWidth, plan.sourceHeight, plan.direction, plan.parts)
    var zoom by remember(resetKey) { mutableFloatStateOf(1f) }
    var pan by remember(resetKey) { mutableStateOf(Offset.Zero) }
    val paddingPx = with(density) { 16.dp.toPx() }

    val layout = if (viewport.width > 0 && viewport.height > 0) {
        ImageSplitGeometry.previewLayout(
            plan = plan,
            spacingPx = spacingPx,
            viewportWidth = viewport.width.toFloat(),
            viewportHeight = viewport.height.toFloat(),
            paddingPx = paddingPx,
        )
    } else {
        null
    }
    val latestLayout by rememberUpdatedState(layout)

    fun clampPan(value: Offset, zoomValue: Float): Offset {
        val current = latestLayout ?: return Offset.Zero
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val maxX = maxOf(0f, (current.canvasWidth * zoomValue - viewport.width) / 2f + paddingPx)
        val maxY = maxOf(0f, (current.canvasHeight * zoomValue - viewport.height) / 2f + paddingPx)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    val gestures = Modifier
        .pointerInput(resetKey) {
            detectTapGestures(
                onDoubleTap = {
                    zoom = 1f
                    pan = Offset.Zero
                },
            )
        }
        .pointerInput(resetKey) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size > 1) {
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_SPLIT_VIEW_ZOOM)
                        val center = Offset(viewport.width / 2f, viewport.height / 2f)
                        val anchored = centroid - center - (centroid - center - pan) * (newZoom / zoom)
                        pan = clampPan(anchored + event.calculatePan(), newZoom)
                        zoom = newZoom
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else if (zoom > 1f) {
                        val change = pressed.first()
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            pan = clampPan(pan + delta, zoom)
                            change.consume()
                        }
                    }
                }
            }
        }

    val bitmapPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val srcRect = remember { android.graphics.Rect() }
    val dstRect = remember { RectF() }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .then(gestures)
            .semantics { contentDescription = description },
    ) {
        drawRect(Color(backgroundColor))
        val currentLayout = layout ?: return@Canvas
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val scaleX = preview.width.toDouble() / plan.sourceWidth
        val scaleY = preview.height.toDouble() / plan.sourceHeight

        drawIntoCanvas { canvas ->
            for (item in currentLayout.slices) {
                val s = item.slice
                val srcLeft = (s.left * scaleX).roundToInt().coerceIn(0, preview.width - 1)
                val srcTop = (s.top * scaleY).roundToInt().coerceIn(0, preview.height - 1)
                val srcRight = (s.right * scaleX).roundToInt().coerceIn(srcLeft + 1, preview.width)
                val srcBottom = (s.bottom * scaleY).roundToInt().coerceIn(srcTop + 1, preview.height)
                srcRect.set(srcLeft, srcTop, srcRight, srcBottom)

                val dstLeft = (item.left - centerX) * zoom + centerX + pan.x
                val dstTop = (item.top - centerY) * zoom + centerY + pan.y
                val dstRight = (item.right - centerX) * zoom + centerX + pan.x
                val dstBottom = (item.bottom - centerY) * zoom + centerY + pan.y
                dstRect.set(dstLeft, dstTop, dstRight, dstBottom)

                canvas.nativeCanvas.drawBitmap(preview, srcRect, dstRect, bitmapPaint)
            }
        }
    }
}

private fun hitDivider(
    pointer: Offset,
    imageRect: Rect,
    plan: SplitPlan,
    hitRadius: Float,
): Int? {
    val expanded = Rect(
        left = imageRect.left - hitRadius,
        top = imageRect.top - hitRadius,
        right = imageRect.right + hitRadius,
        bottom = imageRect.bottom + hitRadius,
    )
    if (!expanded.contains(pointer)) return null
    return if (plan.direction == SplitDirection.HORIZONTAL) {
        ImageSplitGeometry.hitTestDivider(pointer.x, imageRect.left, imageRect.width, plan, hitRadius)
    } else {
        ImageSplitGeometry.hitTestDivider(pointer.y, imageRect.top, imageRect.height, plan, hitRadius)
    }
}

private fun DrawScope.drawSliceBadges(
    imageRect: Rect,
    plan: SplitPlan,
    textPaint: android.graphics.Paint,
) {
    val minBadgeDim = 24.dp.toPx()
    val badgeRadius = 11.dp.toPx()
    val inset = 16.dp.toPx()
    val metrics = textPaint.fontMetrics
    val textBaselineOffset = -(metrics.ascent + metrics.descent) / 2f

    for (slice in plan.slices) {
        val left = imageRect.left + imageRect.width * (slice.left.toFloat() / plan.sourceWidth)
        val right = imageRect.left + imageRect.width * (slice.right.toFloat() / plan.sourceWidth)
        val top = imageRect.top + imageRect.height * (slice.top.toFloat() / plan.sourceHeight)
        val bottom = imageRect.top + imageRect.height * (slice.bottom.toFloat() / plan.sourceHeight)
        val sliceWidth = right - left
        val sliceHeight = bottom - top
        if (sliceWidth < minBadgeDim || sliceHeight < minBadgeDim) continue

        val cx = if (sliceWidth >= inset * 2.2f) left + inset else (left + right) / 2f
        val cy = if (sliceHeight >= inset * 2.2f) top + inset else (top + bottom) / 2f
        drawCircle(
            color = Color.Black.copy(alpha = 0.56f),
            radius = badgeRadius,
            center = Offset(cx, cy),
        )
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText((slice.index + 1).toString(), cx, cy + textBaselineOffset, textPaint)
        }
    }
}

private fun DrawScope.drawSplitDividers(
    imageRect: Rect,
    plan: SplitPlan,
    freeMode: Boolean,
    activeIndex: Int?,
    accentColor: Color,
) {
    val axis = plan.axisLength.toFloat()
    val outlineStroke = 3.5.dp.toPx()
    val innerStroke = 1.6.dp.toPx()
    val activeOutlineStroke = 4.8.dp.toPx()
    val activeInnerStroke = 2.6.dp.toPx()
    val edgeHaloStroke = 2.6.dp.toPx()
    val edgeInnerStroke = 1.2.dp.toPx()
    val activeEdgeInnerStroke = 1.8.dp.toPx()
    val minVisibleBand = 3.dp.toPx()
    val stagger = plan.dividers.size > 6

    plan.dividers.forEachIndexed { index, strip ->
        val isActive = freeMode && activeIndex == index
        val outerWidth = if (isActive) activeOutlineStroke else outlineStroke
        val innerWidth = if (isActive) activeInnerStroke else innerStroke
        val lineColor = if (isActive) accentColor else Color.White

        if (plan.direction == SplitDirection.HORIZONTAL) {
            val bandLeft = imageRect.left + imageRect.width * (strip.start / axis)
            val bandRight = imageRect.left + imageRect.width * (strip.end / axis)
            val bandWidth = bandRight - bandLeft
            val centerX = (bandLeft + bandRight) / 2f

            if (strip.width > 0 && bandWidth >= minVisibleBand) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.54f),
                    topLeft = Offset(bandLeft, imageRect.top),
                    size = Size(bandWidth, imageRect.height),
                )
                if (isActive) {
                    drawRect(
                        color = accentColor.copy(alpha = 0.28f),
                        topLeft = Offset(bandLeft, imageRect.top),
                        size = Size(bandWidth, imageRect.height),
                    )
                }
                val edgeStroke = if (isActive) activeEdgeInnerStroke else edgeInnerStroke
                for (edgeX in listOf(bandLeft, bandRight)) {
                    drawLine(Color.Black.copy(alpha = 0.45f), Offset(edgeX, imageRect.top), Offset(edgeX, imageRect.bottom), edgeHaloStroke, StrokeCap.Butt)
                    drawLine(lineColor, Offset(edgeX, imageRect.top), Offset(edgeX, imageRect.bottom), edgeStroke, StrokeCap.Butt)
                }
            } else {
                val effectiveInner = if (strip.width > 0) maxOf(innerWidth, bandWidth) else innerWidth
                val effectiveOuter = maxOf(outerWidth, effectiveInner + 1.8.dp.toPx())
                drawLine(Color.Black.copy(alpha = 0.5f), Offset(centerX, imageRect.top), Offset(centerX, imageRect.bottom), effectiveOuter, StrokeCap.Butt)
                drawLine(lineColor, Offset(centerX, imageRect.top), Offset(centerX, imageRect.bottom), effectiveInner, StrokeCap.Butt)
            }

            if (freeMode) {
                val crossRatio = if (!stagger) 0.5f else if (index % 2 == 0) 0.42f else 0.58f
                val cy = imageRect.top + imageRect.height * crossRatio
                drawHandlePill(
                    center = Offset(centerX, cy),
                    verticalLine = true,
                    active = isActive,
                    accentColor = accentColor,
                    maxLength = (imageRect.height * 0.45f).coerceAtLeast(14.dp.toPx()),
                )
            }
        } else {
            val bandTop = imageRect.top + imageRect.height * (strip.start / axis)
            val bandBottom = imageRect.top + imageRect.height * (strip.end / axis)
            val bandHeight = bandBottom - bandTop
            val centerY = (bandTop + bandBottom) / 2f

            if (strip.width > 0 && bandHeight >= minVisibleBand) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.54f),
                    topLeft = Offset(imageRect.left, bandTop),
                    size = Size(imageRect.width, bandHeight),
                )
                if (isActive) {
                    drawRect(
                        color = accentColor.copy(alpha = 0.28f),
                        topLeft = Offset(imageRect.left, bandTop),
                        size = Size(imageRect.width, bandHeight),
                    )
                }
                val edgeStroke = if (isActive) activeEdgeInnerStroke else edgeInnerStroke
                for (edgeY in listOf(bandTop, bandBottom)) {
                    drawLine(Color.Black.copy(alpha = 0.45f), Offset(imageRect.left, edgeY), Offset(imageRect.right, edgeY), edgeHaloStroke, StrokeCap.Butt)
                    drawLine(lineColor, Offset(imageRect.left, edgeY), Offset(imageRect.right, edgeY), edgeStroke, StrokeCap.Butt)
                }
            } else {
                val effectiveInner = if (strip.width > 0) maxOf(innerWidth, bandHeight) else innerWidth
                val effectiveOuter = maxOf(outerWidth, effectiveInner + 1.8.dp.toPx())
                drawLine(Color.Black.copy(alpha = 0.5f), Offset(imageRect.left, centerY), Offset(imageRect.right, centerY), effectiveOuter, StrokeCap.Butt)
                drawLine(lineColor, Offset(imageRect.left, centerY), Offset(imageRect.right, centerY), effectiveInner, StrokeCap.Butt)
            }

            if (freeMode) {
                val crossRatio = if (!stagger) 0.5f else if (index % 2 == 0) 0.42f else 0.58f
                val cx = imageRect.left + imageRect.width * crossRatio
                drawHandlePill(
                    center = Offset(cx, centerY),
                    verticalLine = false,
                    active = isActive,
                    accentColor = accentColor,
                    maxLength = (imageRect.width * 0.45f).coerceAtLeast(14.dp.toPx()),
                )
            }
        }
    }
}

private fun DrawScope.drawHandlePill(
    center: Offset,
    verticalLine: Boolean,
    active: Boolean,
    accentColor: Color,
    maxLength: Float,
) {
    val thickness = 14.dp.toPx()
    val length = minOf(32.dp.toPx(), maxLength)
    val width = if (verticalLine) thickness else length
    val height = if (verticalLine) length else thickness
    val topLeft = Offset(center.x - width / 2f, center.y - height / 2f)
    val corner = CornerRadius(thickness / 2f, thickness / 2f)
    val fill = if (active) accentColor else Color.White
    val grip = if (active) Color.White else Color(0xFF3C4043)

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.4f),
        topLeft = Offset(topLeft.x - 1.dp.toPx(), topLeft.y - 1.dp.toPx()),
        size = Size(width + 2.dp.toPx(), height + 2.dp.toPx()),
        cornerRadius = CornerRadius(thickness / 2f + 1.dp.toPx(), thickness / 2f + 1.dp.toPx()),
    )
    drawRoundRect(
        color = fill,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = corner,
    )
    val gripSpan = (length * 0.34f).coerceAtLeast(4.dp.toPx())
    val gripOffset = 2.2.dp.toPx()
    val gripStroke = 1.5.dp.toPx()
    if (verticalLine) {
        drawLine(grip, Offset(center.x - gripOffset, center.y - gripSpan / 2f), Offset(center.x - gripOffset, center.y + gripSpan / 2f), gripStroke, StrokeCap.Round)
        drawLine(grip, Offset(center.x + gripOffset, center.y - gripSpan / 2f), Offset(center.x + gripOffset, center.y + gripSpan / 2f), gripStroke, StrokeCap.Round)
    } else {
        drawLine(grip, Offset(center.x - gripSpan / 2f, center.y - gripOffset), Offset(center.x + gripSpan / 2f, center.y - gripOffset), gripStroke, StrokeCap.Round)
        drawLine(grip, Offset(center.x - gripSpan / 2f, center.y + gripOffset), Offset(center.x + gripSpan / 2f, center.y + gripOffset), gripStroke, StrokeCap.Round)
    }
}

private fun DrawScope.drawDragReadout(
    plan: SplitPlan,
    dividerIndex: Int,
    textPaint: android.graphics.Paint,
) {
    val strip = plan.dividers[dividerIndex]
    val before = plan.slices[dividerIndex]
    val after = plan.slices[dividerIndex + 1]
    val beforeLen = if (plan.direction == SplitDirection.HORIZONTAL) before.width else before.height
    val afterLen = if (plan.direction == SplitDirection.HORIZONTAL) after.width else after.height
    val positionText = if (strip.width > 0) "${strip.start}–${strip.end} px" else "${strip.start} px"
    val label = "#${dividerIndex + 1} · $positionText ($beforeLen | $afterLen)"
    val textWidth = textPaint.measureText(label)
    val horizontalPad = 12.dp.toPx()
    val pillWidth = textWidth + horizontalPad * 2f
    val pillHeight = 28.dp.toPx()
    val left = (size.width - pillWidth) / 2f
    val top = 8.dp.toPx()
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.72f),
        topLeft = Offset(left, top),
        size = Size(pillWidth, pillHeight),
        cornerRadius = CornerRadius(pillHeight / 2f, pillHeight / 2f),
    )
    val metrics = textPaint.fontMetrics
    val baseline = top + pillHeight / 2f - (metrics.ascent + metrics.descent) / 2f
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(label, size.width / 2f, baseline, textPaint)
    }
}

@Composable
private fun rememberSplitCheckerBrush(): ShaderBrush {
    val tile = with(LocalDensity.current) { 8.dp.roundToPx() }.coerceAtLeast(1)
    return remember(tile) {
        val bitmap = ImageBitmap(tile * 2, tile * 2)
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val light = Paint().apply { color = Color(0xFFFFFFFF) }
        val dark = Paint().apply { color = Color(0xFFCCCCCC) }
        canvas.drawRect(0f, 0f, tile * 2f, tile * 2f, light)
        canvas.drawRect(0f, 0f, tile.toFloat(), tile.toFloat(), dark)
        canvas.drawRect(tile.toFloat(), tile.toFloat(), tile * 2f, tile * 2f, dark)
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }
}
