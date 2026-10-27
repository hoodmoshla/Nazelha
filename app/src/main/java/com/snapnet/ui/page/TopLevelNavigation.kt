package com.snapnet.ui.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.snapnet.R

/** The five persistent destinations, declared in the required logical RTL order. */
enum class TopLevelDestination(val route: String) {
    HOME(com.snapnet.ui.common.Route.HOME),
    DOWNLOADS(com.snapnet.ui.common.Route.DOWNLOADS),
    FILES(com.snapnet.ui.common.Route.FILES),
    BROWSER(com.snapnet.ui.common.Route.BROWSER),
    MORE(com.snapnet.ui.common.Route.MORE),
}

fun topLevelDestinationForRoute(route: String?): TopLevelDestination? =
    TopLevelDestination.entries.firstOrNull { it.route == route }

@Composable
fun BottomNavigationBar(
    selectedDestination: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val (label, icon) =
                when (destination) {
                    TopLevelDestination.HOME -> R.string.nav_home to Icons.Outlined.Home
                    TopLevelDestination.DOWNLOADS -> R.string.nav_downloads to Icons.Outlined.Download
                    TopLevelDestination.FILES -> R.string.nav_files to Icons.Outlined.FolderOpen
                    TopLevelDestination.BROWSER -> R.string.nav_browser to Icons.Outlined.Public
                    TopLevelDestination.MORE -> R.string.nav_more to Icons.Outlined.MoreHoriz
                }
            NavigationBarItem(
                modifier = Modifier.testTag("nav_${destination.name.lowercase()}"),
                selected = selectedDestination == destination,
                onClick = { onNavigate(destination) },
                icon = { Icon(imageVector = icon, contentDescription = null) },
                label = { Text(stringResource(label), maxLines = 1) },
                alwaysShowLabel = true,
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
            )
        }
    }
}
