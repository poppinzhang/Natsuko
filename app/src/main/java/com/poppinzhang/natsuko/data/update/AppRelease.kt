package com.poppinzhang.natsuko.data.update

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.checkNotNullText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.text
import java.util.Locale
import org.json.JSONObject

/** The installable package of a release. [sha256] is absent when GitHub publishes no digest for it. */
data class ReleaseApk(val url: String, val size: Long, val sha256: String?)

data class AppRelease(val version: ReleaseVersion, val pageUrl: String, val apk: ReleaseApk)

/** Reads the latest published release of this app from the GitHub REST API. */
object GitHubReleases {
    const val REPOSITORY = "poppinzhang/Natsuko"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    private val sha256 = Regex("sha256:([0-9a-f]{64})")

    /** Returns the release when it is newer than [installed], or null when the installation is current. */
    fun findUpdate(json: String, installed: ReleaseVersion): AppRelease? {
        val release = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw LocalizedIllegalStateException(text(R.string.update_error_invalid_response), error)
        }
        val tag = release.optString("tag_name")
        val version = checkNotNullText(ReleaseVersion.fromTag(tag)) { text(R.string.update_error_invalid_response) }
        if (version <= installed) return null
        val assets = release.optJSONArray("assets")
        val packages = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        // The release workflow publishes one signed package named after the version. Installed copies
        // cannot learn a later naming scheme, so a release's only APK is accepted under any name.
        val apk = packages.firstOrNull { it.optString("name") == "Natsuko-$version.apk" } ?: packages.singleOrNull()
        checkNotNullText(apk) { text(R.string.update_error_missing_apk, version.toString()) }
        val url = apk.optString("browser_download_url")
        val size = apk.optLong("size")
        checkText(size > 0 && UpdateHttpClient.isAllowed(url, api = false)) {
            text(R.string.update_error_invalid_response)
        }
        return AppRelease(
            version = version,
            // The tag has just been validated, so the page address needs nothing from the response.
            pageUrl = "https://github.com/$REPOSITORY/releases/tag/$tag",
            apk = ReleaseApk(
                url = url,
                size = size,
                sha256 = sha256.matchEntire(apk.optString("digest").lowercase(Locale.ROOT))?.groupValues?.get(1),
            ),
        )
    }
}
