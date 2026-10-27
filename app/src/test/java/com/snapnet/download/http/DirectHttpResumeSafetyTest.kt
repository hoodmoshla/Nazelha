package com.snapnet.download.http

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectHttpResumeSafetyTest {
    private fun client() = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).build()

    @Test fun omittedValidatorOnResumedResponseRestartsInsteadOfMixingVersions() {
        val server = TestHttpServer()
        val folder = kotlin.io.path.createTempDirectory("nazelha-safe-resume").toFile()
        try {
            val old = ByteArray(32 * 1024) { 0x11 }
            val replacement = ByteArray(32 * 1024) { 0x22 }
            val initial = AtomicBoolean(true)
            val ranges = AtomicInteger()
            server.createContext("/file.bin") { request, output ->
                val range = request.header("Range")
                if (range == null && initial.compareAndSet(true, false)) {
                    server.respond(output, 200, mapOf("ETag" to "\"old\""), contentLength = old.size.toLong()) { body ->
                        body.write(old, 0, 4096)
                        body.flush()
                    }
                } else if (range != null) {
                    ranges.incrementAndGet()
                    val start = range.substringAfter("bytes=").removeSuffix("-").toInt()
                    server.respond(output, 206,
                        mapOf("Content-Range" to "bytes $start-${replacement.lastIndex}/${replacement.size}"),
                        replacement.copyOfRange(start, replacement.size))
                } else {
                    server.respond(output, 200, mapOf("ETag" to "\"new\""), replacement)
                }
            }
            val downloader = DirectHttpDownloader(client())
            val file = DirectHttpFile(server.url("/file.bin"), "file.bin", "application/octet-stream", old.size.toLong(), true, "\"old\"")
            val partial = File(folder, "file.part")
            assertThrows(IOException::class.java) { downloader.download("task", file, partial) { _, _ -> } }
            downloader.download("task", file, partial) { _, _ -> }
            assertEquals(1, ranges.get())
            assertArrayEquals(replacement, partial.readBytes())
        } finally {
            server.close()
            folder.deleteRecursively()
        }
    }

    @Test fun unknownTotal206CannotBePublishedAsACompleteFile() {
        val server = TestHttpServer()
        val folder = kotlin.io.path.createTempDirectory("nazelha-unknown-range").toFile()
        try {
            val requests = AtomicInteger()
            server.createContext("/fragment.bin") { _, output ->
                requests.incrementAndGet()
                server.respond(output, 206, mapOf("Content-Range" to "bytes 0-3/*"), byteArrayOf(1, 2, 3, 4))
            }
            val downloader = DirectHttpDownloader(client())
            val file = DirectHttpFile(server.url("/fragment.bin"), "fragment.bin", "application/octet-stream", null, true)
            val partial = File(folder, "fragment.part")
            assertThrows(IOException::class.java) { downloader.download("task", file, partial) { _, _ -> } }
            assertEquals(2, requests.get())
            assertEquals(0L, partial.length())
        } finally {
            server.close()
            folder.deleteRecursively()
        }
    }

    @Test fun rangeOffsetsBeyondTwoGiBAreHandledAsLongs() {
        val server = TestHttpServer()
        val folder = kotlin.io.path.createTempDirectory("nazelha-large-offset").toFile()
        try {
            val offset = 3_000_000_000L
            val suffix = ByteArray(1024) { (it % 251).toByte() }
            val total = offset + suffix.size
            server.createContext("/large.bin") { request, output ->
                if (request.header("Range") == null) {
                    server.respond(output, 200, mapOf("ETag" to "\"large\""), contentLength = 16L) { body ->
                        body.write(1)
                        body.flush()
                    }
                } else {
                    assertEquals("bytes=$offset-", request.header("Range"))
                    server.respond(output, 206, mapOf(
                        "ETag" to "\"large\"",
                        "Content-Range" to "bytes $offset-${total - 1}/$total",
                    ), suffix)
                }
            }
            val downloader = DirectHttpDownloader(client())
            val file = DirectHttpFile(server.url("/large.bin"), "large.bin", "application/octet-stream")
            val partial = File(folder, "large.part")
            assertThrows(IOException::class.java) { downloader.download("large-task", file, partial) { _, _ -> } }
            // Sparse extension tests 64-bit offsets, not the integrity of a real multi-GB payload.
            java.io.RandomAccessFile(partial, "rw").use { it.setLength(offset) }
            assertEquals(total, downloader.download("large-task", file, partial) { _, _ -> })
            val actualSuffix = ByteArray(suffix.size)
            java.io.RandomAccessFile(partial, "r").use {
                it.seek(offset)
                it.readFully(actualSuffix)
            }
            assertArrayEquals(suffix, actualSuffix)
        } finally {
            server.close()
            folder.deleteRecursively()
        }
    }
}
