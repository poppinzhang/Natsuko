package com.poppinzhang.natsuko.ui

import android.graphics.Bitmap
import com.poppinzhang.natsuko.model.AdjustmentEdits
import com.poppinzhang.natsuko.model.AdjustmentExportSettings
import com.poppinzhang.natsuko.model.AdjustmentHistory
import com.poppinzhang.natsuko.model.AdjustmentSource
import com.poppinzhang.natsuko.model.AdjustmentTool
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.processing.AdjustmentExportRequest
import com.poppinzhang.natsuko.processing.AdjustmentGeometry
import com.poppinzhang.natsuko.processing.ResolvedAdjustment

/** Output size of the current export parameters, measured from a complete trial encode. */
sealed interface AdjustmentEstimate {
    val request: AdjustmentExportRequest?

    data object Idle : AdjustmentEstimate {
        override val request: AdjustmentExportRequest? get() = null
    }

    data class Calculating(override val request: AdjustmentExportRequest, val stage: UiText) : AdjustmentEstimate
    data class Ready(override val request: AdjustmentExportRequest, val bytes: Long) : AdjustmentEstimate
    data class Failed(
        override val request: AdjustmentExportRequest,
        val message: UiText,
        /** Reducing the size in the editor can resolve this failure. */
        val suggestsReduction: Boolean,
    ) : AdjustmentEstimate
}

/** One single-image workflow; a newly selected image always starts from a fresh state. */
data class ImageAdjustmentState(
    val input: InputItem,
    val source: AdjustmentSource? = null,
    /** Display-sized decode for the editor only; never encoded as a result. */
    val preview: Bitmap? = null,
    val loadError: UiText? = null,
    val history: AdjustmentHistory = AdjustmentHistory(),
    /** Unfinished drag or slider value, recorded as one undo step when the gesture ends. */
    val gesture: AdjustmentEdits? = null,
    val tool: AdjustmentTool = AdjustmentTool.CROP,
    /** Kept for the whole workflow, including returns from the export screen to the editor. */
    val exportSettings: AdjustmentExportSettings? = null,
    val estimate: AdjustmentEstimate = AdjustmentEstimate.Idle,
    /** Edits of the most recently saved version. */
    val exportedEdits: AdjustmentEdits? = null,
    val confirmDiscard: Boolean = false,
) {
    val edits: AdjustmentEdits get() = gesture ?: history.present

    val resolved: ResolvedAdjustment? by lazy {
        source?.let { AdjustmentGeometry.resolve(it.width, it.height, edits) }
    }

    /** Only edits that change the output count; leaving with them asks for confirmation. */
    val hasUnexportedEdits: Boolean by lazy {
        val saved = exportedEdits ?: AdjustmentEdits()
        val current = resolved
        if (current == null || source == null) edits != saved else {
            current != AdjustmentGeometry.resolve(source.width, source.height, saved)
        }
    }

    val exportRequest: AdjustmentExportRequest? by lazy {
        val loaded = source ?: return@lazy null
        val settings = exportSettings ?: return@lazy null
        resolved?.let { AdjustmentExportRequest(it, settings.normalized(loaded.hasTransparency)) }
    }
}
