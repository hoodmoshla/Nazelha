package com.snapnet.download.http

import com.snapnet.download.HttpStatusException
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectHttpResolverStatusTest {
    @Test
    fun `resolver propagates rate limit and server errors for retry classification`() {
        TestHttpServer().use { server ->
            val client =
                OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .cookieJar(CookieJar.NO_COOKIES)
                    .build()
            val resolver = DirectHttpLinkResolver(client)

            listOf(429, 503).forEach { status ->
                val path = "/status-$status"
                server.createContext(path) { _, output ->
                    server.respond(output, status, contentLength = 0L)
                }

                val failure =
                    assertThrows(HttpStatusException::class.java) {
                        resolver.resolve(server.url(path))
                    }
                assertEquals(status, failure.statusCode)
            }
        }
    }
}
