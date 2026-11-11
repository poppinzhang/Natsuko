package com.poppinzhang.natsuko

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.update.AppUpdateInstaller
import com.poppinzhang.natsuko.model.LocalizedFailure
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unsuitable packages are refused before Android is asked to install anything. A real update
 * replaces the app under test, so the installation itself is verified manually; see docs/app_update.md.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateInstallerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val installer = AppUpdateInstaller(context)
    private val scratch = File(context.cacheDir, "installer-test-${UUID.randomUUID()}.apk")

    @After
    fun cleanUp() {
        scratch.delete()
    }

    @Test
    fun filesThatAreNotPackagesAreRejected() {
        scratch.writeBytes(ByteArray(4096) { it.toByte() })
        assertEquals(text(R.string.update_error_invalid_package), failure { installer.verify(scratch) })
        assertEquals(text(R.string.update_error_invalid_package), failure { installer.verify(File(context.cacheDir, "missing.apk")) })
    }

    @Test
    fun thePackageOfAnotherAppIsRejected() {
        // The instrumentation APK is a valid package with a different application ID.
        val other = File(instrumentation.context.packageCodePath)
        assertTrue(other.isFile)
        assertEquals(text(R.string.update_error_package_mismatch), failure { installer.verify(other) })
    }

    @Test
    fun aPackageThatIsNotNewerThanTheInstalledVersionIsRejected() {
        val installed = File(context.packageCodePath)
        assertTrue(installed.isFile)
        assertEquals(text(R.string.update_error_not_newer), failure { installer.verify(installed) })
    }

    @Test
    fun installRefusesAnUnsuitablePackageWithoutOpeningASession() {
        val sessions = context.packageManager.packageInstaller
        val before = sessions.mySessions.map { it.sessionId }
        var confirmations = 0

        val message = failure {
            runBlocking { installer.install(File(context.packageCodePath)) { confirmations++ } }
        }

        assertEquals(text(R.string.update_error_not_newer), message)
        assertEquals(0, confirmations)
        assertEquals(before, sessions.mySessions.map { it.sessionId })
    }

    private fun failure(block: () -> Unit): UiText? {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected a localized failure, got $error", error is LocalizedFailure)
        return (error as LocalizedFailure).userMessage
    }
}
