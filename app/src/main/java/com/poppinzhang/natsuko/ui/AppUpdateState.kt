package com.poppinzhang.natsuko.ui

import android.content.Intent
import com.poppinzhang.natsuko.data.update.AppRelease

/** The update workflow is independent of file processing, so it survives workflow resets. */
sealed interface AppUpdateState {
    /** The release being offered, downloaded or installed. */
    val release: AppRelease? get() = null

    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState

    /** A newer release was found and waits for the user's decision. */
    data class Available(override val release: AppRelease) : AppUpdateState

    data class Downloading(override val release: AppRelease, val bytes: Long = 0) : AppUpdateState

    /** [confirmation] is Android's own install prompt, held until the foreground UI has opened it. */
    data class Installing(override val release: AppRelease, val confirmation: Intent? = null) : AppUpdateState

    /** Installing replaces and closes the app, so no other work may start or continue alongside it. */
    val blocksOtherWork: Boolean
        get() = this is Downloading || this is Installing
}
