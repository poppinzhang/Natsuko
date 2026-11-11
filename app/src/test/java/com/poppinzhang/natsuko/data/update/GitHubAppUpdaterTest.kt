package com.poppinzhang.natsuko.data.update

import android.content.Intent
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.LocalizedFailure
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.text
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Release lookup needs Android's JSON parser and is covered by the device tests. */
class GitHubAppUpdaterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val published = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val directory get() = File(temporary.root, "update")
    private val target get() = File(directory, "natsuko-1.4.0.apk")
    private val connections = AtomicInteger()

    @Test
    fun downloadKeepsOnlyTheVerifiedPackage() = runBlocking {
        directory.mkdirs()
        val obsolete = listOf("natsuko-1.3.5.apk", "natsuko-1.4.0.apk.part", "notes.txt").map { File(directory, it) }
        obsolete.forEach { it.writeText("old") }
        val progress = mutableListOf<Long>()

        val apk = updater().download(release()) { progress += it }

        assertEquals(target, apk)
        assertTrue(published.contentEquals(apk.readBytes()))
        assertEquals(listOf(target), directory.listFiles().orEmpty().toList())
        assertEquals(published.size.toLong(), progress.last())
        assertEquals(1, connections.get())
    }

    @Test
    fun aVerifiedPackageIsReusedInsteadOfDownloadedAgain() = runBlocking {
        val updater = updater()
        val first = updater.download(release()) {}
        val progress = mutableListOf<Long>()

        val second = updater.download(release()) { progress += it }

        assertEquals(first, second)
        assertEquals(listOf(published.size.toLong()), progress)
        assertEquals(1, connections.get())
    }

    @Test
    fun aKeptPackageIsDownloadedAgainWhenItCannotBeVerified() = runBlocking {
        val updater = updater()
        updater.download(release()) {}
        // Same length, different content.
        target.writeBytes(ByteArray(published.size))
        updater.download(release()) {}
        assertTrue(published.contentEquals(target.readBytes()))
        assertEquals(2, connections.get())

        // Without a published digest there is nothing to verify a kept file against.
        updater.download(release(sha256 = null)) {}
        assertTrue(published.contentEquals(target.readBytes()))
        assertEquals(3, connections.get())
    }

    @Test
    fun aDownloadThatDoesNotMatchThePublishedDigestIsDeleted() = runBlocking {
        val tampered = published.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        val failure = runCatching { updater(served = tampered).download(release()) {} }.exceptionOrNull()

        assertEquals(text(R.string.update_error_checksum), (failure as? LocalizedFailure)?.userMessage)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFailedDownloadLeavesNoPartialFile() = runBlocking {
        val failure = runCatching {
            updater(served = published.copyOf(1_000)).download(release()) {}
        }.exceptionOrNull()

        assertEquals(text(R.string.backend_download_incomplete), (failure as? LocalizedFailure)?.userMessage)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectedPackagesAreDeletedWhileDeclinedOrCancelledOnesAreKept() = runBlocking {
        val rejection = LocalizedIllegalStateException(text(R.string.update_error_install_conflict))
        val confirmation = Intent()
        val offered = mutableListOf<Intent>()

        val declined = updater { _, onUserAction -> onUserAction(confirmation); false }
        val apk = declined.download(release()) {}
        assertFalse(declined.install(apk) { offered += it })
        assertSame(confirmation, offered.single())
        assertTrue(apk.isFile)

        val cancelled = updater { _, _ -> throw CancellationException("Cancelled") }
        assertTrue(runCatching { cancelled.install(apk) {} }.exceptionOrNull() is CancellationException)
        assertTrue(apk.isFile)

        val rejected = updater { _, _ -> throw rejection }
        assertSame(rejection, runCatching { rejected.install(apk) {} }.exceptionOrNull())
        assertFalse(apk.exists())
    }

    private fun release(sha256: String? = sha256(published)) = AppRelease(
        version = ReleaseVersion(1, 4, 0),
        pageUrl = "https://github.com/poppinzhang/Natsuko/releases/tag/v1.4.0",
        apk = ReleaseApk(
            url = "https://github.com/poppinzhang/Natsuko/releases/download/v1.4.0/Natsuko-1.4.0.apk",
            size = published.size.toLong(),
            sha256 = sha256,
        ),
    )

    private fun updater(
        served: ByteArray = published,
        install: suspend (File, (Intent) -> Unit) -> Boolean = { _, _ -> true },
    ) = GitHubAppUpdater(
        http = UpdateHttpClient({ connections.incrementAndGet(); FakeConnection(served) }),
        directory = directory,
        installed = ReleaseVersion(1, 3, 1),
        installPackage = install,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakeConnection(body: ByteArray) : HttpURLConnection(URL("https://github.com/test")) {
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
