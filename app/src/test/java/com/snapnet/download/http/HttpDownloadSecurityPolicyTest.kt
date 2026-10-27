package com.snapnet.download.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDownloadSecurityPolicyTest {
    @Test
    fun onlyHttpAndHttpsSourcesWithoutEmbeddedCredentialsAreSupported() {
        assertTrue(HttpDownloadSecurityPolicy.isSupportedSourceUrl("https://example.com/video"))
        assertTrue(HttpDownloadSecurityPolicy.isSupportedSourceUrl("http://example.com/file.bin"))
        listOf(
                "file:///tmp/video.mp4",
                "content://downloads/item/1",
                "javascript:alert(1)",
                "intent://example.com/#Intent;end",
                "ftp://example.com/file",
                "https://user:secret@example.com/video",
            )
            .forEach {
                assertFalse(
                    "Unexpectedly accepted $it",
                    HttpDownloadSecurityPolicy.isSupportedSourceUrl(it),
                )
            }
    }

    @Test
    fun cleartextMustBeConfirmedForEachHttpSourceButNotHttps() {
        assertTrue(HttpDownloadSecurityPolicy.requiresUserConfirmation("http://example.com/file"))
        assertFalse(HttpDownloadSecurityPolicy.requiresUserConfirmation("https://example.com/file"))
        assertEquals(
            "example.com",
            HttpDownloadSecurityPolicy.hostForDisplay("http://example.com/file?token=hidden"),
        )
    }

    @Test
    fun onlyAConfirmedDirectHttpFileMayStartOverCleartext() {
        assertFalse(HttpDownloadSecurityPolicy.mayStartTask("http://example.com/file", true, false))
        assertTrue(HttpDownloadSecurityPolicy.mayStartTask("http://example.com/file", true, true))
        assertFalse(HttpDownloadSecurityPolicy.mayStartTask("http://example.com/page", false, true))
        assertFalse(
            HttpDownloadSecurityPolicy.mayStartTask(
                "http://user:secret@example.com/file",
                true,
                true,
            )
        )
        assertTrue(
            HttpDownloadSecurityPolicy.mayStartTask("https://example.com/video", false, false)
        )
    }

    @Test
    fun ytDlpIsHttpsOnlyEvenWhenDirectHttpFilesAreAllowedWithConsent() {
        assertFalse(HttpDownloadSecurityPolicy.mayUseYtDlp("http://example.com/video"))
        assertTrue(HttpDownloadSecurityPolicy.mayUseYtDlp("https://example.com/video"))
        assertFalse(HttpDownloadSecurityPolicy.mayUseYtDlp("file:///tmp/video"))
        assertFalse(HttpDownloadSecurityPolicy.mayUseYtDlp("https://user:secret@example.com/video"))
    }

    @Test
    fun permitsHttpToHttpsRedirectButBlocksHttpsDowngradeAndUnsafeTargets() {
        assertTrue(
            HttpDownloadSecurityPolicy.mayFollowRedirect(
                "http://example.com/a",
                "https://example.com/b",
            )
        )
        assertTrue(HttpDownloadSecurityPolicy.mayFollowRedirect("http://example.com/a", "/b"))
        assertFalse(
            HttpDownloadSecurityPolicy.mayFollowRedirect(
                "https://example.com/a",
                "http://example.com/b",
            )
        )
        assertFalse(
            HttpDownloadSecurityPolicy.mayFollowRedirect(
                "https://example.com/a",
                "file:///etc/passwd",
            )
        )
        assertFalse(
            HttpDownloadSecurityPolicy.mayFollowRedirect(
                "http://example.com/a",
                "http://user:secret@example.com/b",
            )
        )
    }
}
