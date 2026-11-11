package com.poppinzhang.natsuko.processing.location

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationMetadataEngineTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val engine = LocationMetadataEngine(context)

    @Test fun embeddedBackendCopiesCoordinatesAndPlaceNamesForEveryContainer() = runBlocking {
        val directory = File(context.cacheDir, "location-formats-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = fixture("source.jpg", directory)
            val sourceBytes = source.readBytes()
            for (extension in listOf("jpg", "png", "webp", "heic", "tif", "arw", "cr2", "cr3", "nef", "raf", "rw2")) {
                val target = fixture("target.$extension", directory)
                engine.copy(source, target)
                val result = engine.read(target)!!
                assertTrue(extension, result.hasCoordinates)
                assertEquals(extension, 35.6717, result.latitude!!, 0.0000001)
                assertEquals(extension, 139.6949, result.longitude!!, 0.0000001)
                assertTrue(extension, result.displayText.contains("Tokyo"))
                assertTrue(extension, result.displayText.contains("代々木公園"))
                assertTrue(extension, result.displayText.contains("Japan"))
                assertFalse(extension, result.displayText.contains("Kingston"))
                assertFalse(extension, result.displayText.contains("Canada"))
            }
            assertArrayEquals(sourceBytes, source.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingLocationInvalidContentAndMisleadingExtensionsAreDistinguished() = runBlocking {
        val directory = File(context.cacheDir, "location-invalid-${System.nanoTime()}").apply { mkdirs() }
        try {
            val noLocation = File(directory, "screenshot.png")
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            noLocation.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertNull(engine.read(noLocation))
            val invalid = File(directory, "broken.jpg").apply { writeText("not an image") }
            assertTrue(runCatching { engine.read(invalid) }.isFailure)
            val disguised = File(directory, "wrong.png")
            fixture("source.jpg", directory).copyTo(disguised)
            assertTrue(runCatching { engine.read(disguised) }.isFailure)
            assertTrue(runCatching { engine.copy(noLocation, disguised) }.isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test fun namedOnlyConflictsStructuredCoordinatesAndIptcNamesAreSupported() = runBlocking {
        val directory = File(context.cacheDir, "location-names-${System.nanoTime()}").apply { mkdirs() }
        try {
            val named = engine.read(fixture("named-only.jpg", directory))!!
            assertFalse(named.hasCoordinates)
            assertTrue(named.displayText.contains("代々木公園"))
            val structured = fixture("structured.jpg", directory)
            val coordinates = engine.read(structured)!!
            assertEquals(-33.86, coordinates.latitude!!, 0.0000001)
            assertEquals(151.21, coordinates.longitude!!, 0.0000001)
            val target = fixture("target.webp", directory)
            engine.copy(structured, target)
            assertTrue(engine.read(target)!!.displayText.contains("Sydney Harbour"))
            engine.copy(fixture("iptc-only.jpg", directory), target)
            val iptc = engine.read(target)!!
            assertTrue(iptc.displayText.contains("Tokyo"))
            assertTrue(iptc.displayText.contains("Japan"))
            assertFalse(iptc.displayText.contains("Sydney Harbour"))
        } finally { directory.deleteRecursively() }
    }

    private fun fixture(name: String, directory: File): File = File(directory, name).also { output ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("location/$name").use { input ->
            output.outputStream().use { input.copyTo(it) }
        }
    }
}
