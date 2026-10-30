package com.snapnet.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.snapnet.ui.common.Route
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopLevelNavigationIntegrationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun tappingHomeFromFilesImmediatelyShowsHomeWithoutSystemBack() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    val navController = rememberNavController()
                    val currentRoute =
                        navController.currentBackStackEntryAsState().value?.destination?.route

                    Column(Modifier.fillMaxSize()) {
                        NavHost(
                            navController = navController,
                            startDestination = Route.HOME,
                            modifier = Modifier.weight(1f),
                        ) {
                            composable(Route.HOME) {
                                Text("Home destination", Modifier.testTag("page_home"))
                            }
                            composable(Route.FILES) {
                                Text("Files destination", Modifier.testTag("page_files"))
                            }
                        }
                        BottomNavigationBar(
                            selectedDestination =
                                topLevelDestinationForRoute(currentRoute) ?: TopLevelDestination.HOME,
                            onNavigate = { navController.navigateToTopLevel(it) },
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_files").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("page_files").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("page_files").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_files").assertIsSelected()

        composeRule.onNodeWithTag("nav_home").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("page_home").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("page_home").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_home").assertIsSelected()
    }
}
