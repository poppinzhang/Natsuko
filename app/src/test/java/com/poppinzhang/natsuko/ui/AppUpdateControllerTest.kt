package com.poppinzhang.natsuko.ui

import android.content.Intent
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.update.AppRelease
import com.poppinzhang.natsuko.data.update.AppUpdater
import com.poppinzhang.natsuko.data.update.ReleaseApk
import com.poppinzhang.natsuko.data.update.ReleaseVersion
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unconfined coroutines run each step as soon as the fake updater lets it continue. */
class AppUpdateControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val release = AppRelease(
        version = ReleaseVersion(1, 4, 0),
        pageUrl = "https://github.com/poppinzhang/Natsuko/releases/tag/v1.4.0",
        apk = ReleaseApk("https://github.com/poppinzhang/Natsuko/releases/download/v1.4.0/Natsuko-1.4.0.apk", 1_000, null),
    )
    private val apk = File("natsuko-1.4.0.apk")
    private val updater = FakeUpdater()
    private val errors = mutableListOf<UiText>()
    private var processing = false
    private val controller = AppUpdateController(scope, updater, { processing }, { errors += it })

    @After
    fun cancelScope() = scope.cancel()

    @Test
    fun checkReportsACurrentInstallationAndCanBeRepeated() {
        controller.check()
        assertEquals(AppUpdateState.Checking, controller.state.value)
        controller.check()
        assertEquals(1, updater.checks)

        updater.checked.complete(null)
        assertEquals(AppUpdateState.UpToDate, controller.state.value)
        assertFalse(controller.state.value.blocksOtherWork)

        controller.clearResult()
        assertEquals(AppUpdateState.Idle, controller.state.value)
        // A bare null would select the overload that takes a parent job and stay incomplete.
        updater.checked = CompletableDeferred<AppRelease?>(value = null)
        controller.check()
        assertEquals(2, updater.checks)
        assertEquals(AppUpdateState.UpToDate, controller.state.value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aNewerReleaseWaitsForTheUserAndCanBeDeclined() {
        offerRelease()
        assertEquals(AppUpdateState.Available(release), controller.state.value)
        assertFalse(controller.state.value.blocksOtherWork)
        controller.check()
        assertEquals(1, updater.checks)

        controller.dismiss()

        assertEquals(AppUpdateState.Idle, controller.state.value)
        assertEquals(0, updater.downloads)
        controller.install()
        assertEquals(0, updater.downloads)
    }

    @Test
    fun failedChecksReturnToIdleWithTheirOwnOrAGenericMessage() {
        updater.checked.completeExceptionally(LocalizedIllegalStateException(text(R.string.update_error_rate_limited)))
        controller.check()
        assertEquals(AppUpdateState.Idle, controller.state.value)

        updater.checked = CompletableDeferred<AppRelease?>().apply { completeExceptionally(IllegalStateException("Unexpected")) }
        controller.check()

        assertEquals(AppUpdateState.Idle, controller.state.value)
        assertEquals(listOf(text(R.string.update_error_rate_limited), text(R.string.update_error_check_failed)), errors)
    }

    @Test
    fun acceptingDownloadsThenInstallsAndShowsTheSystemConfirmationOnce() {
        offerRelease()
        controller.install()
        controller.install()

        assertEquals(AppUpdateState.Downloading(release, 0), controller.state.value)
        assertTrue(controller.state.value.blocksOtherWork)
        assertEquals(1, updater.downloads)
        updater.onBytes(400)
        assertEquals(AppUpdateState.Downloading(release, 400), controller.state.value)

        updater.downloaded.complete(apk)
        assertEquals(AppUpdateState.Installing(release), controller.state.value)
        assertTrue(controller.state.value.blocksOtherWork)
        assertSame(apk, updater.installing)
        // Progress reported after the download finished must not bring the download state back.
        updater.onBytes(1_000)
        assertEquals(AppUpdateState.Installing(release), controller.state.value)

        val confirmation = Intent()
        updater.onUserAction(confirmation)
        assertSame(confirmation, (controller.state.value as AppUpdateState.Installing).confirmation)
        controller.onConfirmationLaunched()
        assertNull((controller.state.value as AppUpdateState.Installing).confirmation)

        updater.installed.complete(false)
        assertEquals(AppUpdateState.Idle, controller.state.value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun anUpdateDoesNotStartWhileFilesAreBeingProcessed() {
        offerRelease()
        processing = true

        controller.install()

        assertEquals(AppUpdateState.Available(release), controller.state.value)
        assertEquals(0, updater.downloads)
        assertEquals(listOf(text(R.string.update_wait_for_tasks)), errors)

        processing = false
        controller.install()
        assertEquals(1, updater.downloads)
    }

    @Test
    fun downloadAndInstallationFailuresReturnToIdleWithAMessageForThatStep() {
        offerRelease()
        controller.install()
        updater.downloaded.completeExceptionally(IOException("Disk full"))
        assertEquals(AppUpdateState.Idle, controller.state.value)

        updater.checked = CompletableDeferred(release)
        updater.downloaded = CompletableDeferred(apk)
        updater.installed = CompletableDeferred<Boolean>().apply { completeExceptionally(SecurityException("Denied")) }
        controller.check()
        controller.install()
        assertEquals(AppUpdateState.Idle, controller.state.value)

        updater.checked = CompletableDeferred(release)
        updater.installed = CompletableDeferred<Boolean>().apply {
            completeExceptionally(LocalizedIllegalStateException(text(R.string.update_error_install_conflict)))
        }
        controller.check()
        controller.install()

        assertEquals(AppUpdateState.Idle, controller.state.value)
        assertEquals(
            listOf(
                text(R.string.backend_download_failed),
                text(R.string.update_error_install_failed),
                text(R.string.update_error_install_conflict),
            ),
            errors,
        )
    }

    @Test
    fun cancellingStopsADownloadOrPendingInstallationWithoutAnError() {
        offerRelease()
        controller.cancel()
        assertEquals(AppUpdateState.Available(release), controller.state.value)

        controller.install()
        controller.cancel()
        assertEquals(AppUpdateState.Idle, controller.state.value)
        assertTrue(updater.downloaded.isCancelled)

        updater.checked = CompletableDeferred(release)
        updater.downloaded = CompletableDeferred(apk)
        controller.check()
        controller.install()
        assertEquals(AppUpdateState.Installing(release), controller.state.value)
        controller.cancel()

        assertEquals(AppUpdateState.Idle, controller.state.value)
        assertTrue(updater.installed.isCancelled)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aConfirmationThatCannotBeShownAbandonsTheInstallation() {
        offerRelease()
        updater.downloaded.complete(apk)
        controller.onConfirmationFailed()
        assertTrue(errors.isEmpty())

        controller.install()
        updater.onUserAction(Intent())
        controller.onConfirmationLaunched()
        controller.onConfirmationFailed()

        assertEquals(AppUpdateState.Idle, controller.state.value)
        assertTrue(updater.installed.isCancelled)
        assertEquals(listOf(text(R.string.update_error_install_failed)), errors)
    }

    private fun offerRelease() {
        updater.checked.complete(release)
        controller.check()
    }

    private class FakeUpdater : AppUpdater {
        var checked = CompletableDeferred<AppRelease?>()
        var downloaded = CompletableDeferred<File>()
        var installed = CompletableDeferred<Boolean>()
        var checks = 0
        var downloads = 0
        var installing: File? = null
        lateinit var onBytes: (Long) -> Unit
        lateinit var onUserAction: (Intent) -> Unit

        override suspend fun check(): AppRelease? {
            checks++
            return checked.await()
        }

        override suspend fun download(release: AppRelease, onBytes: (Long) -> Unit): File {
            downloads++
            this.onBytes = onBytes
            return awaitOrCancel(downloaded)
        }

        override suspend fun install(apk: File, onUserAction: (Intent) -> Unit): Boolean {
            installing = apk
            this.onUserAction = onUserAction
            return awaitOrCancel(installed)
        }

        /** Records that cancelling the caller reached the running download or installation. */
        private suspend fun <T> awaitOrCancel(result: CompletableDeferred<T>): T = try {
            result.await()
        } catch (cancelled: CancellationException) {
            result.cancel()
            throw cancelled
        }
    }
}
