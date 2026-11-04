package com.poppinzhang.natsuko

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.GifOptions
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.processing.VideoToGifProcessor
import com.poppinzhang.natsuko.processing.video.FfmpegVideoEngine
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FfmpegVideoProcessorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val processor = VideoToGifProcessor(context, OutputRepository(context))
    private val outputRoot = File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, Environment.DIRECTORY_PICTURES),
        "Natsuko/VideoToGif",
    )

    @Test
    fun bundledCodecsConvertMp4MkvAndMovFromNonSeekableContentUris() = runBlocking {
        for (name in listOf("h264.mp4", "hevc.mkv", "av1.mp4", "h264.mov", "audio.mp4")) {
            val cacheBefore = files(context.cacheDir)
            val progress = mutableListOf<Pair<Int, Int>>()
            val outcome = withTimeout(30_000) {
                processor.process(input(name), GifOptions()) { completed, total, _ ->
                    progress += completed to total
                }
            }
            val output = outcome.outputs.single()
            try {
                assertTrue("$name: ${outcome.failures}", outcome.failures.isEmpty())
                assertEquals("image/gif", output.mimeType)
                assertTrue(output.file.canonicalPath.startsWith(outputRoot.canonicalPath + File.separator))
                val gif = GifStructure.read(output.file)
                assertEquals(name, 96, gif.width)
                assertEquals(name, 64, gif.height)
                assertEquals(name, 10, gif.delays.size)
                assertEquals(name, List(10) { 10 }, gif.delays)
                assertEquals(name, 0, gif.loopCount)
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(output.file))
                assertTrue("$name must decode as animation", drawable is AnimatedImageDrawable)
                assertEquals(96, drawable.intrinsicWidth)
                assertEquals(64, drawable.intrinsicHeight)
                assertTrue("Progress must be observable", progress.size >= 2)
                assertTrue(progress.all { (done, total) -> total > 0 && done in 0..total })
                assertEquals(progress.last().second, progress.last().first)
                val fractions = progress.map { (done, total) -> done.toDouble() / total }
                assertTrue("Progress must not go backwards: $progress", fractions.zipWithNext().all { (a, b) -> b >= a })
                assertEquals("Temporary input must be removed", cacheBefore, files(context.cacheDir))
            } finally {
                output.file.delete()
            }
        }
    }

    @Test
    fun ffprobeReadsCodecContainerDurationAndRotationMetadata() = runBlocking {
        val engine = FfmpegVideoEngine(context)
        for (name in listOf("h264.mp4", "hevc.mkv", "av1.mp4", "h264.mov", "audio.mp4", "rotated.mp4", "narrow.mp4")) {
            val source = File.createTempFile("ffprobe-test-", ".video", context.cacheDir)
            try {
                InstrumentationRegistry.getInstrumentation().context.assets.open("video/$name").use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                val metadata = engine.probe(source)
                assertEquals(name, 1_000L, metadata.durationMs)
                val expected = when (name) {
                    "rotated.mp4" -> 64 to 96
                    "narrow.mp4" -> 4 to 512
                    else -> 96 to 64
                }
                assertEquals(name, expected.first, metadata.width)
                assertEquals(name, expected.second, metadata.height)
            } finally {
                source.delete()
            }
        }
    }

    @Test
    fun preservesRotationAndTallNarrowAspectRatiosWithoutUpscaling() = runBlocking {
        for ((name, expected) in listOf("rotated.mp4" to (64 to 96), "narrow.mp4" to (4 to 512))) {
            val outcome = processor.process(input(name), GifOptions(maxWidth = 720)) { _, _, _ -> }
            try {
                val gif = GifStructure.read(outcome.outputs.single().file)
                assertEquals(name, expected.first, gif.width)
                assertEquals(name, expected.second, gif.height)
            } finally {
                outcome.outputs.forEach { it.file.delete() }
            }
        }
    }

    @Test
    fun samplingFrameLimitPlaybackTimingAndLoopCountAreIndependent() = runBlocking {
        val options = GifOptions(framesPerSecond = 5, sampleIntervalMs = 100, maxWidth = 240, maxFrames = 3, loopCount = 3)
        val outcome = processor.process(input("cancel.mp4"), options) { _, _, _ -> }
        try {
            val gif = GifStructure.read(outcome.outputs.single().file)
            assertEquals(240, gif.width)
            assertEquals(135, gif.height)
            assertEquals(3, gif.delays.size)
            assertEquals(listOf(20, 20, 20), gif.delays)
            assertEquals(2, gif.loopCount) // Initial play plus two repetitions.
        } finally {
            outcome.outputs.forEach { it.file.delete() }
        }
    }

    @Test
    fun singleFrameUsesRequestedDelayAndPlayingOnceOmitsTheLoopExtension() = runBlocking {
        val outcome = processor.process(input("h264.mp4"), GifOptions(framesPerSecond = 5, maxFrames = 1, loopCount = 1)) { _, _, _ -> }
        try {
            val gif = GifStructure.read(outcome.outputs.single().file)
            assertNull(gif.loopCount)
            assertEquals(listOf(20), gif.delays)
        } finally {
            outcome.outputs.forEach { it.file.delete() }
        }
    }

    @Test
    fun thirtyFpsUsesAlternatingCentisecondDelaysWithoutSpeedingUpTheAnimation() = runBlocking {
        val outcome = processor.process(input("h264.mp4"), GifOptions(framesPerSecond = 30)) { _, _, _ -> }
        try {
            val gif = GifStructure.read(outcome.outputs.single().file)
            assertEquals(10, gif.delays.size)
            assertTrue(gif.delays.all { it in 3..4 })
            assertTrue("Ten frames at 30 fps should last about 333 ms: ${gif.delays}", gif.delays.sum() in 32..34)
        } finally {
            outcome.outputs.forEach { it.file.delete() }
        }
    }

    @Test
    fun adaptivePalettePreservesColorsBetweenRgb332Levels() = runBlocking {
        val outcome = processor.process(input("h264.mp4"), GifOptions()) { _, _, _ -> }
        try {
            val bitmap = BitmapFactory.decodeFile(outcome.outputs.single().file.absolutePath)
            assertNotNull(bitmap)
            try {
                val actual = bitmap!!.getPixel(12, 12)
                // Source square is RGB(119,87,203); RGB332 used to produce roughly (109,73,170).
                assertTrue("Red: ${Color.red(actual)}", abs(Color.red(actual) - 119) <= 12)
                assertTrue("Green: ${Color.green(actual)}", abs(Color.green(actual) - 87) <= 12)
                assertTrue("Blue: ${Color.blue(actual)}", abs(Color.blue(actual) - 203) <= 12)
            } finally {
                bitmap?.recycle()
            }
        } finally {
            outcome.outputs.forEach { it.file.delete() }
        }
    }

    @Test
    fun corruptMissingAndRevokedInputsFailClearlyWithoutPartialFiles() = runBlocking {
        for (name in listOf("corrupt.mp4", "missing.mp4", "denied.mp4")) {
            val outputBefore = files(outputRoot)
            val cacheBefore = files(context.cacheDir)
            val error = runCatching {
                processor.process(input(name), GifOptions()) { _, _, _ -> }
            }.exceptionOrNull()
            assertNotNull("$name must fail", error)
            assertFalse("$name needs an actionable failure", error!!.message.isNullOrBlank())
            assertEquals("$name left a partial output", outputBefore, files(outputRoot))
            assertEquals("$name left a cached input", cacheBefore, files(context.cacheDir))
        }
    }

    @Test
    fun cancellationFromProgressTerminatesConversionAndRemovesPartialFiles() = runBlocking {
        val outputBefore = files(outputRoot)
        val cacheBefore = files(context.cacheDir)
        var cancelledDuringEncoding = false
        val error = runCatching {
            withTimeout(30_000) {
                processor.process(input("cancel.mp4"), GifOptions(sampleIntervalMs = 50)) { completed, total, _ ->
                    if (total == 1000 && completed in 451 until 990) {
                        cancelledDuringEncoding = true
                        throw CancellationException("Cancel after native encoding starts")
                    }
                }
            }
        }.exceptionOrNull()
        assertTrue("Must reach intermediate native progress", cancelledDuringEncoding)
        assertTrue("Cancellation must propagate", error is CancellationException)
        assertEquals(outputBefore, files(outputRoot))
        assertEquals(cacheBefore, files(context.cacheDir))
    }

    @Test
    fun cancellingCoroutineDuringEncodingCleansUpBeforeReturning() = runBlocking {
        val outputBefore = files(outputRoot)
        val cacheBefore = files(context.cacheDir)
        val encoding = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            processor.process(input("cancel.mp4"), GifOptions(sampleIntervalMs = 50)) { completed, total, _ ->
                if (total == 1000 && completed in 451 until 990) encoding.complete(Unit)
            }
        }
        try {
            withTimeout(30_000) { encoding.await() }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(outputBefore, files(outputRoot))
            assertEquals(cacheBefore, files(context.cacheDir))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun sharingVideoOpensConfigurationAndProducesReviewableGif(): Unit = runBlocking {
        val preferences = OutputStoragePreferences(context)
        val originalSettings = preferences.read()
        val outputs = mutableListOf<OutputArtifact>()
        var scenario: ActivityScenario<MainActivity>? = null
        var viewModel: NatsukoViewModel? = null
        preferences.setEnabled(false)
        try {
            val source = input("av1.mp4")
            val intent = Intent(Intent.ACTION_SEND)
                .setComponent(ComponentName(context, "com.poppinzhang.natsuko.share.VideoToGifShare"))
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, source.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("Shared video", source.uri) }
            val launched = ActivityScenario.launch<MainActivity>(intent)
            scenario = launched
            launched.onActivity { viewModel = ViewModelProvider(it)[NatsukoViewModel::class.java] }
            val model = checkNotNull(viewModel)
            val configured = withTimeout(10_000) { model.state.first { it.screen == AppScreen.CONFIGURE } }
            assertEquals(Feature.VIDEO_TO_GIF, configured.feature)
            assertEquals(listOf(source.uri), configured.inputs.map { it.uri })
            assertFalse(configured.isProcessing)
            launched.onActivity { model.process() }
            val completed = withTimeout(30_000) {
                model.state.first { !it.isProcessing && (it.outcome != null || it.errorMessage != null) }
            }
            assertEquals(completed.errorMessage?.resolve(context), AppScreen.RESULT, completed.screen)
            val outcome = checkNotNull(completed.outcome)
            outputs += outcome.outputs
            assertTrue(outcome.failures.isEmpty())
            assertEquals(10, GifStructure.read(outcome.outputs.single().file).delays.size)
            launched.recreate()
            launched.onActivity {
                assertEquals(outcome, ViewModelProvider(it)[NatsukoViewModel::class.java].state.value.outcome)
            }
        } finally {
            outputs += viewModel?.state?.value?.outcome?.outputs.orEmpty()
            scenario?.close()
            outputs.map { it.file }.distinct().forEach(File::delete)
            preferences.setDirectory(originalSettings.treeUri, originalSettings.directoryLabel)
            preferences.setEnabled(originalSettings.enabled)
        }
    }

    private fun input(name: String): InputItem {
        val uri = Uri.parse("content://${VideoTestProvider.AUTHORITY}/$name")
        return InputItem(uri, name, context.contentResolver.getType(uri))
    }

    private fun files(directory: File): Set<String> = directory.walkTopDown()
        .filter(File::isFile).map(File::getAbsolutePath).toSet()
}

/** Reads actual GIF records, including local palettes and optimized image rectangles. */
private data class GifStructure(val width: Int, val height: Int, val delays: List<Int>, val loopCount: Int?) {
    companion object {
        fun read(file: File): GifStructure {
            val bytes = file.readBytes()
            fun byte(index: Int) = bytes[index].toInt() and 255
            fun short(index: Int) = byte(index) or (byte(index + 1) shl 8)
            check(String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
            val width = short(6)
            val height = short(8)
            var position = 13
            if (byte(10) and 128 != 0) position += 3 * (1 shl ((byte(10) and 7) + 1))
            val delays = mutableListOf<Int>()
            var delay = 0
            var loops: Int? = null
            fun blocks(): ByteArray {
                val result = java.io.ByteArrayOutputStream()
                while (true) {
                    val size = byte(position++)
                    if (size == 0) return result.toByteArray()
                    result.write(bytes, position, size)
                    position += size
                }
            }
            while (true) {
                when (val marker = byte(position++)) {
                    0x21 -> {
                        val label = byte(position++)
                        val data = blocks()
                        if (label == 0xF9) {
                            check(data.size == 4)
                            delay = (data[1].toInt() and 255) or ((data[2].toInt() and 255) shl 8)
                        }
                        if (label == 0xFF && data.size >= 14 &&
                            String(data, 0, 11, Charsets.US_ASCII) == "NETSCAPE2.0") {
                            loops = (data[12].toInt() and 255) or ((data[13].toInt() and 255) shl 8)
                        }
                    }
                    0x2C -> {
                        position += 8
                        val flags = byte(position++)
                        if (flags and 128 != 0) position += 3 * (1 shl ((flags and 7) + 1))
                        position++ // LZW minimum code size.
                        blocks()
                        delays += delay
                        delay = 0
                    }
                    0x3B -> {
                        check(position == bytes.size) { "Unexpected bytes after GIF trailer" }
                        return GifStructure(width, height, delays, loops)
                    }
                    else -> error("Invalid GIF record: $marker")
                }
            }
        }
    }
}
