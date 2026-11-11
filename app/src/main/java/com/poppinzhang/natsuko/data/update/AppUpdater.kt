package com.poppinzhang.natsuko.data.update

import android.content.Context
import android.content.Intent
import com.poppinzhang.natsuko.BuildConfig
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.text
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Updates this installation from the app's published releases; nothing runs without a user request. */
interface AppUpdater {
    /** The newer published release, or null when the installed version is current. */
    suspend fun check(): AppRelease?

    /** Downloads and verifies the release package, reporting the bytes received so far. */
    suspend fun download(release: AppRelease, onBytes: (Long) -> Unit): File

    /**
     * Hands the package to Android. [onUserAction] receives the system confirmation to show when
     * Android requires one. Returns false when the user declines it; a completed update replaces
     * this process, so a true result is rarely observed.
     */
    suspend fun install(apk: File, onUserAction: (Intent) -> Unit): Boolean
}

class GitHubAppUpdater internal constructor(
    private val http: UpdateHttpClient,
    private val directory: File,
    private val installed: ReleaseVersion,
    private val installPackage: suspend (File, (Intent) -> Unit) -> Boolean,
) : AppUpdater {
    constructor(context: Context) : this(
        UpdateHttpClient(),
        File(context.cacheDir, "update"),
        checkNotNull(ReleaseVersion.fromVersionName(BuildConfig.VERSION_NAME)) { "Unsupported version name" },
        AppUpdateInstaller(context)::install,
    )

    override suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val release = GitHubReleases.findUpdate(http.latestRelease(), installed)
        // Nothing newer is published, so a package kept from an earlier update is obsolete.
        if (release == null) directory.deleteRecursively()
        release
    }

    override suspend fun download(release: AppRelease, onBytes: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val apk = release.apk
        val target = File(directory, "natsuko-${release.version}.apk")
        checkText(directory.mkdirs() || directory.isDirectory) { text(R.string.backend_network_storage_failed) }
        directory.listFiles()?.filter { it != target }?.forEach { it.deleteRecursively() }
        // A package that already passed verification survives a declined or interrupted installation.
        if (apk.sha256 != null && target.length() == apk.size && sha256(target) == apk.sha256) {
            onBytes(apk.size)
            return@withContext target
        }
        val partial = File(directory, "${target.name}.part")
        try {
            http.download(apk.url, apk.size, partial, onBytes)
            checkText(apk.sha256 == null || sha256(partial) == apk.sha256) { text(R.string.update_error_checksum) }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
        target
    }

    override suspend fun install(apk: File, onUserAction: (Intent) -> Unit): Boolean = try {
        installPackage(apk, onUserAction)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // A rejected package would be rejected again; a declined or cancelled one is kept for the next attempt.
        apk.delete()
        throw error
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
