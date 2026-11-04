package com.poppinzhang.natsuko.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
import com.poppinzhang.natsuko.model.AdjustmentEdits
import com.poppinzhang.natsuko.model.AdjustmentSource
import com.poppinzhang.natsuko.model.PixelRect
import com.poppinzhang.natsuko.processing.AdjustmentGeometry
import com.poppinzhang.natsuko.processing.AffineTransform
import com.poppinzhang.natsuko.processing.CropBox
import com.poppinzhang.natsuko.processing.CropHandle
import com.poppinzhang.natsuko.processing.ResolvedAdjustment
import com.poppinzhang.natsuko.processing.toCropBox
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

internal enum class AdjustmentCanvasMode { CROP, STRAIGHTEN, RESULT }

private const val MAX_VIEW_ZOOM = 8f

/** Maps rotated-frame pixels to screen pixels: one uniform scale, so viewing never distorts. */
private class CanvasLayout(val content: CropBox, val fit: Float, val zoom: Float, val centerX: Float, val centerY: Float) {
    val scale: Float get() = fit * zoom

    val view: AffineTransform
        get() = AffineTransform.translate(-content.centerX, -content.centerY)
            .then(AffineTransform.scale(scale.toDouble(), scale.toDouble()))
            .then(AffineTransform.translate(centerX.toDouble(), centerY.toDouble()))

    fun rect(box: CropBox) = Rect(x(box.left), y(box.top), x(box.right), y(box.bottom))
    private fun x(value: Double) = ((value - content.centerX) * scale + centerX).toFloat()
    private fun y(value: Double) = ((value - content.centerY) * scale + centerY).toFloat()
}

private class CanvasInputs(
    val layout: CanvasLayout,
    val crop: CropBox,
    val resolved: ResolvedAdjustment,
    val ratio: Double?,
    val onCropChange: (PixelRect, Boolean) -> Unit,
    val onCropCancel: () -> Unit,
)

/**
 * Draws the display-sized preview through the same geometry as the export. Grid lines, handles,
 * dimming, and the transparency pattern are view-only; viewing zoom never changes the crop.
 */
@Composable
internal fun AdjustmentCanvas(
    preview: Bitmap,
    source: AdjustmentSource,
    edits: AdjustmentEdits,
    resolved: ResolvedAdjustment,
    mode: AdjustmentCanvasMode,
    description: String,
    modifier: Modifier = Modifier,
    background: Int? = null,
    interactive: Boolean = true,
    onCropChange: (PixelRect, Boolean) -> Unit = { _, _ -> },
    onCropCancel: () -> Unit = {},
) {
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val resetKey = listOf(mode, resolved.frameWidth, resolved.frameHeight, if (mode == AdjustmentCanvasMode.RESULT) resolved.crop else null)
    var zoom by remember(resetKey) { mutableFloatStateOf(1f) }
    var pan by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var dragBox by remember(mode) { mutableStateOf<CropBox?>(null) }
    val frame = CropBox(0.0, 0.0, resolved.frameWidth.toDouble(), resolved.frameHeight.toDouble())
    val content = if (mode == AdjustmentCanvasMode.RESULT) resolved.crop.toCropBox() else frame
    val margin = with(density) { (if (mode == AdjustmentCanvasMode.CROP) 28.dp else 12.dp).toPx() }
    val fit = if (viewport.width == 0 || viewport.height == 0) 0f else minOf(
        (viewport.width - 2 * margin) / content.width.toFloat(),
        (viewport.height - 2 * margin) / content.height.toFloat(),
    ).coerceAtLeast(1e-6f)
    val layout = CanvasLayout(content, fit, zoom, viewport.width / 2f + pan.x, viewport.height / 2f + pan.y)
    val committedCrop = resolved.crop.toCropBox()
    val cropBox = dragBox ?: committedCrop
    val inputs by rememberUpdatedState(
        CanvasInputs(layout, committedCrop, resolved, AdjustmentGeometry.aspectRatio(edits, source.width, source.height), onCropChange, onCropCancel),
    )
    val handleRadius = with(density) { 28.dp.toPx() }
    val minimumCrop = with(density) { 32.dp.toPx() }

    fun clampPan(value: Offset, zoomValue: Float): Offset {
        val current = inputs.layout
        val maxX = maxOf(0f, (current.content.width.toFloat() * current.fit * zoomValue - viewport.width) / 2f + margin)
        val maxY = maxOf(0f, (current.content.height.toFloat() * current.fit * zoomValue - viewport.height) / 2f + margin)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    // Restart with the view state: a reset creates new zoom and pan holders.
    val gestures = if (!interactive) Modifier else Modifier
        .pointerInput(resetKey) {
            detectTapGestures(onDoubleTap = {
                zoom = 1f
                pan = Offset.Zero
            })
        }
        .pointerInput(resetKey) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = inputs
                val handle = if (mode == AdjustmentCanvasMode.CROP) hitHandle(down.position, start.layout.rect(start.crop), handleRadius) else null
                var dragging = false
                var transformed = false
                var travelled = Offset.Zero
                var lastBox: CropBox? = null
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1 || transformed) {
                            // A second finger turns the gesture into viewing zoom and cancels any crop drag.
                            if (dragging) {
                                dragging = false
                                lastBox = null
                                dragBox = null
                                inputs.onCropCancel()
                            }
                            transformed = true
                            if (pressed.size > 1) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_VIEW_ZOOM)
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
                        if (handle != null) {
                            if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                val current = inputs
                                val scale = current.layout.scale.toDouble()
                                val box = AdjustmentGeometry.dragCrop(
                                    start.crop,
                                    handle,
                                    travelled.x / scale,
                                    travelled.y / scale,
                                    current.resolved.frameWidth,
                                    current.resolved.frameHeight,
                                    current.resolved.straightenTenths,
                                    current.ratio,
                                    minimumCrop / scale,
                                )
                                lastBox = box
                                dragBox = box
                                current.onCropChange(snap(box, current.resolved), false)
                                change.consume()
                            }
                        } else if (zoom > 1f && delta != Offset.Zero) {
                            pan = clampPan(pan + delta, zoom)
                            change.consume()
                        }
                    }
                    lastBox?.let { inputs.onCropChange(snap(it, inputs.resolved), true) }
                } catch (cancelled: CancellationException) {
                    if (lastBox != null) inputs.onCropCancel()
                    throw cancelled
                } finally {
                    dragBox = null
                }
            }
        }

    val checker = rememberCheckerBrush()
    val bitmapPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val matrix = remember { android.graphics.Matrix() }
    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .then(gestures)
            .semantics { contentDescription = description },
    ) {
        if (fit == 0f) return@Canvas
        val toScreen = AdjustmentGeometry.frameTransform(
            source.width, source.height, resolved.quarterTurns, resolved.mirrored, resolved.straightenTenths,
        ).then(layout.view)
        val visible = layout.rect(content)
        clipRect(visible.left, visible.top, visible.right, visible.bottom) {
            val picture = Path().apply {
                listOf(0.0 to 0.0, source.width.toDouble() to 0.0, source.width.toDouble() to source.height.toDouble(), 0.0 to source.height.toDouble())
                    .forEachIndexed { index, (x, y) ->
                        val screenX = toScreen.mapX(x, y).toFloat()
                        val screenY = toScreen.mapY(x, y).toFloat()
                        if (index == 0) moveTo(screenX, screenY) else lineTo(screenX, screenY)
                    }
                close()
            }
            when {
                background != null -> drawPath(picture, Color(background))
                source.hasTransparency -> clipPath(picture) { drawRect(checker, visible.topLeft, visible.size) }
            }
            matrix.setValues(
                AffineTransform.scale(source.width.toDouble() / preview.width, source.height.toDouble() / preview.height)
                    .then(toScreen)
                    .matrixValues(),
            )
            drawIntoCanvas { it.nativeCanvas.drawBitmap(preview, matrix, bitmapPaint) }
        }
        if (mode != AdjustmentCanvasMode.RESULT) {
            val crop = layout.rect(cropBox)
            drawPath(
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(visible)
                    addRect(crop)
                },
                Color.Black.copy(alpha = 0.55f),
            )
            val line = 1.dp.toPx()
            if (mode == AdjustmentCanvasMode.STRAIGHTEN) {
                val spacing = 36.dp.toPx()
                drawGrid(crop, (crop.width / spacing).roundToInt().coerceIn(3, 16), (crop.height / spacing).roundToInt().coerceIn(3, 16), line)
            } else if (dragBox != null) {
                drawGrid(crop, 3, 3, line)
            }
            drawRect(Color.White, crop.topLeft, crop.size, style = Stroke(1.5.dp.toPx()))
            if (mode == AdjustmentCanvasMode.CROP) drawHandles(crop, 20.dp.toPx(), 3.dp.toPx())
        }
    }
}

private fun snap(box: CropBox, resolved: ResolvedAdjustment): PixelRect =
    AdjustmentGeometry.snapCrop(box, resolved.frameWidth, resolved.frameHeight, resolved.straightenTenths)

/** Corners take priority over edges; touching inside the crop moves it. */
private fun hitHandle(position: Offset, crop: Rect, radius: Float): CropHandle? {
    val corners = listOf(
        CropHandle.TOP_LEFT to crop.topLeft,
        CropHandle.TOP_RIGHT to crop.topRight,
        CropHandle.BOTTOM_LEFT to crop.bottomLeft,
        CropHandle.BOTTOM_RIGHT to crop.bottomRight,
    )
    corners.minBy { (it.second - position).getDistance() }
        .takeIf { (it.second - position).getDistance() <= radius }
        ?.let { return it.first }
    val alongX = position.x in crop.left..crop.right
    val alongY = position.y in crop.top..crop.bottom
    listOfNotNull(
        (CropHandle.LEFT to abs(position.x - crop.left)).takeIf { alongY },
        (CropHandle.RIGHT to abs(position.x - crop.right)).takeIf { alongY },
        (CropHandle.TOP to abs(position.y - crop.top)).takeIf { alongX },
        (CropHandle.BOTTOM to abs(position.y - crop.bottom)).takeIf { alongX },
    ).minByOrNull { it.second }?.takeIf { it.second <= radius }?.let { return it.first }
    return if (crop.contains(position)) CropHandle.MOVE else null
}

private fun DrawScope.drawGrid(area: Rect, columns: Int, rows: Int, width: Float) {
    val color = Color.White.copy(alpha = 0.5f)
    for (index in 1 until columns) {
        val x = area.left + area.width * index / columns
        drawLine(color, Offset(x, area.top), Offset(x, area.bottom), width)
    }
    for (index in 1 until rows) {
        val y = area.top + area.height * index / rows
        drawLine(color, Offset(area.left, y), Offset(area.right, y), width)
    }
}

private fun DrawScope.drawHandles(crop: Rect, length: Float, thickness: Float) {
    val size = minOf(length, crop.width / 3, crop.height / 3)
    val corners = listOf(
        crop.topLeft to Offset(1f, 1f),
        crop.topRight to Offset(-1f, 1f),
        crop.bottomLeft to Offset(1f, -1f),
        crop.bottomRight to Offset(-1f, -1f),
    )
    val center = crop.center
    val edges = listOf(
        Offset(center.x - size / 2, crop.top) to Offset(center.x + size / 2, crop.top),
        Offset(center.x - size / 2, crop.bottom) to Offset(center.x + size / 2, crop.bottom),
        Offset(crop.left, center.y - size / 2) to Offset(crop.left, center.y + size / 2),
        Offset(crop.right, center.y - size / 2) to Offset(crop.right, center.y + size / 2),
    )
    // A dark outline keeps the white handles visible on bright pictures.
    for ((color, width) in listOf(Color.Black.copy(alpha = 0.35f) to thickness + 2f, Color.White to thickness)) {
        corners.forEach { (corner, direction) ->
            drawLine(color, corner, corner + Offset(direction.x * size, 0f), width, StrokeCap.Square)
            drawLine(color, corner, corner + Offset(0f, direction.y * size), width, StrokeCap.Square)
        }
        edges.forEach { (from, to) -> drawLine(color, from, to, width, StrokeCap.Round) }
    }
}

@Composable
private fun rememberCheckerBrush(): ShaderBrush {
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
