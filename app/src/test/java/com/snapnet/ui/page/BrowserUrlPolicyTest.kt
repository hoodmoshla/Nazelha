package com.snapnet.ui.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlPolicyTest {
    @Test
    fun inAppBrowserAllowsOnlyHttpsNavigation() {
        assertTrue(isAllowedInAppBrowserScheme("https"))
        listOf("http", "file", "content", "javascript", "intent", "ftp", null).forEach {
            assertFalse(
                "Expected $it to be blocked in the in-app browser",
                isAllowedInAppBrowserScheme(it),
            )
        }
    }

    @Test
    fun allowsOnlySafeExternalSchemesAfterUserGesture() {
        listOf("mailto", "tel", "sms", "geo").forEach { scheme ->
            assertTrue(
                "Expected $scheme to be allowed after a user gesture",
                isSafeExternalBrowserScheme(scheme, true),
            )
        }
    }

    @Test
    fun blocksDangerousAndUnknownSchemesEvenAfterUserGesture() {
        listOf("file", "content", "javascript", "intent", "data", "ftp", "http", "https", null)
            .forEach { scheme ->
                assertFalse(
                    "Expected $scheme to remain inside/blocked",
                    isSafeExternalBrowserScheme(scheme, true),
                )
            }
    }

    @Test
    fun blocksExternalActionsWithoutUserGesture() {
        listOf("mailto", "tel", "sms", "geo").forEach { scheme ->
            assertFalse(
                "Expected $scheme to require a user gesture",
                isSafeExternalBrowserScheme(scheme, false),
            )
        }
    }
}
