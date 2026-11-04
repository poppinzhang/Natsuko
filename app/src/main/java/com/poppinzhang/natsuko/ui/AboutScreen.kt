@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.poppinzhang.natsuko.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poppinzhang.natsuko.BuildConfig
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.OpenSourceNotice
import com.poppinzhang.natsuko.data.OpenSourceProject
import com.poppinzhang.natsuko.data.OpenSourceRepository
import kotlinx.coroutines.CancellationException

@Composable
internal fun AboutScreen(
    selectedProjectId: String?,
    onProject: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { OpenSourceRepository(context) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val catalog by produceState<Result<List<OpenSourceProject>>?>(null, repository, retry) {
        value = null
        value = loadAboutContent { repository.projects() }
    }
    val project = catalog?.getOrNull()?.find { it.id == selectedProjectId }
    // Keep the catalog's scroll position while a project's full notices are open.
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedProjectId == null) stringResource(R.string.about_title)
                        else project?.name ?: stringResource(R.string.about_notices_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("about-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.secondary_back))
                    }
                },
            )
        },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when {
            catalog == null -> AboutLoading(contentModifier)
            catalog?.isFailure == true -> AboutLoadError(contentModifier, onRetry = { retry++ })
            selectedProjectId != null -> {
                if (project == null) {
                    AboutLoadError(contentModifier, onRetry = { retry++ })
                } else {
                    key(project.id) { OpenSourceNotices(project, repository, contentModifier) }
                }
            }
            else -> LazyColumn(
                modifier = contentModifier.testTag("about-list"),
                state = listState,
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.about_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                                modifier = Modifier.testTag("about-version"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.about_projects_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.about_projects_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(catalog?.getOrNull().orEmpty(), key = { it.id }) { item ->
                    Card(
                        onClick = { onProject(item.id) },
                        modifier = Modifier.fillMaxWidth().testTag("open-source-${item.id}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(projectVersion(item), style = MaterialTheme.typography.bodySmall)
                            Text(item.license, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            val description = projectDescription(item)
                            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall)
                            if (item.url.isNotBlank()) Text(
                                item.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(stringResource(R.string.about_view_notices), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenSourceNotices(project: OpenSourceProject, repository: OpenSourceRepository, modifier: Modifier) {
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val notices by produceState<Result<List<OpenSourceNotice>>?>(null, project, repository, retry) {
        value = null
        value = loadAboutContent { repository.notices(project) }
    }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val noticeItems = remember(notices) {
        notices?.getOrNull().orEmpty().flatMap { notice ->
            // Bound text layout work even for large NOTICE files, retaining every original character.
            listOf(NoticeItem(heading = true, text = notice.filename)) +
                notice.text.chunked(4_000).map { NoticeItem(heading = false, text = it) }
        }
    }
    when {
        notices == null -> AboutLoading(modifier)
        notices?.isFailure == true -> AboutLoadError(modifier, onRetry = { retry++ })
        else -> LazyColumn(
            modifier = modifier.testTag("open-source-notices"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(projectVersion(project), style = MaterialTheme.typography.bodyMedium)
                    Text(project.license, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    val description = projectDescription(project)
                    if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
                    if (project.url.isNotBlank()) {
                        SelectionContainer { Text(project.url, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = {
                            try {
                                uriHandler.openUri(project.url)
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.about_link_error, Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.about_open_source_url)) }
                    }
                }
            }
            items(noticeItems.size) { index ->
                val item = noticeItems[index]
                if (item.heading) {
                    Text(item.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                } else {
                    SelectionContainer {
                        Text(item.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("notice-text-$index"))
                    }
                }
            }
        }
    }
}

private data class NoticeItem(val heading: Boolean, val text: String)

@Composable
private fun projectVersion(project: OpenSourceProject): String =
    if (project.id == "natsuko") stringResource(R.string.about_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    else stringResource(R.string.about_project_version, project.version)

@Composable
private fun projectDescription(project: OpenSourceProject): String =
    project.descriptionRes?.let { stringResource(it) } ?: project.description

@Composable
private fun AboutLoading(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun AboutLoadError(modifier: Modifier, onRetry: () -> Unit) {
    Box(modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.about_load_error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.about_retry)) }
        }
    }
}

private suspend fun <T> loadAboutContent(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
