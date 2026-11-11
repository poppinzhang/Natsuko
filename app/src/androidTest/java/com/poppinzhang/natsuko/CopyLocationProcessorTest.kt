package com.poppinzhang.natsuko

import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.processing.location.CopyLocationProcessor
import com.poppinzhang.natsuko.processing.location.LocationConflict
import com.poppinzhang.natsuko.processing.location.LocationConflictDecision
import com.poppinzhang.natsuko.processing.location.LocationMetadata
import com.poppinzhang.natsuko.processing.location.LocationSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Batch policy and storage tests inject metadata operations; the engine has format-specific tests. */
@RunWith(AndroidJUnit4::class)
class CopyLocationProcessorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureDirectory = File(context.filesDir, "location-policy-${UUID.randomUUID()}").apply { mkdirs() }
    private val outputRoot = File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, Environment.DIRECTORY_PICTURES),
        "Natsuko/CopyLocation",
    )
    private val initialDirectories = outputRoot.listFiles().orEmpty().toSet()
    private val sourceCache = File(context.cacheDir, "copy-location")
    private val initialSourceCache = sourceCache.listFiles().orEmpty().toSet()
    private val copied = mutableListOf<File>()
    private val sourceMetadata = LocationMetadata("35.0, 139.0 · Tokyo", true, 35.0, 139.0)
    private val processor = CopyLocationProcessor(
        context,
        OutputRepository(context),
        readMetadata = { file ->
            when (file.readText()) {
                "source" -> sourceMetadata
                "located" -> LocationMetadata("34.0, 135.0", true, 34.0, 135.0)
                "names-only" -> LocationMetadata("Osaka", false)
                "corrupt" -> error("Corrupt image")
                else -> null
            }
        },
        copyMetadata = { _, target ->
            copied += target
            target.appendText("|location-copied")
        },
    )

    @After
    fun cleanUp() {
        fixtureDirectory.deleteRecursively()
        outputRoot.listFiles().orEmpty().filterNot { it in initialDirectories }.forEach { it.deleteRecursively() }
        sourceCache.listFiles().orEmpty().filterNot { it in initialSourceCache }.forEach { it.delete() }
    }

    @Test
    fun singleImportCreatesAppCopyAndKeepsOriginalBytes() = runBlocking {
        val source = processor.importSource(input("source.jpg", "source"))
        val target = input("target.jpg", "pixels")
        val original = File(fixtureDirectory, target.displayName).readBytes()
        val progress = mutableListOf<Int>()
        val outcome = processor.process(source, listOf(target), onConflict = { error("Unexpected conflict") }) { count, total, _ ->
            assertEquals(1, total)
            progress += count
        }

        assertTrue(source.location.hasCoordinates)
        assertTrue(outcome.failures.isEmpty())
        val output = outcome.outputs.single()
        assertEquals("image/jpeg", output.mimeType)
        assertTrue(output.file.canonicalPath.startsWith(outputRoot.canonicalPath + File.separator))
        assertEquals("pixels|location-copied", output.file.readText())
        assertArrayEquals(original, File(fixtureDirectory, target.displayName).readBytes())
        assertEquals(listOf(0, 1), progress)
    }

    @Test
    fun overwriteSkipAndAbortRetainOnlyEarlierCompletedCopies() = runBlocking {
        val targets = listOf(input("one.jpg", "located"), input("two.jpg", "located"), input("three.jpg", "located"), input("four.jpg", "pixels"))
        val decisions = ArrayDeque(listOf(LocationConflictDecision.OVERWRITE, LocationConflictDecision.SKIP, LocationConflictDecision.ABORT))
        val seen = mutableListOf<LocationConflict>()
        val outcome = process(targets) { conflict ->
            seen += conflict
            decisions.removeFirst()
        }

        assertEquals(listOf("one.jpg", "two.jpg", "three.jpg"), seen.map { it.target.displayName })
        assertEquals(listOf("one.jpg"), outcome.outputs.map { it.label.resolve(context) })
        assertEquals(listOf("two.jpg", "three.jpg", "four.jpg"), outcome.skipped.map { it.source.resolve(context) })
        assertTrue(outcome.failures.isEmpty())
        assertEquals(1, copied.size)
        assertEquals(1, outcome.outputs.single().file.parentFile!!.listFiles()!!.size)
        assertEquals(context.getString(R.string.location_skipped), outcome.skipped.first().reason.resolve(context))
        assertTrue(outcome.skipped.drop(1).all { it.reason.resolve(context) == context.getString(R.string.location_aborted) })
    }

    @Test
    fun overwriteAllPromptsOnceAndProcessesEveryTarget() = runBlocking {
        var prompts = 0
        val outcome = process(listOf(input("one.jpg", "located"), input("two.jpg", "names-only"), input("three.jpg", "pixels"))) {
            prompts++
            LocationConflictDecision.OVERWRITE_ALL
        }
        assertEquals(1, prompts)
        assertEquals(3, outcome.outputs.size)
        assertTrue(outcome.failures.isEmpty())
    }

    @Test
    fun skipAllOnlySkipsExistingLocationsAndDoesNotLeakIntoNextBatch() = runBlocking {
        var prompts = 0
        val targets = listOf(input("one.jpg", "located"), input("two.jpg", "pixels"), input("three.jpg", "names-only"))
        val first = process(targets) {
            prompts++
            LocationConflictDecision.SKIP_ALL
        }
        assertEquals(1, prompts)
        assertEquals(listOf("two.jpg"), first.outputs.map { it.label.resolve(context) })
        assertEquals(listOf("one.jpg", "three.jpg"), first.skipped.map { it.source.resolve(context) })
        assertTrue(first.failures.isEmpty())

        val second = process(listOf(targets.first())) {
            prompts++
            LocationConflictDecision.OVERWRITE
        }
        assertEquals(2, prompts)
        assertEquals(1, second.outputs.size)
    }

    @Test
    fun placeNameWithoutCoordinatesStillPromptsBeforeOverwrite() = runBlocking {
        var seen: LocationConflict? = null
        val outcome = process(listOf(input("city.jpg", "names-only"))) {
            seen = it
            LocationConflictDecision.OVERWRITE
        }
        assertNotNull(seen)
        assertFalse(checkNotNull(seen).before.hasCoordinates)
        assertEquals("Osaka", checkNotNull(seen).before.displayText)
        assertEquals(sourceMetadata, checkNotNull(seen).after)
        assertEquals(1, outcome.outputs.size)
    }

    @Test
    fun unreadableUnsupportedAndCorruptTargetsFailIndividually() = runBlocking {
        val missing = item(File(fixtureDirectory, "missing.jpg"))
        val outcome = process(listOf(
            input("unsupported.txt", "pixels", "text/plain"),
            input("bad.jpg", "corrupt"),
            missing,
            input("good.png", "pixels", "image/png"),
        )) { error("Unexpected conflict") }

        assertEquals(listOf("unsupported.txt", "bad.jpg", "missing.jpg"), outcome.failures.map { it.source.resolve(context) })
        assertEquals("good.png", outcome.outputs.single().label.resolve(context))
        assertEquals("image/png", outcome.outputs.single().mimeType)
        assertEquals(1, outcome.outputs.single().file.parentFile!!.listFiles()!!.size)
    }

    @Test
    fun invalidSourceDoesNotLeaveStagedFiles() = runBlocking {
        val initial = sourceCache.listFiles().orEmpty().toSet()
        val failure = runCatching { processor.importSource(input("no-location.jpg", "pixels")) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(initial, sourceCache.listFiles().orEmpty().toSet())
        val namesOnly = runCatching { processor.importSource(input("names-only.jpg", "names-only")) }.exceptionOrNull()
        assertNotNull(namesOnly)
        assertEquals(initial, sourceCache.listFiles().orEmpty().toSet())
    }

    @Test
    fun cancellationRemovesWholeUnpublishedBatchIncludingCompletedFiles() = runBlocking {
        val cancellation = CancellationException("User cancelled after first target")
        val source = source()
        val failure = runCatching {
            processor.process(source, listOf(input("first.jpg", "pixels"), input("second.jpg", "pixels")),
                onConflict = { error("Unexpected conflict") },
                onProgress = { count, _, _ -> if (count == 1) throw cancellation },
            )
        }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertEquals(1, copied.size)
        assertFalse(copied.single().exists())
        assertFalse(copied.single().parentFile!!.exists())
        assertTrue(source.file.exists())
    }

    private suspend fun process(targets: List<InputItem>, conflict: suspend (LocationConflict) -> LocationConflictDecision): ProcessingOutcome =
        processor.process(source(), targets, conflict) { _, _, _ -> }

    private fun source(): LocationSource {
        val sourceFile = File(fixtureDirectory, "source.jpg").apply { writeText("source") }
        return LocationSource(item(sourceFile), sourceFile, sourceMetadata)
    }

    private fun input(name: String, content: String, mime: String = "image/jpeg"): InputItem =
        item(File(fixtureDirectory, name).apply { writeText(content) }, mime)

    private fun item(file: File, mime: String = "image/jpeg") = InputItem(
        FileProvider.getUriForFile(context, "${context.packageName}.files", file), file.name, mime,
    )
}
