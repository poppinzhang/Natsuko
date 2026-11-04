package com.poppinzhang.natsuko.data

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.checkText
import android.content.Context
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.poppinzhang.natsuko.BuildConfig
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OutputRepository(private val context: Context) {
    fun createRunDirectory(feature: Feature, documentOutput: Boolean = false): File {
        val type = if (documentOutput) Environment.DIRECTORY_DOCUMENTS else Environment.DIRECTORY_PICTURES
        val externalRoot = context.getExternalFilesDir(type)
        val root = externalRoot ?: File(context.filesDir, type)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(root, "Natsuko/${feature.folderName}/$stamp").also { directory ->
            checkText(directory.mkdirs() || directory.isDirectory) { text(R.string.backend_output_path_create_failed, directory.path) }
        }
    }

    fun shareUri(artifact: OutputArtifact): android.net.Uri {
        artifact.userStorage?.let { saved ->
            val readable = !DocumentsContract.isTreeUri(saved.uri) || runCatching {
                context.contentResolver.persistedUriPermissions.any {
                    it.uri.toString() == saved.destinationKey && it.isReadPermission
                }
            }.getOrDefault(false)
            if (readable) return saved.uri
        }
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", artifact.file)
    }

    companion object {
        fun safeStem(name: String): String {
            val withoutExtension = name.substringBeforeLast('.', name)
            return withoutExtension
                .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
                .trim('_', '.')
                .take(80)
                .ifBlank { "output" }
        }
    }
}
