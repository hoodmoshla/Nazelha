package com.snapnet.ui.page

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.createComposeRule
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection as ComposeLayoutDirection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BottomNavigationRtlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun destinationsArePlacedFromHomeOnTheRightToMoreOnTheLeft() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides ComposeLayoutDirection.Rtl) {
                MaterialTheme {
                    BottomNavigationBar(
                        selectedDestination = TopLevelDestination.HOME,
                        onNavigate = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val rightToLeftOrder =
            listOf("home", "downloads", "files", "browser", "more")
                .map { name ->
                    composeRule
                        .onNodeWithTag("nav_$name")
                        .fetchSemanticsNode()
                        .boundsInRoot
                        .center
                        .x
                }
        assertTrue("Navigation items should descend in x from right to left: $rightToLeftOrder", rightToLeftOrder.zipWithNext().all { (right, left) -> right > left })
    }

    @Test
    fun clickingEachNavigationItemSelectsThatDestination() {
        val selected = androidx.compose.runtime.mutableStateOf(TopLevelDestination.HOME)
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides ComposeLayoutDirection.Rtl) {
                MaterialTheme {
                    BottomNavigationBar(
                        selectedDestination = selected.value,
                        onNavigate = { selected.value = it },
                    )
                }
            }
        }

        listOf("downloads", "files", "browser", "more", "home").forEach { name ->
            val item = composeRule.onNodeWithTag("nav_$name")
            item.performClick()
            item.assertIsSelected()
        }
    }
}
