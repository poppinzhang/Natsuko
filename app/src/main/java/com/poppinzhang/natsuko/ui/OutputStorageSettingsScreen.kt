@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.poppinzhang.natsuko.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.i18n.AppLanguage
import com.poppinzhang.natsuko.i18n.AppLanguages
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.poppinzhang.natsuko.model.ProcessingProgress
import com.poppinzhang.natsuko.model.ThemeMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun OutputStorageSettingsScreen(
    state: NatsukoUiState,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onChooseDirectory: () -> Unit,
    onRestoreDefault: () -> Unit,
    updateState: AppUpdateState,
    onCheckUpdate: () -> Unit,
) {
    val settings = state.storageSettings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.isSavingToUserStorage) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings-list"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { ThemeModeSetting(themeMode, onThemeModeChange) }
            item { LanguageSetting() }
            item { Text(stringResource(R.string.settings_output_storage), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                UserStorageSwitch(
                    checked = settings.enabled,
                    enabled = !state.isSavingToUserStorage,
                    description = stringResource(R.string.settings_storage_description),
                    onCheckedChange = onEnabledChange,
                    testTag = "user-storage-switch",
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.settings_storage_directory), fontWeight = FontWeight.Medium)
                        Text(settings.directoryLabel, modifier = Modifier.testTag("user-storage-directory"), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.settings_storage_grouping), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onChooseDirectory, enabled = !state.isSavingToUserStorage, modifier = Modifier.testTag("choose-user-storage-directory")) {
                            Text(stringResource(R.string.settings_storage_change))
                        }
                        if (settings.treeUri != null) {
                            TextButton(onClick = onRestoreDefault, enabled = !state.isSavingToUserStorage, modifier = Modifier.testTag("restore-user-storage-directory")) {
                                Text(stringResource(R.string.settings_storage_restore))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_storage_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isSavingToUserStorage) item { UserStorageProgress(state.progress) }
            if (!state.isSavingToUserStorage && state.storageFailures.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pluralStringResource(R.plurals.settings_storage_failures, state.storageFailures.size, state.storageFailures.size), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_storage_retry)) }
                    }
                }
            }
            item { Text(stringResource(R.string.settings_update), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { AppUpdateSetting(updateState, onCheckUpdate) }
        }
    }
}

@Composable
private fun ThemeModeSetting(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.appearance_color_mode), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.appearance_color_mode_hint),
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .testTag("theme-mode-${option.preferenceValue}")
                            .selectable(
                                selected = mode == option,
                                onClick = { onModeChange(option) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = mode == option, onClick = null)
                        Text(when (option) {
                            ThemeMode.SYSTEM -> stringResource(R.string.appearance_system)
                            ThemeMode.LIGHT -> stringResource(R.string.appearance_light)
                            ThemeMode.DARK -> stringResource(R.string.appearance_dark)
                        })
                    }
                }
            }
        }
    }
}

@Composable
internal fun UserStorageSwitch(
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val label = stringResource(R.string.settings_storage_save)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_storage_save), style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag).semantics { contentDescription = label },
        )
    }
}

@Composable
internal fun UserStorageProgress(progress: ProcessingProgress) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        Text(progress.message.asString(), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LanguageSetting() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var selected by remember(context, configuration) { mutableStateOf(AppLanguages.current(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { selected = AppLanguages.current(context) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showPicker = true }, modifier = Modifier.testTag("language-picker")) {
                Text(stringResource(selected.labelRes))
            }
        }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    Text(stringResource(R.string.settings_language_hint), style = MaterialTheme.typography.bodyMedium)
                    AppLanguage.entries.forEach { language ->
                        Row(
                            Modifier.fillMaxWidth().testTag("language-${language.name.lowercase(java.util.Locale.ROOT)}")
                                .selectable(
                                    selected = selected == language,
                                    role = Role.RadioButton,
                                    onClick = {
                                        showPicker = false
                                        // Clearing an override may leave the effective locale unchanged.
                                        // Update the choice even when Android need not recreate the Activity.
                                        selected = language
                                        AppLanguages.select(context, language)
                                    },
                                ).padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = selected == language, onClick = null)
                            Text(stringResource(language.labelRes))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.settings_language_cancel)) }
            },
        )
    }
}
