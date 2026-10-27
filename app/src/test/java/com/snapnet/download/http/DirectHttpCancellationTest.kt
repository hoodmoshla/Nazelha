package com.snapnet.download.http

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectHttpCancellationTest {
    @Test
    fun `resolver does not probe after its task is canceled`() {
        val resolver = DirectHttpLinkResolver()

        assertThrows(CancellationException::class.java) {
            resolver.resolve("https://example.com/file.bin", "task") { false }
        }
    }

    @Test
    fun `downloader does not start a request after its task is canceled`() {
        val root = Files.createTempDirectory("nazelha-http-cancel").toFile()
        try {
            val partial = File(root, "direct-http-canceled.part")
            val file =
                DirectHttpFile(
                    downloadUrl = "https://example.com/file.bin",
                    fileName = "file.bin",
                    contentType = "application/octet-stream",
                )

            assertThrows(CancellationException::class.java) {
                DirectHttpDownloader()
                    .download(
                        taskId = "task",
                        file = file,
                        partialFile = partial,
                        shouldContinue = { false },
                        onProgress = { _, _ -> },
                    )
            }
            assertFalse(partial.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
