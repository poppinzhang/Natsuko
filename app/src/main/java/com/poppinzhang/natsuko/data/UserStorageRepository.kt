package com.poppinzhang.natsuko.data

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.checkNotNullText
import com.poppinzhang.natsuko.model.LocalizedIOException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.SavedOutput
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class UserStorageExportResult(
    val outputs: List<OutputArtifact>,
    val failures: List<ProcessingFailure> = emptyList(),
)

/** Publishes completed private outputs without removing the originals needed for retry. */
class UserStorageRepository(context: Context) {
    private val context = context.applicationContext
    private val resolver = context.contentResolver

    fun hasDirectoryPermission(settings: OutputStorageSettings): Boolean {
        val treeUri = settings.treeUri ?: return true
        return runCatching {
            resolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            }
        }.getOrDefault(false)
    }

    fun retainDirectoryPermission(uri: Uri): String {
        requireText(DocumentsContract.isTreeUri(uri)) { text(R.string.backend_select_directory) }
        checkText(isWritableDirectory(treeDocumentUri(uri))) { text(R.string.backend_directory_not_writable) }
        val label = storageDirectoryLabel(context, uri)
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        checkText(hasDirectoryPermission(OutputStorageSettings(treeUri = uri))) {
            text(R.string.backend_directory_permission_not_retained)
        }
        return label
    }

    suspend fun export(
        outputs: List<OutputArtifact>,
        settings: OutputStorageSettings,
        onProgress: (Int, Int, UiText) -> Unit,
    ): UserStorageExportResult = withContext(Dispatchers.IO) {
        if (!settings.enabled) return@withContext UserStorageExportResult(outputs)
        val exported = ArrayList<OutputArtifact>(outputs.size)
        val failures = mutableListOf<ProcessingFailure>()
        val directories = mutableMapOf<String, Uri>()
        outputs.forEachIndexed { index, output ->
            currentCoroutineContext().ensureActive()
            onProgress(index, outputs.size, text(R.string.backend_saving_output, output.label))
            if (output.userStorage?.destinationKey == settings.destinationKey) {
                exported += output
            } else {
                try {
                    checkText(output.file.isFile) { text(R.string.backend_output_missing) }
                    val subdirectory = outputFeatureDirectory(output.file)
                    val savedUri = if (settings.treeUri == null) {
                        saveToDownloads(output, subdirectory)
                    } else {
                        checkText(hasDirectoryPermission(settings)) { text(R.string.backend_directory_permission_expired) }
                        val directory = directories.getOrPut(subdirectory) {
                            checkText(isWritableDirectory(treeDocumentUri(settings.treeUri))) {
                                text(R.string.backend_directory_not_writable_settings)
                            }
                            findOrCreateDirectory(treeDocumentUri(settings.treeUri), subdirectory)
                        }
                        saveToDocumentDirectory(output, directory)
                    }
                    exported += output.copy(
                        userStorage = SavedOutput(
                            uri = savedUri,
                            directoryLabel = "${settings.directoryLabel}/$subdirectory",
                            destinationKey = settings.destinationKey,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    exported += output
                    failures += ProcessingFailure(
                        source = output.label,
                        reason = text(R.string.backend_save_storage_error, error.userMessage(R.string.backend_write_file_failed)),
                    )
                }
            }
            onProgress(index + 1, outputs.size, text(R.string.backend_outputs_processed, index + 1, outputs.size))
        }
        UserStorageExportResult(exported, failures)
    }

    private suspend fun saveToDownloads(output: OutputArtifact, subdirectory: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, output.file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, output.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${OutputStorageSettings.DEFAULT_DIRECTORY}/$subdirectory/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw LocalizedIOException(text(R.string.backend_download_file_create_failed))
        var complete = false
        try {
            copy(output.file, uri)
            currentCoroutineContext().ensureActive()
            checkText(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                text(R.string.backend_download_file_finish_failed)
            }
            complete = true
            return uri
        } finally {
            if (!complete) runCatching { resolver.delete(uri, null, null) }
        }
    }

    private suspend fun saveToDocumentDirectory(output: OutputArtifact, directory: Uri): Uri {
        val uri = DocumentsContract.createDocument(resolver, directory, output.mimeType, output.file.name)
            ?: throw LocalizedIOException(text(R.string.backend_directory_file_create_failed))
        var complete = false
        try {
            copy(output.file, uri)
            currentCoroutineContext().ensureActive()
            complete = true
            return uri
        } finally {
            if (!complete) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }

    private suspend fun copy(file: File, destination: Uri) {
        file.inputStream().use { input ->
            val output: OutputStream = resolver.openOutputStream(destination, "w")
                ?: throw LocalizedIOException(text(R.string.backend_output_open_failed))
            output.use {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    it.write(buffer, 0, count)
                }
                it.flush()
            }
        }
    }

    private fun isWritableDirectory(uri: Uri): Boolean = resolver.query(
        uri,
        arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS),
        null,
        null,
        null,
    )?.use { cursor ->
        cursor.moveToFirst() && cursor.getString(0) == Document.MIME_TYPE_DIR &&
            cursor.getLong(1) and Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L
    } ?: false

    private fun findOrCreateDirectory(parent: Uri, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name && cursor.getString(2) == Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
            ?: throw LocalizedIOException(text(R.string.backend_directory_create_failed, name))
    }

    private fun treeDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun outputFeatureDirectory(file: File): String {
        val run = checkNotNullText(file.parentFile) { text(R.string.backend_output_directory_unknown) }
        val feature = checkNotNullText(run.parentFile) { text(R.string.backend_feature_directory_unknown) }
        // Run directories isolate private processing and cleanup; user storage groups only by feature.
        return safeDirectoryName(feature.name)
    }

    private fun safeDirectoryName(name: String): String = name
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
        .trim('_', '.')
        .take(100)
        .ifBlank { "output" }
}
