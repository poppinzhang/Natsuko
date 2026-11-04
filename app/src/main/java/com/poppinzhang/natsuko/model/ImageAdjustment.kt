package com.poppinzhang.natsuko.model

/** Integer pixel rectangle; right and bottom are exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right > left && bottom > top) { "Empty pixel rectangle" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class AdjustmentTool { CROP, STRAIGHTEN, ROTATE, MIRROR, RESIZE }

/** Width:height in landscape form; FREE and ORIGINAL derive their ratio elsewhere. */
enum class CropAspect(val ratioWidth: Int, val ratioHeight: Int) {
    FREE(0, 0),
    ORIGINAL(0, 0),
    SQUARE(1, 1),
    RATIO_4_3(4, 3),
    RATIO_3_2(3, 2),
    RATIO_16_9(16, 9),
}

/**
 * Edit parameters only; every preview, size display, and export regenerates pixels from the
 * source with these values, so no intermediate bitmap is ever resampled again.
 */
data class AdjustmentEdits(
    /** Clockwise quarter turns, applied after [mirrored]. View flips are folded into this pair. */
    val quarterTurns: Int = 0,
    /** Mirrors the oriented source horizontally before [quarterTurns]. */
    val mirrored: Boolean = false,
    /** Straighten angle in tenths of a degree; positive values rotate the picture clockwise. */
    val straightenTenths: Int = 0,
    /** Crop chosen in the rotated frame; null keeps the full frame. */
    val crop: PixelRect? = null,
    val aspect: CropAspect = CropAspect.FREE,
    /** Uses the height:width form of a non-square [aspect]. */
    val aspectSwapped: Boolean = false,
    /** Proportional reduction after geometry edits, in (0, 1]; 1 keeps the edited size. */
    val scale: Double = 1.0,
)

/** Lightweight parameter history: no bitmap is retained per step. */
data class AdjustmentHistory(
    val past: List<AdjustmentEdits> = emptyList(),
    val present: AdjustmentEdits = AdjustmentEdits(),
    val future: List<AdjustmentEdits> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun record(next: AdjustmentEdits): AdjustmentHistory =
        if (next == present) this else AdjustmentHistory((past + present).takeLast(LIMIT), next)

    fun undo(): AdjustmentHistory =
        if (past.isEmpty()) this else AdjustmentHistory(past.dropLast(1), past.last(), listOf(present) + future)

    fun redo(): AdjustmentHistory =
        if (future.isEmpty()) this else AdjustmentHistory(past + present, future.first(), future.drop(1))

    companion object {
        const val LIMIT = 100
    }
}

enum class AdjustmentFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
}

data class AdjustmentExportSettings(
    val format: AdjustmentFormat,
    /** Encoder quality for JPEG and lossy WebP. */
    val quality: Int = DEFAULT_QUALITY,
    val webpLossless: Boolean = true,
    /** JPEG has no alpha channel; transparent areas are composited onto this color. */
    val background: Int = BACKGROUND_WHITE,
) {
    val isLossy: Boolean get() = format == AdjustmentFormat.JPEG || format == AdjustmentFormat.WEBP && !webpLossless

    /** Canonical values for settings that cannot change the encoded file, so they never invalidate an estimate. */
    fun normalized(hasTransparency: Boolean): AdjustmentExportSettings = AdjustmentExportSettings(
        format = format,
        quality = if (isLossy) quality else DEFAULT_QUALITY,
        webpLossless = format != AdjustmentFormat.WEBP || webpLossless,
        background = if (format == AdjustmentFormat.JPEG && hasTransparency) background else BACKGROUND_WHITE,
    )

    companion object {
        val QUALITIES = listOf(60, 75, 85, 95, 100)
        const val DEFAULT_QUALITY = 85
        const val BACKGROUND_WHITE = 0xFFFFFFFF.toInt()
        const val BACKGROUND_BLACK = 0xFF000000.toInt()
    }
}

/** Facts about the decoded content, independent of the provider's declared MIME type. */
data class AdjustmentSource(
    /** Dimensions after EXIF orientation. */
    val width: Int,
    val height: Int,
    /** Detected content format, for example image/png. */
    val mimeType: String?,
    /** Transparent pixels found in the preview; decides whether a JPEG background can be chosen. */
    val hasTransparency: Boolean,
    /** The decoded pixels carry alpha, so JPEG exports always composite onto the background. */
    val hasAlphaChannel: Boolean,
    val fileSize: Long?,
    /** Wide-gamut or HDR content is converted to SDR sRGB. */
    val convertsColor: Boolean,
) {
    fun defaultExportSettings(): AdjustmentExportSettings = when (mimeType?.lowercase()) {
        "image/png" -> AdjustmentExportSettings(AdjustmentFormat.PNG)
        "image/jpeg" -> AdjustmentExportSettings(AdjustmentFormat.JPEG)
        "image/webp" -> AdjustmentExportSettings(AdjustmentFormat.WEBP, webpLossless = true)
        else -> AdjustmentExportSettings(if (hasTransparency) AdjustmentFormat.PNG else AdjustmentFormat.JPEG)
    }
}
