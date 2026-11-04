package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.LocalizedFailure
import com.poppinzhang.natsuko.model.LocalizedIOException
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.graphics.createBitmap
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.AdjustmentExportSettings
import com.poppinzhang.natsuko.model.AdjustmentFormat
import com.poppinzhang.natsuko.model.AdjustmentSource
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.OutputArtifact
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A limit the user can resolve by reducing the size in the editor. */
class AdjustmentTooLargeException(override val userMessage: UiText) :
    IllegalStateException(userMessage.toString()), LocalizedFailure

/** Everything that determines the encoded bytes: geometry plus normalized export settings. */
data class AdjustmentExportRequest(
    val geometry: ResolvedAdjustment,
    val settings: AdjustmentExportSettings,
)

class AdjustmentLoad(val source: AdjustmentSource, val preview: Bitmap)

class ImageAdjustmentProcessor(
    private val context: Context,
    private val outputRepository: OutputRepository,
) {
    /** Reads the header and a display-sized preview. Export always decodes the source again. */
    fun load(input: InputItem, checkpoint: () -> Unit): AdjustmentLoad {
        var width = 0
        var height = 0
        var mimeType: String? = null
        var colorSpace: ColorSpace? = null
        val preview = decodeOrFail {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, input.uri)) { decoder, info, _ ->
                requireText(!info.isAnimated) { text(R.string.backend_adjust_animated) }
                width = info.size.width
                height = info.size.height
                requireText(width > 0 && height > 0) { text(R.string.backend_image_size_invalid) }
                mimeType = info.mimeType
                colorSpace = info.colorSpace
                configure(decoder, mutable = false)
                val target = fitInside(width, height, PREVIEW_MAX_EDGE)
                if (target.width != width || target.height != height) decoder.setTargetSize(target.width, target.height)
            }
        }
        try {
            checkpoint()
            val transparent = preview.hasAlpha() && hasTransparentPixel(preview, checkpoint)
            val wideOrHdr = colorSpace?.isSrgb == false || preview.hasGainmap()
            return AdjustmentLoad(
                AdjustmentSource(width, height, mimeType, transparent, preview.hasAlpha(), fileSize(input), wideOrHdr),
                preview,
            )
        } catch (error: Throwable) {
            preview.recycle()
            throw error
        }
    }

    fun openSession() = ImageAdjustmentSession(this, File(context.cacheDir, TEMPORARY_DIRECTORY))

    /** Decodes only the needed source region at the needed resolution and resamples once. */
    internal fun render(
        input: InputItem,
        source: AdjustmentSource,
        geometry: ResolvedAdjustment,
        background: Int?,
        checkpoint: () -> Unit,
        onStage: (UiText) -> Unit,
    ): Bitmap {
        val plan = decodePlan(geometry)
        onStage(text(R.string.backend_adjust_reading))
        val decoded = decodeOrFail {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, input.uri)) { decoder, info, _ ->
                requireText(!info.isAnimated) { text(R.string.backend_adjust_animated) }
                checkText(info.size.width == source.width && info.size.height == source.height) {
                    text(R.string.backend_adjust_source_changed)
                }
                configure(decoder, mutable = geometry.isPixelCopy)
                if (plan.targetWidth != source.width || plan.targetHeight != source.height) {
                    decoder.setTargetSize(plan.targetWidth, plan.targetHeight)
                }
                if (plan.region != Rect(0, 0, plan.targetWidth, plan.targetHeight)) decoder.crop = plan.region
            }
        }
        var result: Bitmap? = null
        try {
            checkText(decoded.width == plan.region.width() && decoded.height == plan.region.height()) {
                text(R.string.backend_decode_image_failed)
            }
            checkpoint()
            onStage(text(R.string.backend_adjust_rendering))
            // Output is plain SDR sRGB; an HDR gain map would otherwise be re-encoded with the base image.
            if (decoded.hasGainmap()) decoded.gainmap = null
            if (geometry.isPixelCopy && decoded.config == Bitmap.Config.ARGB_8888 && decoded.colorSpace?.isSrgb == true) {
                background?.let { Canvas(decoded).drawColor(it, PorterDuff.Mode.DST_OVER) }
                result = decoded
                return decoded
            }
            val output = createBitmap(
                geometry.outputWidth,
                geometry.outputHeight,
                Bitmap.Config.ARGB_8888,
                decoded.hasAlpha() && background == null,
                ColorSpace.get(ColorSpace.Named.SRGB),
            )
            result = output
            background?.let(output::eraseColor)
            // No anti-aliasing: every output pixel center lies inside the picture, so edges stay opaque.
            Canvas(output).drawBitmap(decoded, Matrix().apply { setValues(plan.toOutput.matrixValues()) }, Paint(Paint.FILTER_BITMAP_FLAG))
            checkpoint()
            return output
        } catch (error: Throwable) {
            result?.takeUnless { it === decoded }?.recycle()
            throw error
        } finally {
            if (result !== decoded) decoded.recycle()
        }
    }

    internal fun encode(
        bitmap: Bitmap,
        settings: AdjustmentExportSettings,
        file: File,
        checkpoint: () -> Unit,
        onProgress: (UiText) -> Unit,
    ): Long {
        val (format, quality) = when (settings.format) {
            AdjustmentFormat.PNG -> Bitmap.CompressFormat.PNG to 100
            AdjustmentFormat.JPEG -> Bitmap.CompressFormat.JPEG to settings.quality
            AdjustmentFormat.WEBP -> if (settings.webpLossless) {
                Bitmap.CompressFormat.WEBP_LOSSLESS to WEBP_LOSSLESS_EFFORT
            } else {
                Bitmap.CompressFormat.WEBP_LOSSY to settings.quality
            }
        }
        if (settings.format == AdjustmentFormat.WEBP && maxOf(bitmap.width, bitmap.height) > WEBP_MAX_EDGE) {
            throw AdjustmentTooLargeException(text(R.string.backend_adjust_webp_too_large, WEBP_MAX_EDGE))
        }
        onProgress(text(R.string.backend_adjust_encoding))
        var writeFailure: IOException? = null
        val success = file.outputStream().use { stream ->
            // The platform encoder reports exceptions from this stream only as a failed result.
            val checked = object : FilterOutputStream(stream) {
                private var written = 0L
                private var reported = 0L

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    checkpoint()
                    try {
                        out.write(buffer, offset, length)
                    } catch (error: IOException) {
                        writeFailure = error
                        throw error
                    }
                    written += length
                    if (written - reported >= PROGRESS_BYTES) {
                        reported = written
                        onProgress(text(R.string.backend_adjust_encoding_bytes, written / 1024))
                    }
                }

                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            }
            bitmap.compress(format, quality, checked)
        }
        checkpoint()
        writeFailure?.let { throw storageFailure(it) }
        checkText(success) { text(R.string.backend_write_image_failed) }
        return file.length()
    }

    /** Publishes the already measured trial file without encoding it again. */
    internal fun commit(
        input: InputItem,
        request: AdjustmentExportRequest,
        trial: File,
        bytes: Long,
        checkpoint: () -> Unit,
    ): OutputArtifact {
        val directory = outputRepository.createRunDirectory(Feature.IMAGE_ADJUSTMENT)
        val output = File(directory, "${outputStem(input.displayName)}_adjusted.${request.settings.format.extension}")
        var complete = false
        try {
            if (!trial.renameTo(output)) {
                try {
                    trial.inputStream().use { source ->
                        output.outputStream().use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkpoint()
                                val count = source.read(buffer)
                                if (count < 0) break
                                destination.write(buffer, 0, count)
                            }
                        }
                    }
                } catch (error: IOException) {
                    throw storageFailure(error)
                }
            }
            checkText(output.length() == bytes) { text(R.string.backend_write_image_failed) }
            checkpoint()
            complete = true
            return OutputArtifact(
                output,
                request.settings.format.mimeType,
                UiText.Literal(input.displayName),
                detail = text(
                    R.string.adjust_output_detail,
                    request.geometry.outputWidth,
                    request.geometry.outputHeight,
                    formatLabel(request.settings),
                ),
            )
        } finally {
            if (!complete) {
                output.delete()
                directory.delete()
            }
        }
    }

    private class DecodePlan(val targetWidth: Int, val targetHeight: Int, val region: Rect, val toOutput: AffineTransform)

    /**
     * Peak memory is the decoded region plus the output bitmap. Reduced exports decode at up to
     * twice the output resolution for smooth downsampling; a selected reduction can always bring a
     * large source within the budget, which is never applied automatically.
     */
    private fun decodePlan(geometry: ResolvedAdjustment): DecodePlan {
        val outputPixels = geometry.outputWidth.toLong() * geometry.outputHeight
        if (outputPixels > MAX_BITMAP_PIXELS) throw tooLarge(outputPixels, MAX_BITMAP_PIXELS)
        if (geometry.isPixelCopy) {
            val crop = geometry.crop
            return DecodePlan(
                geometry.sourceWidth,
                geometry.sourceHeight,
                Rect(crop.left, crop.top, crop.right, crop.bottom),
                AffineTransform.IDENTITY,
            )
        }
        val bounds = AdjustmentGeometry.sourceBounds(geometry)
        // One extra pixel keeps bilinear sampling at the region edges identical to a full decode.
        val left = (floor(bounds.left).toInt() - 1).coerceIn(0, geometry.sourceWidth - 1)
        val top = (floor(bounds.top).toInt() - 1).coerceIn(0, geometry.sourceHeight - 1)
        val right = (ceil(bounds.right).toInt() + 1).coerceIn(left + 1, geometry.sourceWidth)
        val bottom = (ceil(bounds.bottom).toInt() + 1).coerceIn(top + 1, geometry.sourceHeight)
        val regionPixels = (right - left).toDouble() * (bottom - top)
        val outputScale = maxOf(
            geometry.outputWidth.toDouble() / geometry.crop.width,
            geometry.outputHeight.toDouble() / geometry.crop.height,
        )
        val budget = minOf(MAX_BITMAP_PIXELS, MAX_PEAK_PIXELS - outputPixels).toDouble()
        var scale = minOf(1.0, outputScale * 2)
        if (regionPixels * scale * scale > budget) scale = sqrt(budget / regionPixels)
        if (scale < outputScale * (1 - 1e-9)) {
            throw tooLarge(outputPixels + (regionPixels * outputScale * outputScale).toLong(), MAX_PEAK_PIXELS)
        }
        val targetWidth = if (scale >= 1.0) geometry.sourceWidth else (geometry.sourceWidth * scale).roundToInt().coerceIn(1, geometry.sourceWidth)
        val targetHeight = if (scale >= 1.0) geometry.sourceHeight else (geometry.sourceHeight * scale).roundToInt().coerceIn(1, geometry.sourceHeight)
        val scaleX = targetWidth.toDouble() / geometry.sourceWidth
        val scaleY = targetHeight.toDouble() / geometry.sourceHeight
        val regionLeft = floor(left * scaleX).toInt().coerceIn(0, targetWidth - 1)
        val regionTop = floor(top * scaleY).toInt().coerceIn(0, targetHeight - 1)
        val region = Rect(
            regionLeft,
            regionTop,
            ceil(right * scaleX).toInt().coerceIn(regionLeft + 1, targetWidth),
            ceil(bottom * scaleY).toInt().coerceIn(regionTop + 1, targetHeight),
        )
        val toOutput = AffineTransform.translate(region.left.toDouble(), region.top.toDouble())
            .then(AffineTransform.scale(1 / scaleX, 1 / scaleY))
            .then(AdjustmentGeometry.outputTransform(geometry))
        return DecodePlan(targetWidth, targetHeight, region, toOutput)
    }

    private fun tooLarge(pixels: Long, limit: Long) = AdjustmentTooLargeException(
        text(R.string.backend_adjust_too_large, pixels / 1_000_000.0, limit / 1_000_000.0),
    )

    private fun configure(decoder: ImageDecoder, mutable: Boolean) {
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // LOW_RAM may choose RGB_565 and band gradients; previews and output use full color depth.
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
        decoder.isMutableRequired = mutable
        // Converts wide-gamut colors instead of reinterpreting them as sRGB. EXIF orientation is
        // applied by ImageDecoder, and incomplete images fail instead of returning partial pixels.
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }

    private inline fun decodeOrFail(decode: () -> Bitmap): Bitmap = try {
        decode()
    } catch (error: Exception) {
        throw when (error) {
            is LocalizedFailure -> error
            is ImageDecoder.DecodeException -> LocalizedIllegalStateException(text(R.string.backend_image_invalid), error)
            is FileNotFoundException, is SecurityException, is IOException ->
                LocalizedIOException(text(R.string.backend_adjust_source_unreadable), error)
            else -> error
        }
    } catch (error: OutOfMemoryError) {
        throw AdjustmentTooLargeException(text(R.string.backend_adjust_out_of_memory))
    }

    private fun hasTransparentPixel(bitmap: Bitmap, checkpoint: () -> Unit): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            if (y % 64 == 0) checkpoint()
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it ushr 24 != 255 }) return true
        }
        return false
    }

    private fun fileSize(input: InputItem): Long? = runCatching {
        context.contentResolver.query(input.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()?.takeIf { it >= 0 }

    private fun storageFailure(error: IOException): Exception {
        val full = generateSequence<Throwable>(error) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
        return LocalizedIOException(text(if (full) R.string.backend_storage_full else R.string.backend_write_image_failed), error)
    }

    private fun outputStem(displayName: String): String {
        // At most 210 UTF-8 bytes leaves room for "_adjusted.webp" and a provider's " (n)" suffix
        // within the 255-byte filename limit. Do not split surrogate pairs.
        val stem = OutputRepository.safeStem(displayName).take(70)
        return if (stem.last().isHighSurrogate()) stem.dropLast(1) else stem
    }

    companion object {
        /** Preview decode size; pinch zoom shows detail without a full-resolution bitmap. */
        const val PREVIEW_MAX_EDGE = 2560

        /** Largest single bitmap; fits 50 MP camera images such as 8160 × 6144 at their original size. */
        const val MAX_BITMAP_PIXELS = 52_000_000L

        /** Decoded region plus output bitmap, about 320 MB in ARGB_8888. */
        const val MAX_PEAK_PIXELS = 80_000_000L
        const val WEBP_MAX_EDGE = 16383

        /** Fixed effort so equal parameters always encode to identical bytes. */
        const val WEBP_LOSSLESS_EFFORT = 75
        private const val PROGRESS_BYTES = 256L * 1024
        private const val TEMPORARY_DIRECTORY = "image-adjustment"

        fun formatLabel(settings: AdjustmentExportSettings): UiText = when (settings.format) {
            AdjustmentFormat.PNG -> text(R.string.adjust_format_png)
            AdjustmentFormat.JPEG -> text(R.string.adjust_format_jpeg_quality, settings.quality)
            AdjustmentFormat.WEBP -> if (settings.webpLossless) {
                text(R.string.adjust_format_webp_lossless)
            } else {
                text(R.string.adjust_format_webp_quality, settings.quality)
            }
        }

        private fun fitInside(width: Int, height: Int, maxEdge: Int): ImageSize {
            val largest = maxOf(width, height)
            if (largest <= maxEdge) return ImageSize(width, height)
            val scale = maxEdge.toDouble() / largest
            return ImageSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        }
    }
}

/**
 * Per-workflow cache of the rendered result and the latest trial file. Operations are serialized
 * so a superseded request never encodes concurrently with the current one, and only the trial
 * matching the requested parameters can be committed. Closing deletes every temporary file.
 */
class ImageAdjustmentSession internal constructor(
    private val processor: ImageAdjustmentProcessor,
    private val root: File,
) {
    private val mutex = Mutex()
    @Volatile private var closed = false
    @Volatile private var releaseRequested = false
    private var directory: File? = null
    private var rendered: Pair<Any, Bitmap>? = null
    private var trial: TrialFile? = null
    private var counter = 0

    private class TrialFile(val request: AdjustmentExportRequest, val file: File, val bytes: Long)

    /** Returns the byte size of a complete trial encode for exactly this request. */
    suspend fun encode(
        input: InputItem,
        source: AdjustmentSource,
        request: AdjustmentExportRequest,
        onStage: (UiText) -> Unit,
    ): Long = exclusive { checkpoint ->
        trial?.takeIf { it.request == request && it.file.isFile }?.let { return@exclusive it.bytes }
        discardTrial()
        // A subsampled preview can miss isolated transparent pixels, so any alpha channel is
        // composited for JPEG; fully opaque pixels are unaffected.
        val background = request.settings.background.takeIf {
            request.settings.format == AdjustmentFormat.JPEG && source.hasAlphaChannel
        }
        val key = request.geometry to background
        val bitmap = rendered?.takeIf { it.first == key }?.second ?: run {
            releaseRendered()
            processor.render(input, source, request.geometry, background, checkpoint, onStage).also { rendered = key to it }
        }
        val file = File(workingDirectory(), "trial-${++counter}.${request.settings.format.extension}")
        try {
            val bytes = processor.encode(bitmap, request.settings, file, checkpoint, onStage)
            trial = TrialFile(request, file, bytes)
            bytes
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Moves the matching trial into the app's output directory; it never re-encodes. */
    suspend fun commit(input: InputItem, request: AdjustmentExportRequest, onStage: (UiText) -> Unit): OutputArtifact =
        exclusive { checkpoint ->
            val ready = trial?.takeIf { it.request == request && it.file.isFile }
                ?: throw LocalizedIllegalStateException(text(R.string.backend_adjust_trial_missing))
            trial = null
            onStage(text(R.string.backend_adjust_saving))
            try {
                processor.commit(input, request, ready.file, ready.bytes, checkpoint)
            } finally {
                ready.file.delete()
                releaseRendered()
            }
        }

    /** Frees the rendered bitmap once the export screen is closed; trial files stay valid. */
    fun releaseRender() {
        if (mutex.tryLock()) {
            try {
                releaseRendered()
            } finally {
                mutex.unlock()
            }
        } else {
            releaseRequested = true
        }
    }

    fun close() {
        closed = true
        if (mutex.tryLock()) {
            try {
                cleanUp()
            } finally {
                mutex.unlock()
            }
        }
        // Otherwise the running operation cleans up as it releases the lock.
    }

    private suspend fun <T> exclusive(block: (checkpoint: () -> Unit) -> T): T {
        val context = currentCoroutineContext()
        return mutex.withLock {
            if (closed) throw CancellationException("Image adjustment session closed")
            try {
                block {
                    context.ensureActive()
                    if (closed) throw CancellationException("Image adjustment session closed")
                }
            } finally {
                when {
                    closed -> cleanUp()
                    releaseRequested -> {
                        releaseRequested = false
                        releaseRendered()
                    }
                }
            }
        }
    }

    private fun workingDirectory(): File = directory ?: synchronized(liveDirectories) {
        checkText(root.mkdirs() || root.isDirectory) { text(R.string.backend_output_path_create_failed, root.path) }
        // Directories left by an earlier process are never reused; live sessions register atomically.
        root.listFiles().orEmpty().filter { it.name !in liveDirectories }.forEach { it.deleteRecursively() }
        Files.createTempDirectory(root.toPath(), "session-").toFile().also {
            liveDirectories += it.name
            directory = it
        }
    }

    private fun discardTrial() {
        trial?.file?.delete()
        trial = null
    }

    private fun releaseRendered() {
        rendered?.second?.recycle()
        rendered = null
    }

    private fun cleanUp() {
        releaseRendered()
        discardTrial()
        directory?.let {
            it.deleteRecursively()
            liveDirectories -= it.name
        }
        directory = null
    }

    private companion object {
        val liveDirectories: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    }
}
