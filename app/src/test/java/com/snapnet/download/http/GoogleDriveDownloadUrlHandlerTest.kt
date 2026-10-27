package com.snapnet.download.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleDriveDownloadUrlHandlerTest {
    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz"

    @Test
    fun `file share becomes public download endpoint`() {
        assertEquals(
            "https://drive.google.com/uc?export=download&id=$id",
            GoogleDriveDownloadUrlHandler.normalize("https://drive.google.com/file/d/$id/view?usp=sharing"),
        )
    }

    @Test
    fun `open and uc links preserve file id but discard unrelated export choices`() {
        assertEquals(
            "https://drive.google.com/uc?export=download&id=$id",
            GoogleDriveDownloadUrlHandler.normalize("https://drive.google.com/open?id=$id"),
        )
        assertEquals(
            "https://drive.google.com/uc?export=download&id=$id",
            GoogleDriveDownloadUrlHandler.normalize(
                "https://drive.google.com/uc?export=bad&id=$id&usp=sharing"
            ),
        )
    }

    @Test
    fun `folders documents malformed ids and credential urls are not normalized`() {
        assertNull(GoogleDriveDownloadUrlHandler.normalize("https://drive.google.com/drive/folders/$id"))
        assertNull(GoogleDriveDownloadUrlHandler.normalize("https://docs.google.com/document/d/$id/edit"))
        assertNull(GoogleDriveDownloadUrlHandler.normalize("https://drive.google.com/file/d/short/view"))
        assertNull(
            GoogleDriveDownloadUrlHandler.normalize(
                "https://user:secret@drive.google.com/file/d/$id/view"
            )
        )
    }
}
