package com.snapnet.ui.page

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.snapnet.R
import com.snapnet.download.DownloaderV2
import com.snapnet.download.Task
import com.snapnet.download.Task.DownloadState
import com.snapnet.util.FileUtil
import com.snapnet.util.findURLsFromString
import com.snapnet.util.makeToast
import org.koin.compose.koinInject

internal data class DashboardCounts(
    val active: Int = 0,
    val completed: Int = 0,
    val stopped: Int = 0,
)

internal fun dashboardCounts(states: Iterable<DownloadState>): DashboardCounts {
    var active = 0
    var completed = 0
    var stopped = 0
    states.forEach { state ->
        when (state) {
            DownloadState.Idle,
            DownloadState.ReadyWithInfo,
            is DownloadState.FetchingInfo,
            is DownloadState.Running -> active++
            is DownloadState.Completed -> completed++
            is DownloadState.Canceled,
            is DownloadState.Error -> stopped++
        }
    }
    return DashboardCounts(active = active, completed = completed, stopped = stopped)
}

private data class RecentDownload(val title: String, val path: String)

@Composable
fun HomeDashboard(
    onStartDownload: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenFiles: () -> Unit,
    downloader: DownloaderV2 = koinInject(),
) {
    val taskStateMap = downloader.getTaskStateMap()
    val snapshot by
        remember(taskStateMap) {
            derivedStateOf {
                val entries = taskStateMap.entries.map { it.key to it.value }
                val counts = dashboardCounts(entries.map { it.second.downloadState })
                val recent =
                    entries
                        .mapNotNull { (task, state) ->
                            val path = (state.downloadState as? DownloadState.Completed)?.filePath
                                ?: return@mapNotNull null
                            val title = state.viewState.title.ifBlank {
                                Uri.parse(task.url).host ?: task.url.substringBefore('?')
                            }
                            RecentDownload(title = title, path = path)
                        }
                        .sortedByDescending { item ->
                            entries.firstOrNull { (task, state) ->
                                state.downloadState is DownloadState.Completed &&
                                    (state.downloadState as DownloadState.Completed).filePath == item.path
                            }?.first?.timeCreated ?: 0L
                        }
                        .take(3)
                counts to recent
            }
        }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    val (counts, recentDownloads) = snapshot

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(modifier = Modifier.padding(top = 18.dp)) {
            Text(stringResource(R.string.dashboard_greeting), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.dashboard_subtitle),
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                Text(stringResource(R.string.dashboard_add_link), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.dashboard_link_hint)) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboardText = clipboard.getText()?.text.orEmpty()
                                val candidate = findURLsFromString(clipboardText).firstOrNull()
                                    ?: clipboardText.trim()
                                if (candidate.isNotBlank()) url = candidate
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = stringResource(R.string.dashboard_paste_link),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        url.trim().takeIf(String::isNotBlank)?.let(onStartDownload)
                    }),
                )
                Button(
                    onClick = {
                        val candidate = url.trim()
                        if (candidate.isNotBlank()) onStartDownload(candidate)
                        else context.makeToast(R.string.dashboard_enter_link)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
                    enabled = url.isNotBlank(),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Text(stringResource(R.string.dashboard_start_download), modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    stringResource(R.string.dashboard_source_hint),
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_active),
                value = counts.active,
                onClick = onOpenDownloads,
            )
            SummaryTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_completed),
                value = counts.completed,
                onClick = onOpenDownloads,
            )
            SummaryTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_stopped),
                value = counts.stopped,
                onClick = onOpenDownloads,
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dashboard_recent),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onOpenFiles, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = stringResource(R.string.nav_files),
                    )
                }
            }
            if (recentDownloads.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.dashboard_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.dashboard_empty_body),
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                recentDownloads.forEach { recent ->
                    Card(
                        onClick = {
                            FileUtil.openFile(recent.path) { context.makeToast(R.string.file_unavailable) }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                recent.title,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Icon(Icons.Outlined.OpenInNew, contentDescription = stringResource(R.string.file_action_open))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryTile(
    modifier: Modifier = Modifier,
    label: String,
    value: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
