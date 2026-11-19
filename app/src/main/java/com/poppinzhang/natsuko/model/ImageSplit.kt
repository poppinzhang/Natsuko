package com.poppinzhang.natsuko.model

enum class SplitDirection { HORIZONTAL, VERTICAL }

enum class SplitMode { EQUAL, FREE }

enum class SplitOrientationHint { LANDSCAPE, PORTRAIT, UNSPECIFIED }

data class SplitSource(
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val hasAlpha: Boolean,
)

data class SplitOptions(
    val direction: SplitDirection = SplitDirection.HORIZONTAL,
    val parts: Int = DEFAULT_PARTS,
    val mode: SplitMode = SplitMode.EQUAL,
    /** Pixel width excluded under each divider line; 0 keeps all pixels intact. */
    val dividerWidthPx: Int = DEFAULT_DIVIDER_WIDTH_PX,
    /** Normalized divider center positions in (0.0, 1.0), used when [mode] is [SplitMode.FREE]. */
    val freeDividers: List<Double> = listOf(0.5),
    val previewSpacingPx: Int = DEFAULT_PREVIEW_SPACING_PX,
    val previewBackgroundColor: Int = BACKGROUND_WHITE,
) {
    companion object {
        const val MIN_PARTS = 2
        const val MAX_PARTS = 20
        const val DEFAULT_PARTS = 2

        const val MIN_DIVIDER_WIDTH_PX = 0
        const val MAX_DIVIDER_WIDTH_PX = 500
        const val DEFAULT_DIVIDER_WIDTH_PX = 0

        const val MIN_PREVIEW_SPACING_PX = 0
        const val MAX_PREVIEW_SPACING_PX = 500
        const val DEFAULT_PREVIEW_SPACING_PX = 24

        const val BACKGROUND_WHITE = 0xFFFFFFFF.toInt()
        const val BACKGROUND_BLACK = 0xFF000000.toInt()
        const val BACKGROUND_DIM = 0xFF15202B.toInt()
        const val BACKGROUND_GRAY = 0xFFE6E9EE.toInt()
        const val BACKGROUND_CREAM = 0xFFF5EEE8.toInt()
        const val BACKGROUND_BLUE = 0xFFE9EEF5.toInt()
        const val BACKGROUND_PINK = 0xFFFFE4E8.toInt()
    }
}
