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

    @Test
    fun pagesOfOneDocumentShareThePixelBudget() {
        assertEquals(PdfPageLayout.DOCUMENT_PIXEL_BUDGET, PdfPageLayout.pagePixelLimit(1))
        assertEquals(1_600_000L, PdfPageLayout.pagePixelLimit(100))
        assertEquals(2_000_000L, PdfPageLayout.pagePixelLimit(4, budget = 8_000_000))
        // A very long document keeps legible pages rather than shrinking them without end.
        assertEquals(PdfPageLayout.MIN_PAGE_PIXELS, PdfPageLayout.pagePixelLimit(1_000))
    }

    @Test
    fun imagesShrinkInProportionOnlyWhenOverTheirShare() {
        val photo = ImageSize(4096, 3072)
        // Twelve such photographs fit the budget at full size.
        assertEquals(photo, PdfPageLayout.decodeSize(photo, PdfPageLayout.pagePixelLimit(12)))
        assertEquals(ImageSize(80, 40), PdfPageLayout.decodeSize(ImageSize(80, 40), PdfPageLayout.MIN_PAGE_PIXELS))

        val limit = PdfPageLayout.pagePixelLimit(100)
        val reduced = PdfPageLayout.decodeSize(photo, limit)
        val pixels = reduced.width.toLong() * reduced.height
        assertTrue(pixels <= limit)
        assertTrue(pixels > limit * 99 / 100)
        assertEquals(4096f / 3072f, reduced.width.toFloat() / reduced.height, 0.01f)
    }
}
