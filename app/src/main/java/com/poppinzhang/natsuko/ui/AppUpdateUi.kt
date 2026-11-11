package com.poppinzhang.natsuko.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.poppinzhang.natsuko.BuildConfig
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.update.AppRelease

/** The settings entry: the installed version, the result of the last check, and the check itself. */
@Composable
internal fun AppUpdateSetting(state: AppUpdateState, onCheck: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
                modifier = Modifier.testTag("update-current-version"),
                fontWeight = FontWeight.Medium,
            )
            val release = state.release
            Text(
                when {
                    release != null -> stringResource(R.string.update_available_title, release.version.toString())
                    state == AppUpdateState.Checking -> stringResource(R.string.update_checking)
                    state == AppUpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                    else -> stringResource(R.string.update_hint)
                },
                modifier = Modifier.testTag("update-status"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onCheck,
                enabled = state == AppUpdateState.Idle || state == AppUpdateState.UpToDate,
                modifier = Modifier.testTag("check-update"),
            ) { Text(stringResource(R.string.update_check)) }
        }
    }
}

/** Shown above every screen, so an update in progress stays visible wherever the user is. */
@Composable
internal fun AppUpdateDialogs(state: AppUpdateState, controller: AppUpdateController) {
    val context = LocalContext.current
    val confirmation = (state as? AppUpdateState.Installing)?.confirmation
    // Android's prompt may only be opened from the foreground; this runs once the UI is visible again.
    LaunchedEffect(confirmation) {
        if (confirmation != null) {
            controller.onConfirmationLaunched()
            try {
                context.startActivity(confirmation)
            } catch (_: Exception) {
                controller.onConfirmationFailed()
            }
        }
    }
    when (state) {
        is AppUpdateState.Available -> UpdateAvailableDialog(state.release, controller::install, controller::dismiss)
        is AppUpdateState.Downloading -> UpdateProgressDialog(
            title = stringResource(R.string.update_downloading_title),
            message = stringResource(
                R.string.update_download_progress,
                Formatter.formatShortFileSize(context, state.bytes),
                Formatter.formatShortFileSize(context, state.release.apk.size),
            ),
            fraction = (state.bytes.toFloat() / state.release.apk.size).coerceIn(0f, 1f),
            onCancel = controller::cancel,
        )
        is AppUpdateState.Installing -> UpdateProgressDialog(
            title = stringResource(R.string.update_installing_title),
            message = stringResource(R.string.update_installing_message),
            fraction = null,
            onCancel = controller::cancel,
        )
        else -> Unit
    }
}

@Composable
private fun UpdateAvailableDialog(release: AppRelease, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.update_available_title, release.version.toString()),
                modifier = Modifier.testTag("update-available-title"),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.update_available_message,
                        BuildConfig.VERSION_NAME,
                        Formatter.formatShortFileSize(context, release.apk.size),
                    ),
                )
                TextButton(
                    onClick = {
                        try {
                            uriHandler.openUri(release.pageUrl)
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.update_open_page_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("update-release-notes"),
                ) { Text(stringResource(R.string.update_release_notes)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, modifier = Modifier.testTag("update-install")) {
                Text(stringResource(R.string.update_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("update-later")) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}

/** [fraction] is null while the remaining work cannot be measured. */
@Composable
private fun UpdateProgressDialog(title: String, message: String, fraction: Float?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                Text(message, modifier = Modifier.testTag("update-progress"))
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("update-cancel")) {
                Text(stringResource(R.string.main_cancel))
            }
        },
    )
}
