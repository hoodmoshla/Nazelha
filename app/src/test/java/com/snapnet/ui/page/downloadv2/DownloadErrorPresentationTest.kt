package com.snapnet.ui.page.downloadv2

import com.hoodmoshla.nazelha.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadErrorPresentationTest {
    @Test
    fun unknownXFailuresGetActionableMessageForBothHostnamesOnly() {
        val failure = IllegalStateException("Unclassified extractor response")

        assertEquals(
            R.string.download_error_x_unknown,
            downloadErrorMessageRes(failure, "https://x.com/user/status/12345"),
        )
        assertEquals(
            R.string.download_error_x_unknown,
            downloadErrorMessageRes(failure, "https://twitter.com/user/status/12345"),
        )
        assertEquals(
            R.string.download_error_msg,
            downloadErrorMessageRes(failure, "https://x.com.evil.example/user/status/12345"),
        )
    }

    @Test
    fun knownExtractionAndRequestFailuresKeepTheirSpecificArabicMessages() {
        assertEquals(
            R.string.download_error_formats,
            downloadErrorMessageRes(
                IllegalStateException("No video formats found in yt-dlp response"),
                "https://x.com/user/status/12345",
            ),
        )
        assertEquals(
            R.string.download_error_auth,
            downloadErrorMessageRes(
                IllegalStateException("HTTP Error 401: Unauthorized"),
                "https://twitter.com/user/status/12345",
            ),
        )
        assertEquals(
            R.string.download_error_rate_limited,
            downloadErrorMessageRes(
                IllegalStateException("HTTP Error 429: Too Many Requests"),
                "https://x.com/user/status/12345",
            ),
        )
    }

    @Test
    fun diagnosticIdIsKeptAndCanBeExtractedFromTheCauseChain() {
        val id = "a52a753e29aef9db-MCT"
        val failure =
            IllegalStateException("wrapper", RuntimeException("Something went wrong. ($id)"))
        assertEquals(id, downloadErrorDiagnosticId(failure))
        assertNull(downloadErrorDiagnosticId(IllegalStateException("no diagnostic ID")))

        val redacted =
            redactDownloadErrorDetails(
                "Request failed: https://x.com/user/status/1?token=do-not-show ($id)"
            )
        assertTrue(redacted.contains(id))
        assertFalse(redacted.contains("do-not-show"))
    }
}
