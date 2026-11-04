package com.poppinzhang.natsuko

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MergePreviewLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun previewPositionsFollowSelectedMergeDirection() {
        val viewModel = showPreview(List(4) { index -> createImage("preview-$index.png", 120, 80) })
        waitForItems(0, 1, 2, 3)

        val verticalFirst = itemBounds(0)
        val verticalSecond = itemBounds(1)
        assertEquals(verticalFirst.center.x, verticalSecond.center.x, 1f)
        assertTrue(verticalSecond.top > verticalFirst.top)

        composeRule.runOnIdle { viewModel.updateMergeDirection(MergeDirection.HORIZONTAL) }
        val horizontalFirst = itemBounds(0)
        val horizontalSecond = itemBounds(1)
        assertEquals(horizontalFirst.center.y, horizontalSecond.center.y, 1f)
        assertTrue(horizontalSecond.left > horizontalFirst.left)

        composeRule.runOnIdle { viewModel.updateMergeDirection(MergeDirection.GRID) }
        val gridFirst = itemBounds(0)
        val gridSecond = itemBounds(1)
        val gridThird = itemBounds(2)
        assertEquals(gridFirst.center.y, gridSecond.center.y, 1f)
        assertEquals(gridFirst.center.x, gridThird.center.x, 1f)
        assertTrue(gridSecond.left > gridFirst.left)
        assertTrue(gridThird.top > gridFirst.top)
    }

    @Test
    fun mixedImageSizesPreviewTheOutputLayoutWithoutCroppingInEveryDirection() {
        val viewModel = showPreview(mixedSizeImages())
        waitForItems(0, 1, 2)

        val layouts = listOf(
            ExpectedLayout(
                MergeDirection.VERTICAL,
                120f,
                260f,
                listOf(Rect(0f, 0f, 120f, 80f), Rect(30f, 80f, 90f, 240f), Rect(45f, 240f, 75f, 260f)),
            ),
            ExpectedLayout(
                MergeDirection.HORIZONTAL,
                210f,
                160f,
                listOf(Rect(0f, 40f, 120f, 120f), Rect(120f, 0f, 180f, 160f), Rect(180f, 70f, 210f, 90f)),
            ),
            ExpectedLayout(
                MergeDirection.GRID,
                320f,
                320f,
                listOf(Rect(0f, 26f, 160f, 133f), Rect(210f, 0f, 270f, 160f), Rect(0f, 186f, 160f, 293f)),
            ),
        )
        layouts.forEach { layout ->
            composeRule.runOnIdle { viewModel.updateMergeDirection(layout.direction) }
            val viewport = bounds("merge-preview")
            val canvas = bounds("merge-preview-canvas")
            assertEquals(360f * context.resources.displayMetrics.density, viewport.height, 2f)
            assertContained(viewport, canvas)
            assertEquals(layout.width / layout.height, canvas.width / canvas.height, 0.02f)
            layout.images.forEachIndexed { index, expected ->
                val actual = itemBounds(index)
                assertContained(canvas, actual)
                assertEquals(canvas.left + expected.left / layout.width * canvas.width, actual.left, 2f)
                assertEquals(canvas.top + expected.top / layout.height * canvas.height, actual.top, 2f)
                assertEquals(expected.width / layout.width * canvas.width, actual.width, 2f)
                assertEquals(expected.height / layout.height * canvas.height, actual.height, 2f)
                assertCompleteImageEdges(index)
            }
            saveImage("merge-preview-${layout.direction.name.lowercase()}.png", capture("merge-preview"))
            saveImage("merge-screen-${layout.direction.name.lowercase()}.png", composeRule.onRoot().captureToImage())
        }
    }

    @Test
    fun fillColorChangesImmediatelyInTheResultPadding() {
        val viewModel = showPreview(mixedSizeImages())
        waitForItems(0, 1, 2)

        assertPixelColor(capture("merge-preview-canvas"), 0.05f, 0.9f, 0xFFFFFFFF.toInt())
        val fillColor = 0xFF00BCD4.toInt()
        composeRule.runOnIdle { viewModel.updateMergeColor(fillColor) }
        assertPixelColor(capture("merge-preview-canvas"), 0.05f, 0.9f, fillColor)
        assertCompleteImageEdges(1)
    }

    @Test
    fun selectingReorderingAndRemovingImagesUpdatesThePreview() {
        val files = mixedSizeImages()
        val viewModel = showPreview(files)
        waitForItems(0, 1, 2)

        assertSelected(files[0].name)
        composeRule.onNodeWithContentDescription(context.getString(R.string.main_move_later)).performScrollTo().performClick()
        assertSelected(files[0].name)
        composeRule.onNodeWithContentDescription(context.getString(R.string.main_move_earlier)).performScrollTo().performClick()
        assertSelected(files[0].name)
        composeRule.onNodeWithTag("merge-preview-item-1", useUnmergedTree = true).performClick()
        assertSelected(files[1].name)
        composeRule.onNodeWithContentDescription(context.getString(R.string.main_move_earlier)).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(files[1].name, files[0].name, files[2].name), viewModel.state.value.inputs.map { it.displayName })
        }
        assertSelected(files[1].name)

        composeRule.onNodeWithContentDescription(context.getString(R.string.main_move_later)).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(files.map { it.name }, viewModel.state.value.inputs.map { it.displayName })
        }
        assertSelected(files[1].name)
        composeRule.onNodeWithText(context.getString(R.string.main_next_image)).performScrollTo().performClick()
        assertSelected(files[2].name)
        composeRule.onNodeWithText(context.getString(R.string.main_previous_image)).performScrollTo().performClick()
        assertSelected(files[1].name)

        composeRule.onNodeWithContentDescription(context.getString(R.string.main_remove_named, files[1].name)).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(files[0].name, files[2].name), viewModel.state.value.inputs.map { it.displayName })
        }
        waitForItems(0, 1)
        composeRule.onNodeWithTag("merge-preview-item-2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun longPressDraggingAnImageToAnotherImageReordersTheInputs() {
        val files = mixedSizeImages()
        val viewModel = showPreview(files)
        waitForItems(0, 1, 2)
        val canvas = bounds("merge-preview-canvas")
        val start = itemBounds(0).center - canvas.topLeft
        val end = itemBounds(2).center - canvas.topLeft

        composeRule.onNodeWithTag("merge-preview-canvas", useUnmergedTree = true).performTouchInput {
            down(start)
            advanceEventTime(1_000)
            moveTo((start + end) / 2f, delayMillis = 100)
            moveTo(end, delayMillis = 100)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(files[1].name, files[2].name, files[0].name), viewModel.state.value.inputs.map { it.displayName })
        }
        waitForItems(0, 1, 2)
        assertSelected(files[0].name)
        assertEquals(2f, itemBounds(0).width / itemBounds(1).width, 0.1f)
    }

    @Test
    fun unreadableImageDoesNotBlockTheOtherImagePreviews() {
        val invalid = File(context.filesDir, "preview-invalid.png").apply { writeText("not an image") }
        showPreview(listOf(createImage("preview-valid-first.png", 120, 80), invalid, createImage("preview-valid-last.png", 60, 160)))
        waitForItems(0, 2)

        composeRule.onNodeWithTag("merge-preview-item-1", useUnmergedTree = true).assertDoesNotExist()
        val canvas = bounds("merge-preview-canvas")
        val first = itemBounds(0)
        val last = itemBounds(2)
        assertEquals(0.5f, canvas.width / canvas.height, 0.02f)
        assertEquals(first.bottom, last.top, 2f)
        assertEquals(first.center.x, last.center.x, 2f)
        assertCompleteImageEdges(0)
        assertCompleteImageEdges(2)
        composeRule.onNodeWithText(context.getString(R.string.main_next_image)).performScrollTo().performClick()
        assertSelected(invalid.name)
        composeRule.onNodeWithContentDescription(context.getString(R.string.main_remove_named, invalid.name)).performScrollTo().performClick()
        waitForItems(0, 1)
    }

    private fun showPreview(files: List<File>): NatsukoViewModel {
        val viewModel = NatsukoViewModel(context.applicationContext as Application)
        composeRule.setContent {
            NatsukoTheme { NatsukoApp(viewModel) }
        }
        composeRule.runOnIdle { viewModel.acceptSelection(Feature.IMAGE_MERGE, files.map(::fixtureUri)) }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.inputs.size == files.size }
        return viewModel
    }

    private fun waitForItems(vararg indices: Int) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            indices.all { index -> runCatching { itemBounds(index).width > 0f }.getOrDefault(false) }
        }
        composeRule.waitForIdle()
    }

    private fun assertSelected(name: String) {
        composeRule.onNodeWithTag("merge-preview-selection").assertTextContains(name, substring = true)
    }

    private fun assertContained(outer: Rect, inner: Rect) {
        assertTrue(
            "$inner must fit inside $outer",
            inner.left >= outer.left - 2f && inner.top >= outer.top - 2f &&
                inner.right <= outer.right + 2f && inner.bottom <= outer.bottom + 2f,
        )
    }

    private fun assertCompleteImageEdges(index: Int) {
        val image = capture("merge-preview-item-$index")
        assertPixelColor(image, 0.5f, 0.17f, TOP_COLOR)
        assertPixelColor(image, 0.5f, 0.83f, BOTTOM_COLOR)
        assertPixelColor(image, 0.17f, 0.5f, LEFT_COLOR)
        assertPixelColor(image, 0.83f, 0.5f, RIGHT_COLOR)
    }

    private fun assertPixelColor(image: ImageBitmap, xFraction: Float, yFraction: Float, expected: Int) {
        val pixels = image.toPixelMap()
        val x = (pixels.width * xFraction).toInt().coerceIn(0, pixels.width - 1)
        val y = (pixels.height * yFraction).toInt().coerceIn(0, pixels.height - 1)
        val actual = pixels[x, y].toArgb()
        listOf(0, 8, 16, 24).forEach { shift ->
            assertEquals(
                "Color channel at ($x, $y)",
                (expected ushr shift and 0xFF).toFloat(),
                (actual ushr shift and 0xFF).toFloat(),
                3f,
            )
        }
    }

    private fun capture(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage()

    private fun saveImage(name: String, image: ImageBitmap) {
        FileOutputStream(File(context.filesDir, name)).use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun itemBounds(index: Int) = bounds("merge-preview-item-$index")

    private fun bounds(tag: String) = composeRule
        .onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot

    private fun mixedSizeImages() = listOf(
        createImage("preview-wide.png", 120, 80),
        createImage("preview-tall.png", 60, 160),
        createImage("preview-small.png", 30, 20),
    )

    private fun createImage(name: String, width: Int, height: Int): File {
        val file = File(context.filesDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        canvas.drawColor(0xFF667788.toInt())
        val edgeWidth = width / 4f
        val edgeHeight = height / 4f
        paint.color = TOP_COLOR
        canvas.drawRect(0f, 0f, width.toFloat(), edgeHeight, paint)
        paint.color = BOTTOM_COLOR
        canvas.drawRect(0f, height - edgeHeight, width.toFloat(), height.toFloat(), paint)
        paint.color = LEFT_COLOR
        canvas.drawRect(0f, 0f, edgeWidth, height.toFloat(), paint)
        paint.color = RIGHT_COLOR
        canvas.drawRect(width - edgeWidth, 0f, width.toFloat(), height.toFloat(), paint)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun fixtureUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private data class ExpectedLayout(val direction: MergeDirection, val width: Float, val height: Float, val images: List<Rect>)

    private companion object {
        const val TOP_COLOR = 0xFFEE2222.toInt()
        const val BOTTOM_COLOR = 0xFF2244EE.toInt()
        const val LEFT_COLOR = 0xFF22CC44.toInt()
        const val RIGHT_COLOR = 0xFFFFDD22.toInt()
    }
}
