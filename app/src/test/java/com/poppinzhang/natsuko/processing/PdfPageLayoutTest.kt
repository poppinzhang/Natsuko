package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.model.ImagesToPdfOptions
import com.poppinzhang.natsuko.model.PdfOrientation
import com.poppinzhang.natsuko.model.PdfPagePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageLayoutTest {
    @Test
    fun a4AutoRotatesForLandscapeImage() {
        val size = PdfPageLayout.pageSize(
            ImageSize(1600, 900),
            ImagesToPdfOptions(pagePreset = PdfPagePreset.A4, orientation = PdfOrientation.AUTO),
        )

        assertEquals(ImageSize(842, 595), size)
    }

    @Test
    fun contentKeepsAspectRatioWithinMargins() {
        val rect = PdfPageLayout.contentRect(ImageSize(595, 842), ImageSize(1600, 900), 24)

        assertTrue(rect.left >= 24)
        assertTrue(rect.left + rect.width <= 595 - 24)
        assertTrue(rect.top >= 24)
        assertTrue(rect.top + rect.height <= 842 - 24)
        assertEquals(1600f / 900f, rect.width.toFloat() / rect.height, 0.01f)
    }
}
