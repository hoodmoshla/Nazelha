package com.snapnet.download.http

import java.io.File
import java.io.IOException
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectHttpRedirectGuardTest {
    @Test
    fun resolverStopsBeforeIssuingHttpRequestAfterHttpsDowngrade() {
        val requests = mutableListOf<String>()
        val client = scriptedClient { request ->
            requests += request.url.toString()
            response(request, 302, mapOf("Location" to "http://example.com/plain.bin"))
        }

        assertThrows(IOException::class.java) {
            DirectHttpLinkResolver(client).resolve("https://example.com/start.bin")
        }

        assertEquals(listOf("https://example.com/start.bin"), requests)
    }

    @Test
    fun resolverRejectsHttpCredentialQueryBeforeIssuingRequest() {
        val requests = mutableListOf<String>()
        val client = scriptedClient { request ->
            requests += request.url.toString()
            response(request, 200)
        }

        assertThrows(IOException::class.java) {
            DirectHttpLinkResolver(client).resolve("http://example.com/file?access_token=secret")
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun downloaderRejectsHttpCredentialQueryBeforeIssuingRequest() {
        val requests = mutableListOf<String>()
        val client = scriptedClient { request ->
            requests += request.url.toString()
            response(request, 200)
        }
        val directory = kotlin.io.path.createTempDirectory("nazelha-credential-query-test").toFile()

        try {
            val partial = File(directory, "download.part")
            val file =
                DirectHttpFile(
                    downloadUrl = "http://example.com/file?token=secret",
                    fileName = "download.bin",
                    contentType = "application/octet-stream",
                )

            assertThrows(IOException::class.java) {
                DirectHttpDownloader(client).download("task-id", file, partial) { _, _ -> }
            }

            assertTrue(requests.isEmpty())
            assertFalse(partial.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resolverAllowsHttpUpgradeAndUsesOnlyTheHttpsTargetAfterRedirect() {
        val requests = mutableListOf<String>()
        val client = scriptedClient { request ->
            requests += request.url.toString()
            if (request.url.scheme == "http") {
                response(request, 302, mapOf("Location" to "https://example.com/files/manual.pdf"))
            } else {
                response(
                    request,
                    200,
                    mapOf(
                        "Content-Type" to "application/pdf",
                        "Content-Disposition" to "attachment; filename=manual.pdf",
                        "Content-Length" to "123",
                    ),
                )
            }
        }

        val file = DirectHttpLinkResolver(client).resolve("http://example.com/start")

        assertEquals("manual.pdf", file?.fileName)
        assertEquals("https://example.com/files/manual.pdf", file?.downloadUrl)
        assertEquals(
            listOf("http://example.com/start", "https://example.com/files/manual.pdf"),
            requests,
        )
    }

    @Test
    fun downloaderStopsBeforeIssuingHttpRequestAfterHttpsDowngrade() {
        val requests = mutableListOf<String>()
        val client = scriptedClient { request ->
            requests += request.url.toString()
            response(request, 302, mapOf("Location" to "http://example.com/plain.bin"))
        }
        val directory = kotlin.io.path.createTempDirectory("nazelha-redirect-test").toFile()

        try {
            val partial = File(directory, "download.part")
            val file =
                DirectHttpFile(
                    downloadUrl = "https://example.com/start.bin",
                    fileName = "download.bin",
                    contentType = "application/octet-stream",
                )

            assertThrows(IOException::class.java) {
                DirectHttpDownloader(client).download("task-id", file, partial) { _, _ -> }
            }

            assertEquals(listOf("https://example.com/start.bin"), requests)
            assertFalse(partial.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun downloaderAllowsHttpUpgradeAndStoresResponseBytes() {
        val payload = "safe over https".toByteArray()
        val requests = mutableListOf<String>()
        val client = scriptedClient { request ->
            requests += request.url.toString()
            if (request.url.scheme == "http") {
                response(
                    request,
                    302,
                    mapOf("Location" to "https://example.com/files/download.bin"),
                )
            } else {
                response(request, 200, mapOf("Content-Length" to payload.size.toString()), payload)
            }
        }
        val directory = kotlin.io.path.createTempDirectory("nazelha-upgrade-test").toFile()

        try {
            val partial = File(directory, "download.part")
            val file =
                DirectHttpFile(
                    downloadUrl = "http://example.com/start",
                    fileName = "download.bin",
                    contentType = "application/octet-stream",
                )

            val size = DirectHttpDownloader(client).download("task-id", file, partial) { _, _ -> }

            assertEquals(payload.size.toLong(), size)
            assertArrayEquals(payload, partial.readBytes())
            assertEquals(
                listOf("http://example.com/start", "https://example.com/files/download.bin"),
                requests,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun scriptedClient(respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .addInterceptor { chain -> respond(chain.request()) }
            .build()

    private fun response(
        request: Request,
        code: Int,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = byteArrayOf(),
    ): Response {
        val responseHeaders =
            okhttp3.Headers.Builder()
                .apply { headers.forEach { (name, value) -> add(name, value) } }
                .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 300..399) "Redirect" else "OK")
            .headers(responseHeaders)
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()
    }
}
