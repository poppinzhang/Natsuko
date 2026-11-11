package com.poppinzhang.natsuko

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.update.GitHubAppUpdater
import com.poppinzhang.natsuko.data.update.GitHubReleases
import com.poppinzhang.natsuko.data.update.ReleaseVersion
import com.poppinzhang.natsuko.data.update.UpdateHttpClient
import com.poppinzhang.natsuko.model.LocalizedFailure
import com.poppinzhang.natsuko.model.text
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Release information is parsed with Android's JSON implementation, so these run on a device. */
@RunWith(AndroidJUnit4::class)
class GitHubReleasesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val installed = ReleaseVersion(1, 3, 1)
    private val digest = "sha256:" + "9f".repeat(32)
    private val directory = File(context.cacheDir, "update-test-${UUID.randomUUID()}")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun newerReleaseOffersTheVersionedPackageWithItsSizeAndDigest() {
        val release = GitHubReleases.findUpdate(
            release(
                "v1.4.0",
                asset("natsuko-third-party-sources-1.4.0.tar.gz", 35_729_258),
                asset("Natsuko-1.4.0.apk", 29_754_424),
                asset("SHA256SUMS", 190),
            ),
            installed,
        )

        assertEquals(ReleaseVersion(1, 4, 0), release!!.version)
        assertEquals("https://github.com/poppinzhang/Natsuko/releases/tag/v1.4.0", release.pageUrl)
        assertEquals("https://github.com/poppinzhang/Natsuko/releases/download/v1.4.0/Natsuko-1.4.0.apk", release.apk.url)
        assertEquals(29_754_424L, release.apk.size)
        assertEquals("9f".repeat(32), release.apk.sha256)
    }

    @Test
    fun currentAndOlderReleasesAreNotOfferedEvenWithoutAPackage() {
        for (tag in listOf("v1.3.1", "v1.3.0", "v1.2.10", "v0.9.0")) {
            assertNull(tag, GitHubReleases.findUpdate(release(tag), installed))
            assertNull(tag, GitHubReleases.findUpdate(release(tag, asset("Natsuko-${tag.drop(1)}.apk", 10)), installed))
        }
        // A debug build of the published version is current as well.
        assertNull(GitHubReleases.findUpdate(release("v1.3.1"), ReleaseVersion.fromVersionName("1.3.1-debug")!!))
    }

    @Test
    fun theVersionedPackageIsPreferredAndALoneApkIsAcceptedUnderAnotherName() {
        val preferred = GitHubReleases.findUpdate(
            release("v1.4.0", asset("app-debug.apk", 59_000_000), asset("Natsuko-1.4.0.apk", 29_000_000)),
            installed,
        )
        assertTrue(preferred!!.apk.url.endsWith("/Natsuko-1.4.0.apk"))
        assertEquals(29_000_000L, preferred.apk.size)

        val renamed = GitHubReleases.findUpdate(
            release("v1.4.0", asset("natsuko-release.APK", 28_000_000), asset("SHA256SUMS", 190)),
            installed,
        )
        assertTrue(renamed!!.apk.url.endsWith("/natsuko-release.APK"))

        val missing = text(R.string.update_error_missing_apk, "1.4.0")
        // Two packages under unknown names: neither can be chosen safely.
        assertEquals(missing, failure(release("v1.4.0", asset("app-debug.apk", 5), asset("app-release.apk", 5))))
        assertEquals(missing, failure(release("v1.4.0", asset("SHA256SUMS", 190))))
        assertEquals(missing, failure(release("v1.4.0")))
        assertEquals(missing, failure(JSONObject().put("tag_name", "v1.4.0").toString()))
    }

    @Test
    fun onlyWellFormedSha256DigestsAreUsed() {
        fun digestOf(value: Any?) = GitHubReleases.findUpdate(
            release("v1.4.0", asset("Natsuko-1.4.0.apk", 10, digest = value)),
            installed,
        )!!.apk.sha256

        assertEquals("ab".repeat(32), digestOf("SHA256:" + "AB".repeat(32)))
        for (unusable in listOf(null, "", "sha512:" + "ab".repeat(64), "sha256:" + "ab".repeat(31), "sha256:" + "zz".repeat(32), "ab".repeat(32))) {
            assertNull("$unusable", digestOf(unusable))
        }
        val withoutDigest = JSONObject().put("name", "Natsuko-1.4.0.apk").put("size", 10)
            .put("browser_download_url", "https://github.com/poppinzhang/Natsuko/releases/download/v1.4.0/Natsuko-1.4.0.apk")
        assertNull(GitHubReleases.findUpdate(release("v1.4.0", withoutDigest), installed)!!.apk.sha256)
    }

    @Test
    fun malformedReleaseInformationIsRejected() {
        val invalid = text(R.string.update_error_invalid_response)
        val apk = asset("Natsuko-1.4.0.apk", 10)
        for (json in listOf(
            "", "not json", "[]", "{}",
            release("1.4.0", apk), release("v1.4", apk), release("v1.4.0-rc1", apk), release("latest", apk),
            release("v1.4.0", asset("Natsuko-1.4.0.apk", 0)),
            release("v1.4.0", asset("Natsuko-1.4.0.apk", -5)),
            release("v1.4.0", asset("Natsuko-1.4.0.apk", 10, url = "https://example.com/Natsuko-1.4.0.apk")),
            release("v1.4.0", asset("Natsuko-1.4.0.apk", 10, url = "http://github.com/poppinzhang/Natsuko/releases/download/v1.4.0/Natsuko-1.4.0.apk")),
            release("v1.4.0", asset("Natsuko-1.4.0.apk", 10, url = "")),
        )) {
            assertEquals(json, invalid, failure(json))
        }
    }

    @Test
    fun checkOffersNewerReleasesAndRemovesKeptPackagesOnceCurrent() = runBlocking {
        val kept = File(directory, "natsuko-1.3.1.apk").apply {
            checkNotNull(parentFile).mkdirs()
            writeText("kept")
        }
        fun updater(json: String) = GitHubAppUpdater(
            http = UpdateHttpClient({ FakeConnection(json.toByteArray()) }),
            directory = directory,
            installed = installed,
            installPackage = { _, _ -> true },
        )

        val offered = updater(release("v1.3.2", asset("Natsuko-1.3.2.apk", 10))).check()
        assertEquals(ReleaseVersion(1, 3, 2), offered!!.version)
        // A newer release may be the one this package was downloaded for.
        assertTrue(kept.isFile)

        assertNull(updater(release("v1.3.1", asset("Natsuko-1.3.1.apk", 10))).check())
        assertFalse(directory.exists())
    }

    private fun failure(json: String) =
        (runCatching { GitHubReleases.findUpdate(json, installed) }.exceptionOrNull() as? LocalizedFailure)?.userMessage

    private fun release(tag: String, vararg assets: JSONObject): String =
        JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
            .put("assets", JSONArray(assets.toList())).toString()

    private fun asset(
        name: String,
        size: Long,
        digest: Any? = this.digest,
        url: String = "https://github.com/poppinzhang/Natsuko/releases/download/v1.4.0/$name",
    ): JSONObject = JSONObject().put("name", name).put("size", size).put("browser_download_url", url)
        .put("digest", digest ?: JSONObject.NULL)

    private class FakeConnection(body: ByteArray) : HttpURLConnection(URL("https://api.github.com/test")) {
        private val input = ByteArrayInputStream(body)
        private val length = body.size.toLong()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getInputStream(): InputStream = input
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = length
    }
}
