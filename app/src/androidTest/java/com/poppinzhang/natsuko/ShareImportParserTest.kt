package com.poppinzhang.natsuko

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poppinzhang.natsuko.data.ShareImportParser
import com.poppinzhang.natsuko.model.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareImportParserTest {
    @Test
    fun importsSingleImageAndRoutesToSelectedShareFeature() {
        val uri = Uri.parse("content://tests/screenshot.png")
        val intent = Intent(Intent.ACTION_SEND)
            .setComponent(ComponentName("com.poppinzhang.natsuko", "com.poppinzhang.natsuko.share.ImageMergeShare"))
            .putExtra(Intent.EXTRA_STREAM, uri)

        assertEquals(Feature.IMAGE_MERGE, ShareImportParser.feature(intent))
        assertEquals(listOf(uri), ShareImportParser.uris(intent))
    }

    @Test
    fun importsMultiplePdfsFromExtrasAndClipDataWithoutDuplicates() {
        val first = Uri.parse("content://tests/one.pdf")
        val second = Uri.parse("content://tests/two.pdf")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setComponent(ComponentName("com.poppinzhang.natsuko", "com.poppinzhang.natsuko.share.PdfToImagesShare"))
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        intent.clipData = ClipData.newRawUri("PDF", first)

        assertEquals(Feature.PDF_TO_IMAGES, ShareImportParser.feature(intent))
        assertEquals(listOf(first, second), ShareImportParser.uris(intent))
    }

    @Test
    fun routesSharedImagesToImageAdjustmentWithoutDuplicates() {
        val first = Uri.parse("content://tests/one.png")
        val second = Uri.parse("content://tests/two.webp")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setComponent(ComponentName("com.poppinzhang.natsuko", "com.poppinzhang.natsuko.share.ImageAdjustmentShare"))
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
        intent.clipData = ClipData.newRawUri("Images", second)

        assertEquals(Feature.IMAGE_ADJUSTMENT, ShareImportParser.feature(intent))
        assertEquals(listOf(second, first), ShareImportParser.uris(intent))
    }

    @Test
    fun invalidSharePayloadProducesNoInputs() {
        val intent = Intent(Intent.ACTION_SEND)
            .setComponent(ComponentName("com.poppinzhang.natsuko", "com.poppinzhang.natsuko.share.ImagesToPdfShare"))

        assertTrue(ShareImportParser.uris(intent).isEmpty())
    }
}
