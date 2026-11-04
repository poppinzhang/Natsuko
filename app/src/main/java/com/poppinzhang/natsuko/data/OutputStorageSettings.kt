package com.poppinzhang.natsuko.data

import com.poppinzhang.natsuko.R
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit

data class OutputStorageSettings(
    val enabled: Boolean = false,
    val treeUri: Uri? = null,
    val directoryLabel: String = DEFAULT_DIRECTORY,
) {
    val destinationKey: String
        get() = treeUri?.toString() ?: "downloads:Natsuko"

    companion object {
        const val DEFAULT_DIRECTORY = "Download/Natsuko"
    }
}

class OutputStoragePreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences("output_storage", Context.MODE_PRIVATE)

    fun read(): OutputStorageSettings {
        val uri = preferences.getString("tree_uri", null)?.let(Uri::parse)
        return OutputStorageSettings(
            enabled = preferences.getBoolean("enabled", false),
            treeUri = uri,
            directoryLabel = if (uri == null) OutputStorageSettings.DEFAULT_DIRECTORY else
                preferences.getString("directory_label", null) ?: uri.toString(),
        )
    }

    fun setEnabled(enabled: Boolean): OutputStorageSettings {
        preferences.edit { putBoolean("enabled", enabled) }
        return read()
    }

    /** The caller validates and retains the directory grant before saving this setting. */
    fun setDirectory(uri: Uri?, directoryLabel: String? = null): OutputStorageSettings {
        val label = if (uri == null) OutputStorageSettings.DEFAULT_DIRECTORY else
            directoryLabel ?: DocumentsContract.getTreeDocumentId(uri)
        preferences.edit {
            putString("tree_uri", uri?.toString())
            putString("directory_label", label)
        }
        return read()
    }

    /** Keep the returned subscription until the observer is no longer needed, then invoke it. */
    fun observe(onChanged: (OutputStorageSettings) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChanged(read()) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

internal fun storageDirectoryLabel(context: Context, treeUri: Uri): String {
    val documentId = DocumentsContract.getTreeDocumentId(treeUri)
    if (treeUri.authority == "com.android.externalstorage.documents" && documentId.startsWith("primary:")) {
        return documentId.substringAfter(':').ifBlank { context.getString(R.string.backend_internal_storage) }
    }
    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    return context.contentResolver.query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
    } ?: documentId
}
