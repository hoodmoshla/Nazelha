package com.snapnet.database.backup

import com.snapnet.database.objects.CommandTemplate
import com.snapnet.database.objects.DownloadedVideoInfo
import com.snapnet.database.objects.OptionShortcut
import com.snapnet.database.backup.BackupUtil.toJsonString
import com.snapnet.database.backup.BackupUtil.toURLListString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUtilRedactionTest {
    @Test
    fun `history backup and URL list contain no source URLs or signed query values`() {
        val entry =
            DownloadedVideoInfo(
                id = 1,
                videoTitle = "Sample",
                videoAuthor = "Example",
                videoUrl = "https://private.example/file?X-Amz-Signature=video-secret",
                thumbnailUrl = "https://images.example/thumb?token=image-secret",
                videoPath = "/storage/downloads/sample.mp4",
            )

        val json = listOf(entry).toJsonString()
        val urlList = listOf(entry).toURLListString()

        listOf(json, urlList).forEach { exported ->
            assertTrue(exported.contains("<redacted-url>"))
            assertFalse(exported.contains("private.example"))
            assertFalse(exported.contains("images.example"))
            assertFalse(exported.contains("video-secret"))
            assertFalse(exported.contains("image-secret"))
        }
    }

    @Test
    fun `command template and shortcut exports redact embedded URLs`() {
        val json =
            BackupUtil.exportTemplatesToJson(
                templates =
                    listOf(
                        CommandTemplate(
                            id = 1,
                            name = "custom",
                            template = "--input https://private.example/file?token=template-secret",
                        )
                    ),
                shortcuts =
                    listOf(
                        OptionShortcut(
                            id = 1,
                            option = "--header https://private.example/shortcut",
                        )
                    ),
            )

        assertTrue(json.contains("<redacted-url>"))
        assertFalse(json.contains("private.example"))
        assertFalse(json.contains("template-secret"))
    }
}
