package com.snapnet.download.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DropboxDownloadUrlHandlerTest {
    @Test
    fun legacySharedFileLinkGetsDownloadParameter() {
        assertEquals(
            "https://www.dropbox.com/s/abc123/guide.pdf?dl=1",
            DropboxDownloadUrlHandler.normalize("https://www.dropbox.com/s/abc123/guide.pdf?dl=0"),
        )
    }

    @Test
    fun newerSharedFileLinkPreservesRlkeyUnicodeAndFragment() {
        assertEquals(
            "https://dropbox.com/scl/fi/token/file-%E2%9C%93.png?rlkey=secret-link-key&dl=1#preview",
            DropboxDownloadUrlHandler.normalize(
                "https://dropbox.com/scl/fi/token/file-%E2%9C%93.png?rlkey=secret-link-key&dl=0#preview"
            ),
        )
    }

    @Test
    fun duplicateDownloadAndRenderControlsAreReplaced() {
        assertEquals(
            "https://www.dropbox.com/s/id/file.zip?rlkey=key&keep=yes&dl=1",
            DropboxDownloadUrlHandler.normalize(
                "https://www.dropbox.com/s/id/file.zip?rlkey=key&dl=0&raw=1&dl=0&raw=0&keep=yes"
            ),
        )
    }

    @Test
    fun unrelatedRoutesSchemesAndDomainsAreRejected() {
        assertNull(DropboxDownloadUrlHandler.normalize("https://www.dropbox.com/home"))
        assertNull(DropboxDownloadUrlHandler.normalize("https://www.dropbox.com/s/id/folder/extra"))
        assertNull(DropboxDownloadUrlHandler.normalize("http://www.dropbox.com/s/id/file.pdf"))
        assertNull(DropboxDownloadUrlHandler.normalize("https://dropbox.com.evil.example/s/id/file.pdf"))
        assertNull(DropboxDownloadUrlHandler.normalize("https://evil-dropbox.com/s/id/file.pdf"))
    }

    @Test
    fun credentialBearingLinksAreRejected() {
        assertNull(
            DropboxDownloadUrlHandler.normalize(
                "https://name:secret@dropbox.com/s/id/file.pdf"
            )
        )
    }
}
