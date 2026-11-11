package com.poppinzhang.natsuko.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.checkNotNullText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.errorText
import com.poppinzhang.natsuko.model.text
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Installs a downloaded release over this app through Android's package installer. Android verifies
 * the signature against the installed app and replaces the app itself; no downloaded code is run here.
 */
class AppUpdateInstaller(context: Context) {
    private val context = context.applicationContext

    /** Returns false when the user declines the system confirmation; a completed update ends this process. */
    suspend fun install(apk: File, onUserAction: (Intent) -> Unit): Boolean = withContext(Dispatchers.IO) {
        verify(apk)
        val installer = context.packageManager.packageInstaller
        // An interrupted update leaves a staged copy of the package behind.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            // The user agreed in the app; Android still shows its own confirmation whenever it requires one.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(parameters)
        try {
            val session = installer.openSession(sessionId)
            session.use {
                session.openWrite(apk.name, 0, apk.length()).use { output ->
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    session.fsync(output)
                }
                commit(session, sessionId, onUserAction)
            }
        } finally {
            // Releases the staged copy after a failure or cancellation; a finished session is already gone.
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    /** Explains a package Android would refuse before any system prompt appears. */
    internal fun verify(apk: File) {
        val packages = context.packageManager
        val none = PackageManager.PackageInfoFlags.of(0)
        val archive = checkNotNullText(packages.getPackageArchiveInfo(apk.path, none)) {
            text(R.string.update_error_invalid_package)
        }
        checkText(archive.packageName == context.packageName) { text(R.string.update_error_package_mismatch) }
        checkText(archive.longVersionCode > packages.getPackageInfo(context.packageName, none).longVersionCode) {
            text(R.string.update_error_not_newer)
        }
    }

    private suspend fun commit(session: PackageInstaller.Session, sessionId: Int, onUserAction: (Intent) -> Unit): Boolean {
        val action = "${context.packageName}.action.UPDATE_INSTALL_STATUS"
        val results = Channel<Intent>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results.trySend(intent)
            }
        }
        // Android fills in the result, so the intent is mutable; naming the package keeps it explicit.
        val status = PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            session.commit(status.intentSender)
            for (result in results) {
                if (result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) continue
                when (val code = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> onUserAction(
                        checkNotNullText(result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)) {
                            text(R.string.update_error_install_failed)
                        },
                    )
                    PackageInstaller.STATUS_SUCCESS -> return true
                    PackageInstaller.STATUS_FAILURE_ABORTED -> return false
                    else -> errorText(text(failureMessage(code)))
                }
            }
            errorText(text(R.string.update_error_install_failed))
        } finally {
            context.unregisterReceiver(receiver)
            status.cancel()
        }
    }

    @StringRes
    private fun failureMessage(status: Int): Int = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.update_error_install_blocked
        PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.update_error_install_conflict
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.update_error_install_incompatible
        PackageInstaller.STATUS_FAILURE_INVALID -> R.string.update_error_invalid_package
        PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.update_error_install_storage
        else -> R.string.update_error_install_failed
    }
}
