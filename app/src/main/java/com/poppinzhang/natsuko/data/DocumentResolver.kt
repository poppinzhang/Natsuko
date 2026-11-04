package com.poppinzhang.natsuko.data

import com.poppinzhang.natsuko.R
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.poppinzhang.natsuko.model.InputItem

class DocumentResolver(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun resolve(uri: Uri): InputItem {
        var name: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index)
                }
            }
        }
        return InputItem(
            uri = uri,
            displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: context.getString(R.string.backend_unnamed_file),
            mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
        )
    }

    fun resolveAll(uris: List<Uri>): List<InputItem> = uris.distinct().map(::resolve)
}
