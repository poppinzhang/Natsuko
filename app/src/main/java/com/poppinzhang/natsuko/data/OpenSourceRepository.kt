package com.poppinzhang.natsuko.data

import android.content.Context
import androidx.annotation.StringRes
import com.poppinzhang.natsuko.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class OpenSourceProject(
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    val description: String,
    val licenseFiles: List<String>,
) {
    // Curated summaries are UI copy; original license and notice text stays in the asset bundle.
    @get:StringRes
    val descriptionRes: Int?
        get() = when (id) {
            "natsuko" -> R.string.about_project_natsuko_description
            "ffmpeg" -> R.string.about_project_ffmpeg_description
            "dav1d" -> R.string.about_project_dav1d_description
            "llvm" -> R.string.about_project_llvm_description
            else -> null
        }
}

data class OpenSourceNotice(val filename: String, val text: String)

/** Reads the notice bundle shipped in the APK; viewing licenses never requires a network request. */
class OpenSourceRepository(context: Context) {
    private val assets = context.applicationContext.assets

    suspend fun projects(): List<OpenSourceProject> = withContext(Dispatchers.IO) {
        val json = assets.open("open_source/index.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseProjects(json)
    }

    suspend fun notices(project: OpenSourceProject): List<OpenSourceNotice> = withContext(Dispatchers.IO) {
        project.licenseFiles.map { path ->
            OpenSourceNotice(
                filename = path.substringAfterLast('/'),
                text = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() },
            )
        }
    }

    internal companion object {
        fun parseProjects(json: String): List<OpenSourceProject> {
            val entries = JSONObject(json).getJSONArray("projects")
            val projects = List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                val files = entry.getJSONArray("licenseFiles")
                OpenSourceProject(
                    id = entry.getString("id"),
                    name = entry.getString("name"),
                    version = entry.getString("version"),
                    license = entry.getString("license"),
                    url = entry.getString("url"),
                    description = entry.optString("description"),
                    licenseFiles = List(files.length()) { files.getString(it) },
                ).also { project ->
                    require(project.id.isNotBlank() && project.name.isNotBlank() && project.license.isNotBlank())
                    require(project.licenseFiles.isNotEmpty())
                    require(project.licenseFiles.all { path ->
                        path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." }
                    })
                }
            }
            require(projects.isNotEmpty() && projects.map { it.id }.distinct().size == projects.size)
            return projects
        }
    }
}
