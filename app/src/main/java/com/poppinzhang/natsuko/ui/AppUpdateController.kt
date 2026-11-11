package com.poppinzhang.natsuko.ui

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.update.AppUpdater
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Checks for a newer release, asks the user, then downloads and installs it. Every step starts from
 * a user action; nothing is requested from the network in the background.
 */
class AppUpdateController(
    private val scope: CoroutineScope,
    private val updater: AppUpdater,
    private val isProcessing: () -> Boolean,
    private val onError: (UiText) -> Unit,
) {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun check() {
        val current = mutableState.value
        if (current != AppUpdateState.Idle && current != AppUpdateState.UpToDate) return
        mutableState.value = AppUpdateState.Checking
        job = scope.launch {
            reportingFailure({ R.string.update_error_check_failed }) {
                val release = updater.check()
                mutableState.value = if (release == null) AppUpdateState.UpToDate else AppUpdateState.Available(release)
            }
        }
    }

    /** The user chose not to update now; the next check offers the release again. */
    fun dismiss() = mutableState.update { if (it is AppUpdateState.Available) AppUpdateState.Idle else it }

    /** The user accepted the offered release. */
    fun install() {
        val release = (mutableState.value as? AppUpdateState.Available)?.release ?: return
        if (isProcessing()) {
            // Installing closes the app and would abandon the work in progress.
            onError(text(R.string.update_wait_for_tasks))
            return
        }
        mutableState.value = AppUpdateState.Downloading(release)
        job = scope.launch {
            var fallback = R.string.backend_download_failed
            reportingFailure({ fallback }) {
                val apk = updater.download(release) { bytes ->
                    mutableState.update { if (it is AppUpdateState.Downloading) it.copy(bytes = bytes) else it }
                }
                fallback = R.string.update_error_install_failed
                mutableState.value = AppUpdateState.Installing(release)
                updater.install(apk) { confirmation ->
                    mutableState.update { if (it is AppUpdateState.Installing) it.copy(confirmation = confirmation) else it }
                }
                // Reached when the user declines Android's prompt; a completed update closes the app first.
                mutableState.value = AppUpdateState.Idle
            }
        }
    }

    /** Stops a download or a pending installation; the downloader and installer clean up after themselves. */
    fun cancel() {
        if (mutableState.value.blocksOtherWork) job?.cancel()
    }

    fun onConfirmationLaunched() = mutableState.update {
        if (it is AppUpdateState.Installing) it.copy(confirmation = null) else it
    }

    fun onConfirmationFailed() {
        if (mutableState.value !is AppUpdateState.Installing) return
        job?.cancel()
        onError(text(R.string.update_error_install_failed))
    }

    /** A finished check describes the moment it ran; reopening settings starts without it. */
    fun clearResult() = mutableState.update { if (it == AppUpdateState.UpToDate) AppUpdateState.Idle else it }

    /** Every exit returns to idle; failures without their own message use [fallback] for the current step. */
    private suspend fun reportingFailure(fallback: () -> Int, step: suspend () -> Unit) {
        try {
            step()
        } catch (cancelled: CancellationException) {
            mutableState.value = AppUpdateState.Idle
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = AppUpdateState.Idle
            onError(error.userMessage(fallback()))
        }
    }
}
