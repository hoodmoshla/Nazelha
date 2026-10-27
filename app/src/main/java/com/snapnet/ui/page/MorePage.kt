package com.snapnet.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snapnet.R
import com.snapnet.ui.common.Route

private data class MoreAction(
    val title: Int,
    val description: Int,
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun MorePage(onNavigateTo: (String) -> Unit, modifier: Modifier = Modifier) {
    val actions =
        listOf(
            MoreAction(R.string.more_download_history, R.string.more_download_history_desc, Route.DOWNLOAD_HISTORY, Icons.Outlined.History),
            MoreAction(R.string.more_custom_tasks, R.string.more_custom_tasks_desc, Route.TASK_LIST, Icons.Outlined.Terminal),
            MoreAction(R.string.settings, R.string.more_settings_desc, Route.SETTINGS, Icons.Outlined.Settings),
        )
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.nav_more), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.more_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        actions.forEach { action ->
            Card(
                onClick = { onNavigateTo(action.route) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(stringResource(action.title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(action.description),
                            modifier = Modifier.padding(top = 3.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
