@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.poppinzhang.natsuko.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.processing.location.LocationConflict
import com.poppinzhang.natsuko.processing.location.LocationConflictDecision
import com.poppinzhang.natsuko.processing.location.LocationSource

@Composable
internal fun CopyLocationSourceScreen(
    state: CopyLocationUiState,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onChoose: (LocationSource) -> Unit,
) {
    Scaffold(topBar = { LocationTopBar(onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("location-source-screen"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(R.string.location_source_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.location_source_hint),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(onClick = onPick, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth().testTag("location-pick-source")) {
                    Text(stringResource(R.string.location_pick_source))
                }
            }
            if (state.isLoading) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.location_loading))
                    }
                }
            }
            itemsIndexed(state.candidates) { index, candidate ->
                val source = candidate.source
                Card(
                    onClick = { source?.let(onChoose) },
                    enabled = source != null && !state.isLoading,
                    modifier = Modifier.fillMaxWidth().testTag("location-source-$index"),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(candidate.input.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            source?.location?.displayText ?: candidate.error?.asString()
                                ?: stringResource(R.string.location_invalid_source),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { LocationFormatsHint() }
        }
    }
}

@Composable
internal fun CopyLocationTargetsScreen(
    state: CopyLocationUiState,
    isBusy: Boolean,
    onBack: () -> Unit,
    onPick: () -> Unit,
) {
    Scaffold(topBar = { LocationTopBar(onBack = onBack, enabled = !isBusy) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("location-targets-screen"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(R.string.location_targets_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.location_targets_hint),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.source?.let { source ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.location_source_label), style = MaterialTheme.typography.labelLarge)
                            Text(source.input.displayName, fontWeight = FontWeight.SemiBold)
                            Text(source.location.displayText)
                        }
                    }
                }
            }
            item {
                Button(onClick = onPick, enabled = !isBusy && state.source != null, modifier = Modifier.fillMaxWidth().testTag("location-pick-targets")) {
                    Text(stringResource(R.string.location_pick_targets))
                }
            }
            item {
                Text(stringResource(R.string.location_copies_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { LocationFormatsHint() }
        }
    }
}

@Composable
private fun LocationTopBar(onBack: () -> Unit, enabled: Boolean = true) {
    TopAppBar(
        title = { Text(stringResource(R.string.feature_copy_location)) },
        navigationIcon = {
            IconButton(onClick = onBack, enabled = enabled) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.main_back))
            }
        },
    )
}

@Composable
private fun LocationFormatsHint() {
    Text(
        stringResource(R.string.location_formats_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun CopyLocationConflictDialog(
    conflict: LocationConflict,
    onDecision: (LocationConflictDecision) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag("location-conflict-dialog"),
        title = { Text(stringResource(R.string.location_conflict_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(conflict.target.displayName, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.location_before, conflict.before.displayText))
                Text(stringResource(R.string.location_after, conflict.after.displayText))
                Text(stringResource(R.string.location_conflict_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                LocationDecisionButton(R.string.location_overwrite, "location-overwrite") { onDecision(LocationConflictDecision.OVERWRITE) }
                LocationDecisionButton(R.string.location_skip, "location-skip") { onDecision(LocationConflictDecision.SKIP) }
                LocationDecisionButton(R.string.location_overwrite_all, "location-overwrite-all") { onDecision(LocationConflictDecision.OVERWRITE_ALL) }
                LocationDecisionButton(R.string.location_skip_all, "location-skip-all") { onDecision(LocationConflictDecision.SKIP_ALL) }
                LocationDecisionButton(R.string.location_abort, "location-abort") { onDecision(LocationConflictDecision.ABORT) }
            }
        },
    )
}

@Composable
private fun LocationDecisionButton(label: Int, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(stringResource(label))
    }
}
