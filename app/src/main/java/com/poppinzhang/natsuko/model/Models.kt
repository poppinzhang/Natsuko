package com.poppinzhang.natsuko.model

import android.net.Uri
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

data class InputItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
)

data class OutputArtifact(
    val file: File,
    val mimeType: String,
    val label: UiText,
    val userStorage: SavedOutput? = null,
    val detail: UiText? = null,
) {
    constructor(file: File, mimeType: String, label: String, userStorage: SavedOutput? = null, detail: String? = null) :
        this(file, mimeType, UiText.Literal(label), userStorage, detail?.let(UiText::Literal))
}

data class SavedOutput(
    val uri: Uri,
    val directoryLabel: String,
    val destinationKey: String,
)

data class ProcessingFailure(
    val source: UiText,
    val reason: UiText,
) {
    constructor(source: String, reason: UiText) : this(UiText.Literal(source), reason)
    constructor(source: String, reason: String) : this(UiText.Literal(source), UiText.Literal(reason))
}

data class ProcessingSkipped(val source: UiText, val reason: UiText) {
    constructor(source: String, reason: UiText) : this(UiText.Literal(source), reason)
}

data class ProcessingOutcome(
    val outputs: List<OutputArtifact>,
    val failures: List<ProcessingFailure> = emptyList(),
    val skipped: List<ProcessingSkipped> = emptyList(),
)

data class ProcessingProgress(
    val completed: Int,
    val total: Int,
    val message: UiText,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)

    /** A stage whose remaining work cannot be measured reliably; only its message is shown. */
    val isIndeterminate: Boolean
        get() = total <= 0
}

enum class MergeDirection { HORIZONTAL, VERTICAL, GRID }

object MergeGridLayout {
    fun columnCount(itemCount: Int): Int {
        require(itemCount > 0)
        return ceil(sqrt(itemCount.toDouble())).toInt()
    }
}

data class MergeOptions(
    val direction: MergeDirection = MergeDirection.VERTICAL,
    val fillColor: Int = 0xFFFFFFFF.toInt(),
)

enum class PdfOutputMode { COMBINED, SEPARATE }
enum class PdfPagePreset { A4, FIT_IMAGE }
enum class PdfOrientation { AUTO, PORTRAIT, LANDSCAPE }

data class ImagesToPdfOptions(
    val outputMode: PdfOutputMode = PdfOutputMode.COMBINED,
    val pagePreset: PdfPagePreset = PdfPagePreset.A4,
    val orientation: PdfOrientation = PdfOrientation.AUTO,
    val marginPoints: Int = 24,
)

enum class ImageOutputFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
}

data class PdfToImagesOptions(
    val scale: Float = 2f,
    val format: ImageOutputFormat = ImageOutputFormat.PNG,
    val jpegQuality: Int = 90,
)

data class GifOptions(
    val framesPerSecond: Int = 10,
    val sampleIntervalMs: Long = 100,
    val maxWidth: Int = 720,
    val loopCount: Int = 0,
    val maxFrames: Int = 300,
)
