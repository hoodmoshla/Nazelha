package com.snapnet.ui.page

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserPageUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun initialBrowserShowsAddressAndWelcomeInsteadOfAnEmptyBlackWebView() {
        composeRule.setContent {
            MaterialTheme {
                BrowserPage(onDownloadLink = {})
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("browser_address_input").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_start_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("browser_webview").assertDoesNotExist()
    }

    @Test
    fun enteringHttpsAddressReplacesStartPanelWithWebView() {
        composeRule.setContent {
            MaterialTheme {
                BrowserPage(onDownloadLink = {})
            }
        }

        composeRule.onNodeWithTag("browser_address_input").performTextInput("https://127.0.0.1")
        composeRule.onNodeWithTag("browser_go_button").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("browser_webview", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithTag("browser_webview", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("browser_start_panel").assertDoesNotExist()
    }
}
