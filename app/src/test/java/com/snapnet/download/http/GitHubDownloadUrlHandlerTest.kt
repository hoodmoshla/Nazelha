package com.snapnet.download.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubDownloadUrlHandlerTest {
    @Test
    fun repositoryBlobIsSwitchedToPublicRawMode() {
        assertEquals(
            "https://github.com/owner/repo/blob/main/docs/guide.pdf?raw=1",
            GitHubDownloadUrlHandler.normalize(
                "https://github.com/owner/repo/blob/main/docs/guide.pdf"
            ),
        )
    }

    @Test
    fun existingRawParameterIsPreserved() {
        assertEquals(
            "https://github.com/owner/repo/blob/main/file.pdf?raw=0",
            GitHubDownloadUrlHandler.normalize(
                "https://github.com/owner/repo/blob/main/file.pdf?raw=0"
            ),
        )
    }

    @Test
    fun releaseAssetsAndUserAttachmentsStayOnTheirPublicDownloadRoutes() {
        val release = "https://github.com/owner/repo/releases/download/v1.2/tool.zip"
        val attachment = "https://github.com/user-attachments/assets/12345"
        assertEquals(release, GitHubDownloadUrlHandler.normalize(release))
        assertEquals(attachment, GitHubDownloadUrlHandler.normalize(attachment))
    }

    @Test
    fun unrelatedPagesAndCredentialUrlsAreNotNormalized() {
        assertNull(GitHubDownloadUrlHandler.normalize("https://github.com/owner/repo/issues"))
        assertNull(
            GitHubDownloadUrlHandler.normalize(
                "https://name:secret@github.com/owner/repo/blob/main/file.pdf"
            )
        )
        assertNull(GitHubDownloadUrlHandler.normalize("https://example.com/file.pdf"))
    }
}
