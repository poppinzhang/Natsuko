package com.poppinzhang.natsuko.ui

import android.graphics.Bitmap
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.SplitOptions
import com.poppinzhang.natsuko.model.SplitSource
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.processing.ImageSplitGeometry
import com.poppinzhang.natsuko.processing.SplitPlan

/** State for one single-image split workflow. */
data class ImageSplitState(
    val input: InputItem,
    val source: SplitSource? = null,
    /** Display-sized decode for the split diagram, fullscreen editor, and preview only. */
    val preview: Bitmap? = null,
    val loadError: UiText? = null,
    val options: SplitOptions = SplitOptions(),
    /** Selected divider line index (0-based) in free mode for fine-tuning. */
    val selectedDividerIndex: Int? = null,
) {
    val effectiveMaxParts: Int
        get() = source?.let { ImageSplitGeometry.maxPartsFor(it.width, it.height, options.direction) }
            ?: SplitOptions.MAX_PARTS

    val effectiveMaxDividerWidthPx: Int
        get() = source?.let {
            ImageSplitGeometry.maxDividerWidthFor(it.width, it.height, options.direction, options.parts)
        } ?: SplitOptions.MAX_DIVIDER_WIDTH_PX

    val canSplit: Boolean
        get() = source != null && preview != null &&
            ImageSplitGeometry.canSplit(source.width, source.height, options.direction)

    val plan: SplitPlan? by lazy {
        source?.let { ImageSplitGeometry.resolve(it.width, it.height, options) }
    }

    val hasCustomDividers: Boolean by lazy {
        source?.let { ImageSplitGeometry.hasCustomDividers(it.width, it.height, options) } ?: false
    }
}
