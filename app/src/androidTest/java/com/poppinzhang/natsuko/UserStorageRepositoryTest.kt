package com.poppinzhang.natsuko

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.data.OutputStorageSettings
import com.poppinzhang.natsuko.data.UserStorageRepository
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserStorageRepositoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val repository = UserStorageRepository(context)
    private val preferences = OutputStoragePreferences(context)
    private val downloads = mutableListOf<Uri>()
    private val sources = mutableListOf<File>()
    private val treeUri = DocumentsContract.buildTreeDocumentUri(StorageTestDocumentsProvider.AUTHORITY, "root")
    private lateinit var originalSettings: OutputStorageSettings

    @Before
    fun rememberPreferences() {
        originalSettings = preferences.read()
        preferences.setEnabled(false)
        preferences.setDirectory(null)
    }

    @After
    fun cleanUp(): Unit = runBlocking {
        downloads.forEach { runCatching { resolver.delete(it, null, null) } }
        sources.forEach(File::deleteRecursively)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (hasTemporaryGrant()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "root")
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    runCatching { DocumentsContract.deleteDocument(resolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))) }
                }
            }
        }
        runCatching { resolver.releasePersistableUriPermission(treeUri, flags) }
        if (hasTemporaryGrant()) {
            launchGrantActivity(revoke = true)
            withTimeout(10_000) { while (hasTemporaryGrant()) delay(10) }
        }
        preferences.setDirectory(originalSettings.treeUri, originalSettings.directoryLabel)
        preferences.setEnabled(originalSettings.enabled)
    }

    @Test
    fun settingsPersistSharedToggleAndConfiguredDirectory() {
        assertEquals(OutputStorageSettings(), preferences.read())
        preferences.setEnabled(true)
        preferences.setDirectory(treeUri, "Custom output")
        val reloaded = OutputStoragePreferences(context).read()
        assertTrue(reloaded.enabled)
        assertEquals(treeUri, reloaded.treeUri)
        assertEquals("Custom output", reloaded.directoryLabel)
        assertEquals(treeUri.toString(), reloaded.destinationKey)
        preferences.setEnabled(false)
        assertEquals(treeUri, preferences.read().treeUri)
        assertEquals(OutputStorageSettings(), preferences.setDirectory(null))
    }

    @Test
    fun settingsObserversSeeOtherInstancesAndCanUnsubscribe() {
        val observed = mutableListOf<OutputStorageSettings>()
        instrumentation.runOnMainSync {
            val unsubscribe = preferences.observe(observed::add)
            OutputStoragePreferences(context).setEnabled(true)
            assertTrue(observed.last().enabled)
            unsubscribe()
            val before = observed.size
            OutputStoragePreferences(context).setEnabled(false)
            assertEquals(before, observed.size)
        }
    }

    @Test
    fun downloadsBatchPublishesPngPdfAndGifWithOriginalContents() = runBlocking {
        val inputs = sampleOutputs()
        val progress = mutableListOf<Int>()
        val result = repository.export(inputs, OutputStorageSettings(enabled = true)) { completed, _, _ -> progress += completed }
        downloads += result.outputs.mapNotNull { it.userStorage?.uri }

        assertTrue(result.failures.isEmpty())
        assertEquals(3, downloads.size)
        assertEquals(3, progress.last())
        result.outputs.forEach { output ->
            val saved = checkNotNull(output.userStorage)
            assertEquals("Download/Natsuko/ImageMerge", saved.directoryLabel)
            assertArrayEquals(output.file.readBytes(), resolver.openInputStream(saved.uri)!!.use { it.readBytes() })
            assertEquals(saved.uri, OutputRepository(context).shareUri(output))
            resolver.query(saved.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(saved.directoryLabel + "/", cursor.getString(1))
                assertEquals(output.mimeType, cursor.getString(2))
            }
        }
        assertTrue(inputs.all { it.file.isFile })
    }

    @Test
    fun downloadsExportsEveryFeatureDirectlyIntoItsFeatureDirectory() = runBlocking {
        val inputs = Feature.entries.map { sampleOutputs(it).first() }
        val result = repository.export(inputs, OutputStorageSettings(enabled = true)) { _, _, _ -> }
        downloads += result.outputs.mapNotNull { it.userStorage?.uri }

        assertTrue(result.failures.isEmpty())
        Feature.entries.zip(result.outputs).forEach { (feature, output) ->
            val saved = checkNotNull(output.userStorage)
            assertEquals("Download/Natsuko/${feature.folderName}", saved.directoryLabel)
            resolver.query(saved.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Download/Natsuko/${feature.folderName}/", cursor.getString(0))
            }
            assertArrayEquals(output.file.readBytes(), resolver.openInputStream(saved.uri)!!.use { it.readBytes() })
        }
    }

    @Test
    fun safExportsEveryFeatureDirectlyIntoItsFeatureDirectory() = runBlocking {
        val settings = grantTestDirectory()
        val inputs = Feature.entries.map { sampleOutputs(it).first() }
        val result = repository.export(inputs, settings) { _, _, _ -> }

        assertTrue(result.failures.isEmpty())
        Feature.entries.zip(result.outputs).forEach { (feature, output) ->
            val saved = checkNotNull(output.userStorage)
            assertEquals("Storage tests/${feature.folderName}", saved.directoryLabel)
            assertEquals("root/${feature.folderName}/${output.file.name}", DocumentsContract.getDocumentId(saved.uri))
            assertEquals(listOf(output.file.name), listSavedNames(output))
            assertArrayEquals(output.file.readBytes(), resolver.openInputStream(saved.uri)!!.use { it.readBytes() })
        }
    }

    @Test
    fun repeatedNamesFromDifferentRunsPreserveBothFilesAndRetryDoesNotDuplicateThem() = runBlocking {
        val first = sampleOutputs(Feature.CROP_BLACK_BORDER).first()
        val firstRun = checkNotNull(first.file.parentFile)
        val nextRun = File(firstRun.parentFile, "${firstRun.name}-next").apply { mkdirs() }.also(sources::add)
        val second = first.copy(file = File(nextRun, first.file.name).apply {
            writeBytes(first.file.readBytes() + byteArrayOf(1))
        })
        val customDirectory = grantTestDirectory()

        for (settings in listOf(OutputStorageSettings(enabled = true), customDirectory)) {
            val firstResult = repository.export(listOf(first), settings) { _, _, _ -> }
            if (settings.treeUri == null) downloads += firstResult.outputs.mapNotNull { it.userStorage?.uri }
            val secondResult = repository.export(listOf(second), settings) { _, _, _ -> }
            if (settings.treeUri == null) downloads += secondResult.outputs.mapNotNull { it.userStorage?.uri }
            assertTrue(firstResult.failures.isEmpty())
            assertTrue(secondResult.failures.isEmpty())
            val exported = firstResult.outputs + secondResult.outputs
            assertEquals(2, exported.map { checkNotNull(it.userStorage).uri }.distinct().size)
            exported.forEach { output ->
                val saved = checkNotNull(output.userStorage)
                assertEquals("${settings.directoryLabel}/CropBlackBorder", saved.directoryLabel)
                assertArrayEquals(output.file.readBytes(), resolver.openInputStream(saved.uri)!!.use { it.readBytes() })
            }
            val retried = repository.export(exported, settings) { _, _, _ -> }
            assertTrue(retried.failures.isEmpty())
            assertEquals(exported, retried.outputs)
            if (settings.treeUri != null) assertEquals(2, listSavedNames(first).size)
        }
        assertTrue(first.file.isFile)
        assertTrue(second.file.isFile)
    }

    @Test
    fun disabledSettingKeepsPrivateOutputsWithoutPublishing() = runBlocking {
        val inputs = sampleOutputs()
        val result = repository.export(inputs, OutputStorageSettings()) { _, _, _ -> }
        assertEquals(inputs, result.outputs)
        assertTrue(result.failures.isEmpty())
        assertTrue(result.outputs.all { it.userStorage == null })
    }

    @Test
    fun failedFileCanBeRetriedWithoutDuplicatingSuccessfulDownloads() = runBlocking {
        val inputs = sampleOutputs().take(1)
        val absent = File(inputs.single().file.parentFile, "missing.pdf")
        val artifacts = inputs + OutputArtifact(absent, "application/pdf", "Missing PDF")
        val settings = OutputStorageSettings(enabled = true)
        val first = repository.export(artifacts, settings) { _, _, _ -> }
        downloads += first.outputs.mapNotNull { it.userStorage?.uri }
        assertEquals(1, first.failures.size)
        assertEquals("Missing PDF", first.failures.single().source.resolve(context))
        assertNull(first.outputs.last().userStorage)
        absent.writeText("Retry source")
        val retried = repository.export(first.outputs, settings) { _, _, _ -> }
        downloads += retried.outputs.mapNotNull { it.userStorage?.uri }.filterNot(downloads::contains)
        assertTrue(retried.failures.isEmpty())
        assertEquals(first.outputs.first().userStorage, retried.outputs.first().userStorage)
        assertNotNull(retried.outputs.last().userStorage)
        val unchanged = repository.export(retried.outputs, settings) { _, _, _ -> }
        assertEquals(retried.outputs, unchanged.outputs)
        assertEquals(2, downloads.size)
    }

    @Test
    fun safDirectoryGrantPersistsAndFailedWritesRemoveIncompleteDocuments() = runBlocking {
        val settings = grantTestDirectory()
        assertTrue(repository.hasDirectoryPermission(settings))
        val source = sampleOutputs().first()
        val rejected = File(source.file.parentFile, "fail-write.png").apply { writeBytes(source.file.readBytes()) }
        val result = repository.export(listOf(source, OutputArtifact(rejected, "image/png", "Rejected PNG")), settings) { _, _, _ -> }
        assertEquals(result.failures.joinToString(), 1, result.failures.size)
        val saved = checkNotNull(result.outputs.first().userStorage)
        assertEquals("Storage tests/ImageMerge", saved.directoryLabel)
        assertArrayEquals(source.file.readBytes(), resolver.openInputStream(saved.uri)!!.use { it.readBytes() })
        assertEquals(listOf(source.file.name), listSavedNames(source))
        assertTrue(rejected.isFile)

        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(repository.hasDirectoryPermission(settings))
        assertEquals(context.packageName + ".files", OutputRepository(context).shareUri(result.outputs.first()).authority)
    }

    @Test
    fun cancellationRemovesOnlyIncompleteDocumentAndKeepsCompletedFiles() = runBlocking {
        val settings = grantTestDirectory()
        val complete = sampleOutputs().first()
        val slowFile = File(complete.file.parentFile, "slow-copy.gif").apply { writeBytes(ByteArray(1024 * 1024)) }
        val slow = OutputArtifact(slowFile, "image/gif", "Slow GIF")
        val job = launch(Dispatchers.IO) {
            repository.export(listOf(complete, slow), settings) { _, _, _ -> }
        }
        withTimeout(10_000) {
            while (runCatching { listSavedNames(complete).contains(slowFile.name) }.getOrDefault(false).not()) delay(10)
        }
        job.cancelAndJoin()
        assertEquals(listOf(complete.file.name), listSavedNames(complete))
        assertTrue(complete.file.isFile)
        assertTrue(slow.file.isFile)
    }

    @Test
    fun missingDirectoryPermissionReportsEachFileAndKeepsOriginals() = runBlocking {
        val inputs = sampleOutputs()
        val settings = OutputStorageSettings(enabled = true, treeUri = treeUri, directoryLabel = "Unavailable")
        assertFalse(repository.hasDirectoryPermission(settings))
        val result = repository.export(inputs, settings) { _, _, _ -> }
        assertEquals(inputs, result.outputs)
        assertEquals(inputs.size, result.failures.size)
        assertTrue(inputs.all { it.file.isFile })
    }

    private suspend fun grantTestDirectory(): OutputStorageSettings {
        launchGrantActivity(revoke = false)
        withTimeout(10_000) { while (!hasTemporaryGrant()) delay(10) }
        val label = repository.retainDirectoryPermission(treeUri)
        assertEquals("Storage tests", label)
        preferences.setDirectory(treeUri, label)
        return preferences.setEnabled(true)
    }

    private fun launchGrantActivity(revoke: Boolean) {
        context.startActivity(Intent()
            .setClassName(instrumentation.context.packageName, StorageTestGrantActivity::class.java.name)
            .putExtra("target_package", context.packageName)
            .putExtra("revoke", revoke)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun hasTemporaryGrant(): Boolean = context.checkUriPermission(treeUri, Process.myPid(), Process.myUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun listSavedNames(source: OutputArtifact): List<String> {
        val run = checkNotNull(source.file.parentFile)
        val id = "root/${checkNotNull(run.parentFile).name}"
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun sampleOutputs(feature: Feature = Feature.IMAGE_MERGE): List<OutputArtifact> {
        val directory = OutputRepository(context).createRunDirectory(feature).also(sources::add)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val png = File(directory, "sample.png").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val pdf = File(directory, "sample.pdf").apply {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(8, 8, 1).create())
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                document.finishPage(page)
                outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
        }
        val gif = File(directory, "sample.gif").apply {
            instrumentation.context.assets.open("video/sample.gif").use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
        bitmap.recycle()
        return listOf(OutputArtifact(png, "image/png", "PNG"), OutputArtifact(pdf, "application/pdf", "PDF"),
            OutputArtifact(gif, "image/gif", "GIF"))
    }
}
