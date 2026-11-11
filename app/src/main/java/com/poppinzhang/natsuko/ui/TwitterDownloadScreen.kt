@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.poppinzhang.natsuko.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.twitter.TwitterDownloadScope
import com.poppinzhang.natsuko.data.twitter.TwitterVideoSelection
import com.poppinzhang.natsuko.model.Feature

@Composable
internal fun TwitterLoginConsentDialog(onLogin: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("twitter-login-consent"),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.twitter_auth_consent_title)) },
        text = { Text(stringResource(R.string.twitter_auth_consent_message)) },
        confirmButton = {
            TextButton(onClick = onLogin, modifier = Modifier.testTag("twitter-login-accept")) {
                Text(stringResource(R.string.twitter_auth_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("twitter-login-decline")) {
                Text(stringResource(R.string.main_cancel))
            }
        },
    )
}

@Composable
internal fun TwitterVideoSelectionDialog(
    selection: TwitterVideoSelection,
    onChoose: (TwitterDownloadScope) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("twitter-selection-dialog"),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.twitter_selection_title)) },
        text = {
            Text(pluralStringResource(R.plurals.twitter_selection_message, selection.videoCount, selection.videoCount))
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onChoose(TwitterDownloadScope.CURRENT) },
                    modifier = Modifier.fillMaxWidth().testTag("twitter-selection-current"),
                ) { Text(stringResource(R.string.twitter_selection_current)) }
                OutlinedButton(
                    onClick = { onChoose(TwitterDownloadScope.ALL) },
                    modifier = Modifier.fillMaxWidth().testTag("twitter-selection-all"),
                ) { Text(stringResource(R.string.twitter_selection_all)) }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().testTag("twitter-selection-cancel"),
                ) { Text(stringResource(R.string.main_cancel)) }
            }
        },
    )
}

@Composable
internal fun TwitterDownloadScreen(
    link: String,
    isBusy: Boolean,
    onLinkChange: (String) -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit,
    sessionActive: Boolean = false,
    onSignOut: () -> Unit = {},
    onSignIn: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val canDownload = link.isNotBlank() && !isBusy
    val startDownload = {
        if (canDownload) {
            keyboard?.hide()
            onDownload()
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Feature.DOWNLOAD_TWITTER_VIDEO.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isBusy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.secondary_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = startDownload,
                    enabled = canDownload,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp)
                        .testTag("twitter-download-start"),
                    shape = RoundedCornerShape(18.dp),
                ) { Text(stringResource(R.string.twitter_download_start), fontWeight = FontWeight.Bold) }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.twitter_link_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.twitter_link_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = link,
                    onValueChange = onLinkChange,
                    enabled = !isBusy,
                    label = { Text(stringResource(R.string.twitter_link_label)) },
                    placeholder = { Text(stringResource(R.string.twitter_link_placeholder)) },
                    modifier = Modifier.fillMaxWidth().testTag("twitter-link-input"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { startDownload() }),
                )
            }
            if (sessionActive) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.twitter_auth_session_active), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onSignOut, enabled = !isBusy, modifier = Modifier.testTag("twitter-sign-out")) {
                            Text(stringResource(R.string.twitter_auth_sign_out))
                        }
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.twitter_auth_manual_hint), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onSignIn, enabled = !isBusy, modifier = Modifier.testTag("twitter-sign-in")) {
                            Text(stringResource(R.string.twitter_auth_sign_in))
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.twitter_gif_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.twitter_gif_description),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.twitter_storage_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
