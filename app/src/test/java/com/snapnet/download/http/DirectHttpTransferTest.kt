package com.snapnet.download.http

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class DirectHttpTransferTest {
    private lateinit var server: TestHttpServer
    private lateinit var transferExecutor: java.util.concurrent.ExecutorService
    private lateinit var resolver: DirectHttpLinkResolver
    private lateinit var downloader: DirectHttpDownloader
    private lateinit var tempDirectory: File

    @Before
    fun setUp() {
        server = TestHttpServer()
        transferExecutor = Executors.newSingleThreadExecutor()
        val testClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .build()
        resolver = DirectHttpLinkResolver(testClient)
        downloader = DirectHttpDownloader(testClient)
        tempDirectory = kotlin.io.path.createTempDirectory("snapnet-http-test").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        transferExecutor.shutdownNow()
        tempDirectory.deleteRecursively()
    }

    @Test
    fun resolvesRedirectAndRfc5987FilenameFromHeaders() {
        server.createContext("/redirect") { _, output ->
            server.respond(output, 302, headers = mapOf("Location" to "/files/report.pdf"))
        }
        server.createContext("/files/report.pdf") { request, output ->
            val headers =
                mapOf(
                    "Content-Type" to "application/pdf",
                    "Content-Disposition" to
                        "attachment; filename=report.pdf; filename*=UTF-8''rapport%20final.pdf",
                    "Content-Length" to "128",
                    "Accept-Ranges" to "bytes",
                    "ETag" to "\"v1\"",
                )
            if (request.method == "HEAD") {
                server.respond(output, 200, headers = headers, contentLength = null)
            } else {
                server.respond(
                    output,
                    200,
                    headers = headers,
                    body = ByteArray(128) { it.toByte() },
                    contentLength = 128L,
                )
            }
        }

        val file = resolver.resolve(server.url("/redirect"))

        assertNotNull(file)
        assertEquals("rapport final.pdf", file!!.fileName)
        assertEquals("application/pdf", file.contentType)
        assertEquals(128L, file.sizeBytes)
        assertTrue(file.supportsRanges)
        assertEquals("\"v1\"", file.etag)
        assertTrue(file.downloadUrl.endsWith("/files/report.pdf"))
    }

    @Test
    fun htmlPagesStayOnExistingVideoExtractorPath() {
        server.createContext("/article") { _, output ->
            server.respond(
                output,
                200,
                headers =
                    mapOf("Content-Type" to "text/html; charset=utf-8", "Content-Length" to "73"),
                contentLength = null,
            )
        }

        assertNull(resolver.resolve(server.url("/article")))
    }

    @Test
    fun existingVideoPlatformsSkipTheHttpProbe() {
        assertFalse(DirectHttpLinkResolver.shouldProbeHost("www.youtube.com"))
        assertFalse(DirectHttpLinkResolver.shouldProbeHost("m.facebook.com"))
        assertFalse(DirectHttpLinkResolver.shouldProbeHost("x.com"))
        assertFalse(DirectHttpLinkResolver.shouldProbeHost("t.co"))
        assertTrue(DirectHttpLinkResolver.shouldProbeHost("raw.githubusercontent.com"))
    }

    @Test
    fun rejectsCredentialsInInitialAndRedirectUrls() {
        org.junit.Assert.assertThrows(IOException::class.java) {
            resolver.resolve("http://user:secret@127.0.0.1:${server.port}/file.bin")
        }
        server.createContext("/credential-redirect") { _, output ->
            server.respond(
                output,
                302,
                headers = mapOf("Location" to "http://user:secret@example.com/file.bin"),
            )
        }
        org.junit.Assert.assertThrows(IOException::class.java) {
            resolver.resolve(server.url("/credential-redirect"))
        }
    }

    @Test
    fun fallsBackToOneByteRangeWhenHeadIsUnsupportedAndSizeIsUnknown() {
        server.createContext("/download.bin") { request, output ->
            if (request.method == "HEAD") {
                server.respond(output, 405, contentLength = 0L)
            } else {
                assertEquals("bytes=0-0", request.header("Range"))
                server.respond(
                    output,
                    206,
                    headers =
                        mapOf(
                            "Content-Type" to "application/octet-stream",
                            "Content-Range" to "bytes 0-0/*",
                            "Accept-Ranges" to "bytes",
                        ),
                    body = byteArrayOf(0x41),
                )
            }
        }

        val file = resolver.resolve(server.url("/download.bin"))

        assertNotNull(file)
        assertNull("The one-byte range must not be mistaken for the total size", file!!.sizeBytes)
        assertTrue(file.supportsRanges)
    }

    @Test
    fun resumesAfterConnectionDropsAndRetainsPartialBytes() {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val firstRequest = AtomicBoolean(true)
        server.createContext("/resume.bin") { request, output ->
            val headers =
                mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Accept-Ranges" to "bytes",
                    "ETag" to "\"stable-v1\"",
                )
            val range = request.header("Range")
            if (range == null && firstRequest.compareAndSet(true, false)) {
                server.respond(output, 200, headers, contentLength = bytes.size.toLong()) { body ->
                    body.write(bytes, 0, 24 * 1024)
                    body.flush()
                    // Return before the advertised Content-Length to simulate a network drop.
                }
            } else if (range == null) {
                server.respond(output, 200, headers, body = bytes)
            } else {
                assertEquals("\"stable-v1\"", request.header("If-Range"))
                val offset = range.removePrefix("bytes=").removeSuffix("-").toInt()
                server.respond(
                    output,
                    206,
                    headers + ("Content-Range" to "bytes $offset-${bytes.lastIndex}/${bytes.size}"),
                    body = bytes.copyOfRange(offset, bytes.size),
                )
            }
        }
        val info =
            DirectHttpFile(
                downloadUrl = server.url("/resume.bin"),
                fileName = "resume.bin",
                contentType = "application/octet-stream",
                sizeBytes = bytes.size.toLong(),
                supportsRanges = true,
                etag = "\"stable-v1\"",
            )
        val partial = File(tempDirectory, "resume.part")

        org.junit.Assert.assertThrows(IOException::class.java) {
            downloader.download("resume-task", info, partial) { _, _ -> }
        }
        val savedPartialSize = partial.length()
        assertTrue("Interrupted bytes should remain available for resume", savedPartialSize > 0L)
        assertTrue(savedPartialSize < bytes.size.toLong())

        val finalSize = downloader.download("resume-task", info, partial) { _, _ -> }

        assertEquals(bytes.size.toLong(), finalSize)
        assertArrayEquals(bytes, partial.readBytes())
    }

    @Test
    fun downloadsChunkedResponseWhenTotalSizeIsUnknown() {
        val bytes = ByteArray(4096) { (it % 239).toByte() }
        server.createContext("/chunked.bin") { _, output ->
            server.respond(
                output,
                200,
                headers = mapOf("Content-Type" to "application/octet-stream"),
                body = bytes,
                chunked = true,
            )
        }
        val info =
            DirectHttpFile(
                downloadUrl = server.url("/chunked.bin"),
                fileName = "chunked.bin",
                contentType = "application/octet-stream",
                sizeBytes = null,
            )
        val partial = File(tempDirectory, "chunked.part")
        val seenTotals = mutableListOf<Long?>()

        val size =
            downloader.download("unknown-size-task", info, partial) { _, total ->
                seenTotals.add(total)
            }

        assertEquals(bytes.size.toLong(), size)
        assertArrayEquals(bytes, partial.readBytes())
        assertTrue(seenTotals.isEmpty() || seenTotals.all { it == null })
    }

    @Test
    fun cancelStopsTheHttpCallAndLeavesPartialForResume() {
        val bytes = ByteArray(512 * 1024) { (it % 241).toByte() }
        val responseStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        server.createContext("/cancel.bin") { _, output ->
            server.respond(
                output,
                200,
                headers =
                    mapOf("Content-Type" to "application/octet-stream", "ETag" to "\"cancel-v1\""),
                contentLength = bytes.size.toLong(),
            ) { body ->
                body.write(bytes, 0, 64 * 1024)
                body.flush()
                responseStarted.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
                try {
                    body.write(bytes, 64 * 1024, bytes.size - 64 * 1024)
                } catch (_: IOException) {
                    // Closing the client call is expected to interrupt this response writer.
                }
            }
        }
        val info =
            DirectHttpFile(
                downloadUrl = server.url("/cancel.bin"),
                fileName = "cancel.bin",
                contentType = "application/octet-stream",
                sizeBytes = bytes.size.toLong(),
                etag = "\"cancel-v1\"",
            )
        val partial = File(tempDirectory, "cancel.part")
        val wroteBytes = CountDownLatch(1)
        val future =
            transferExecutor.submit<Long> {
                downloader.download("cancel-task", info, partial) { downloaded, _ ->
                    if (downloaded > 0L) wroteBytes.countDown()
                }
            }

        assertTrue(responseStarted.await(5, TimeUnit.SECONDS))
        assertTrue(wroteBytes.await(5, TimeUnit.SECONDS))
        downloader.cancel("cancel-task")
        releaseServer.countDown()
        val failure =
            org.junit.Assert.assertThrows(ExecutionException::class.java) {
                future.get(5, TimeUnit.SECONDS)
            }

        assertTrue(failure.cause is CancellationException)
        assertTrue(partial.length() > 0L)
    }

    @Test
    fun publicGithubRawFileCanBeResolvedAndDownloaded() {
        assumeTrue(System.getenv("SNAPNET_LIVE_HTTP_TESTS") == "1")
        val file =
            DirectHttpLinkResolver()
                .resolve("https://raw.githubusercontent.com/hoodmoshla/SnapNet/v1.0.5/README.md")
        assertNotNull(file)
        val resolved = file!!
        assertEquals("README.md", resolved.fileName)
        assertTrue(resolved.sizeBytes == null || resolved.sizeBytes!! > 0L)

        val downloadedFile = File(tempDirectory, "github-readme.part")
        val downloadedSize =
            DirectHttpDownloader().download("github-readme", resolved, downloadedFile) { _, _ -> }

        assertTrue(downloadedSize > 0L)
        assertEquals(downloadedSize, downloadedFile.length())
        assertTrue(downloadedFile.readBytes().isNotEmpty())
    }

    @Test
    fun rejectsNonHttpRedirectTargets() {
        server.createContext("/unsafe-redirect") { _, output ->
            server.respond(output, 302, headers = mapOf("Location" to "file:///etc/passwd"))
        }

        org.junit.Assert.assertThrows(IOException::class.java) {
            resolver.resolve(server.url("/unsafe-redirect"))
        }
    }
}
