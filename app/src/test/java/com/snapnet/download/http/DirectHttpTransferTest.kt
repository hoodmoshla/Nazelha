package com.snapnet.download.http

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
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
    fun productionDirectHttpClientDoesNotStoreOrSendCookiesOrAuthorization() {
        val client = DirectHttpClientProvider.create().newBuilder().dns(Dns.SYSTEM).build()
        val productionResolver = DirectHttpLinkResolver(client)
        val productionDownloader = DirectHttpDownloader(client)
        val payload = "file".toByteArray()
        val requests = java.util.concurrent.CopyOnWriteArrayList<TestHttpRequest>()
        server.createContext("/seed-cookie") { _, output ->
            server.respond(
                output,
                200,
                headers = mapOf("Set-Cookie" to "session=secret; Path=/"),
                contentLength = 0L,
            )
        }
        server.createContext("/public.bin") { request, output ->
            requests += request
            val headers =
                mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Disposition" to "attachment; filename=public.bin",
                    "Content-Length" to payload.size.toString(),
                )
            if (request.method == "HEAD") {
                server.respond(output, 200, headers = headers, contentLength = null)
            } else {
                server.respond(output, 200, headers = headers, body = payload)
            }
        }
        val baseUrl = server.url("")

        client.newCall(Request.Builder().url("$baseUrl/seed-cookie").build()).execute().use {
            assertTrue(it.isSuccessful)
        }
        val file = productionResolver.resolve("$baseUrl/public.bin")

        assertNotNull(file)
        productionDownloader.download(
            "cookie-free-task",
            file!!,
            File(tempDirectory, "public.part"),
        ) { _, _ ->
        }

        assertArrayEquals(payload, File(tempDirectory, "public.part").readBytes())
        assertTrue(requests.any { it.method == "HEAD" })
        assertTrue(requests.any { it.method == "GET" })
        assertTrue(requests.all { it.header("Cookie") == null })
        assertTrue(requests.all { it.header("Authorization") == null })
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
    fun htmlPageLinksAreListedWithoutFetchingTheCandidate() {
        val html = "<a href='/files/manual.pdf?token=secret'>Manual</a>".toByteArray()
        val candidateRequests = AtomicInteger()
        server.createContext("/page") { request, output ->
            server.respond(
                output,
                200,
                headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
                body = if (request.method == "GET") html else byteArrayOf(),
                contentLength = html.size.toLong(),
            )
        }
        server.createContext("/files/manual.pdf") { _, output ->
            candidateRequests.incrementAndGet()
            server.respond(output, 200, headers = mapOf("Content-Type" to "application/pdf"))
        }

        val page = server.url("/page")
        assertNull(resolver.resolve(page))
        val candidates = resolver.discoverPageDownloads(page, "safe-page-id")

        assertEquals(1, candidates.size)
        assertEquals("Manual", candidates.single().label)
        assertTrue(candidates.single().url.contains("token=secret"))
        assertEquals(0, candidateRequests.get())
    }

    @Test
    fun metaRefreshPageDoesNotFollowOrFetchItsTarget() {
        val metaRefresh =
            "<html><head><meta http-equiv=\"refresh\" content=\"0; url='/target.bin'\"></head></html>"
                .toByteArray()
        val targetRequests = AtomicInteger()
        server.createContext("/meta-refresh") { request, output ->
            server.respond(
                output,
                200,
                headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
                body = if (request.method == "GET") metaRefresh else byteArrayOf(),
                contentLength = metaRefresh.size.toLong(),
            )
        }
        server.createContext("/target.bin") { _, output ->
            targetRequests.incrementAndGet()
            server.respond(
                output,
                200,
                headers = mapOf("Content-Type" to "application/octet-stream"),
            )
        }

        val page = server.url("/meta-refresh")

        assertNull(resolver.resolve(page))
        assertTrue(resolver.discoverPageDownloads(page, "meta-refresh-task").isEmpty())
        assertEquals(0, targetRequests.get())
    }

    @Test
    fun htmlResponseWithFileExtensionIsNotMisclassifiedAsDownload() {
        val getRequests = AtomicInteger()
        server.createContext("/fake.pdf") { request, output ->
            if (request.method == "GET") getRequests.incrementAndGet()
            server.respond(
                output,
                200,
                headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
                body = "<html>login page</html>".toByteArray(),
            )
        }

        assertNull(resolver.resolve(server.url("/fake.pdf")))
        assertEquals(0, getRequests.get())
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
    fun refreshedMetadataDoesNotReplaceValidatorBoundToInterruptedBytes() {
        val bytes = ByteArray(64 * 1024) { (it % 233).toByte() }
        val firstRequest = AtomicBoolean(true)
        server.createContext("/refreshed-validator.bin") { request, output ->
            val range = request.header("Range")
            if (range == null && firstRequest.compareAndSet(true, false)) {
                server.respond(
                    output,
                    200,
                    headers = mapOf("ETag" to "\"v1\""),
                    contentLength = bytes.size.toLong(),
                ) { body ->
                    body.write(bytes, 0, 8192)
                    body.flush()
                }
            } else {
                assertEquals("bytes=8192-", range)
                assertEquals("\"v1\"", request.header("If-Range"))
                val offset = range!!.substringAfter("bytes=").removeSuffix("-").toInt()
                server.respond(
                    output,
                    206,
                    headers =
                        mapOf(
                            "ETag" to "\"v1\"",
                            "Content-Range" to "bytes $offset-${bytes.lastIndex}/${bytes.size}",
                        ),
                    body = bytes.copyOfRange(offset, bytes.size),
                )
            }
        }
        val partial = File(tempDirectory, "refreshed.part")
        val original = directFile(server.url("/refreshed-validator.bin"), etag = "\"v1\"")
        org.junit.Assert.assertThrows(IOException::class.java) {
            downloader.download("refreshed-task", original, partial) { _, _ -> }
        }

        // Metadata has since been re-resolved to v2; it must not be used for old v1 bytes.
        val refreshed = original.copy(etag = "\"v2\"")
        downloader.download("refreshed-task", refreshed, partial) { _, _ -> }

        assertArrayEquals(bytes, partial.readBytes())
    }

    @Test
    fun changed206AfterIgnoredIfRangeResetsAndRetriesFreshOnce() {
        val oldBytes = ByteArray(48 * 1024) { 0x31 }
        val newBytes = ByteArray(48 * 1024) { 0x62 }
        val initial = AtomicBoolean(true)
        val rangeRequests = java.util.concurrent.atomic.AtomicInteger()
        val freshRequests = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/changed-range.bin") { request, output ->
            val range = request.header("Range")
            if (range == null && initial.compareAndSet(true, false)) {
                server.respond(
                    output,
                    200,
                    headers = mapOf("ETag" to "\"v1\""),
                    contentLength = oldBytes.size.toLong(),
                ) { body ->
                    body.write(oldBytes, 0, 8192)
                    body.flush()
                }
            } else if (range != null) {
                rangeRequests.incrementAndGet()
                assertEquals("\"v1\"", request.header("If-Range"))
                val offset = range.substringAfter("bytes=").removeSuffix("-").toInt()
                // Broken server ignores If-Range and sends the v2 suffix as a 206.
                server.respond(
                    output,
                    206,
                    headers =
                        mapOf(
                            "ETag" to "\"v2\"",
                            "Content-Range" to
                                "bytes $offset-${newBytes.lastIndex}/${newBytes.size}",
                        ),
                    body = newBytes.copyOfRange(offset, newBytes.size),
                )
            } else {
                freshRequests.incrementAndGet()
                server.respond(output, 200, headers = mapOf("ETag" to "\"v2\""), body = newBytes)
            }
        }
        val partial = File(tempDirectory, "changed-range.part")
        val info = directFile(server.url("/changed-range.bin"), etag = "\"v1\"")
        org.junit.Assert.assertThrows(IOException::class.java) {
            downloader.download("changed-range-task", info, partial) { _, _ -> }
        }

        downloader.download("changed-range-task", info, partial) { _, _ -> }

        assertEquals(1, rangeRequests.get())
        assertEquals(1, freshRequests.get())
        assertArrayEquals(newBytes, partial.readBytes())
    }

    @Test
    fun legacyPartialWithoutSidecarIsNeverAppended() {
        val oldBytes = ByteArray(4096) { 0x11 }
        val replacement = ByteArray(8192) { 0x22 }
        val partial = File(tempDirectory, "unbound.part")
        partial.writeBytes(oldBytes)
        server.createContext("/unbound.bin") { request, output ->
            assertNull("Unbound legacy bytes must not be resumed", request.header("Range"))
            server.respond(output, 200, headers = mapOf("ETag" to "\"new\""), body = replacement)
        }

        downloader.download(
            "unbound-task",
            directFile(server.url("/unbound.bin"), etag = "\"new\""),
            partial,
        ) { _, _ ->
        }

        assertArrayEquals(replacement, partial.readBytes())
    }

    @Test
    fun weakEtagFallsBackToLastModifiedForIfRange() {
        val bytes = ByteArray(40 * 1024) { (it % 197).toByte() }
        val firstRequest = AtomicBoolean(true)
        val modified = "Wed, 21 Oct 2015 07:28:00 GMT"
        server.createContext("/weak-etag.bin") { request, output ->
            val range = request.header("Range")
            val headers = mapOf("ETag" to "W/\"weak-v1\"", "Last-Modified" to modified)
            if (range == null && firstRequest.compareAndSet(true, false)) {
                server.respond(output, 200, headers, contentLength = bytes.size.toLong()) { body ->
                    body.write(bytes, 0, 8192)
                    body.flush()
                }
            } else {
                assertEquals("bytes=8192-", range)
                assertEquals(modified, request.header("If-Range"))
                val offset = range!!.substringAfter("bytes=").removeSuffix("-").toInt()
                server.respond(
                    output,
                    206,
                    headers + ("Content-Range" to "bytes $offset-${bytes.lastIndex}/${bytes.size}"),
                    bytes.copyOfRange(offset, bytes.size),
                )
            }
        }
        val info = directFile(server.url("/weak-etag.bin"), etag = "W/\"weak-v1\"")
        val partial = File(tempDirectory, "weak-etag.part")
        org.junit.Assert.assertThrows(IOException::class.java) {
            downloader.download("weak-etag-task", info, partial) { _, _ -> }
        }

        downloader.download("weak-etag-task", info, partial) { _, _ -> }

        assertArrayEquals(bytes, partial.readBytes())
    }

    @Test
    fun lastModifiedAloneSafelyResumesInterruptedTransfer() {
        val bytes = ByteArray(36 * 1024) { (it % 181).toByte() }
        val firstRequest = AtomicBoolean(true)
        val modified = "Thu, 22 Oct 2015 07:28:00 GMT"
        server.createContext("/last-modified.bin") { request, output ->
            val range = request.header("Range")
            val headers = mapOf("Last-Modified" to modified)
            if (range == null && firstRequest.compareAndSet(true, false)) {
                server.respond(output, 200, headers, contentLength = bytes.size.toLong()) { body ->
                    body.write(bytes, 0, 8192)
                    body.flush()
                }
            } else {
                assertEquals(modified, request.header("If-Range"))
                val offset = range!!.substringAfter("bytes=").removeSuffix("-").toInt()
                server.respond(
                    output,
                    206,
                    headers + ("Content-Range" to "bytes $offset-${bytes.lastIndex}/${bytes.size}"),
                    bytes.copyOfRange(offset, bytes.size),
                )
            }
        }
        val info = directFile(server.url("/last-modified.bin"))
        val partial = File(tempDirectory, "last-modified.part")
        org.junit.Assert.assertThrows(IOException::class.java) {
            downloader.download("last-modified-task", info, partial) { _, _ -> }
        }

        downloader.download("last-modified-task", info, partial) { _, _ -> }

        assertArrayEquals(bytes, partial.readBytes())
        val sidecar = File(tempDirectory, ".last-modified.part.resume")
        assertTrue("Resume metadata should be hidden beside the partial", sidecar.isFile)
        downloader.clearResumeMetadata(partial)
        assertFalse(sidecar.exists())
    }

    private fun directFile(url: String, etag: String? = null): DirectHttpFile =
        DirectHttpFile(
            downloadUrl = url,
            fileName = "test.bin",
            contentType = "application/octet-stream",
            sizeBytes = null,
            etag = etag,
        )

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
        assumeTrue(System.getenv("NAZELHA_LIVE_HTTP_TESTS") == "1")
        val file =
            DirectHttpLinkResolver()
                .resolve(
                    "https://github.com/hoodmoshla/Nazelha/blob/feature/direct-http-downloads-stage1-2026-10-27/README.md"
                )
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
