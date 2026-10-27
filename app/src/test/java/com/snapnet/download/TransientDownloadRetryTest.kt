package com.snapnet.download

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransientDownloadRetryTest {
    @Test
    fun `retries a transient network error with bounded exponential delays`() = runBlocking {
        val delays = mutableListOf<Long>()
        var calls = 0
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMillis = 100, maxDelayMillis = 1_000)

        val result =
            retryTransientDownload(
                policy = policy,
                delayBlock = { delays += it },
            ) {
                calls++
                if (calls < 3) throw IOException("Connection reset by peer")
                "downloaded"
            }

        assertEquals("downloaded", result)
        assertEquals(3, calls)
        assertEquals(listOf(100L, 200L), delays)
    }

    @Test
    fun `retries HTTP rate limits and server errors`() = runBlocking {
        listOf(429, 503).forEach { status ->
            var calls = 0
            val result =
                retryTransientDownload(
                    policy = RetryPolicy(maxAttempts = 2, initialDelayMillis = 0, maxDelayMillis = 0),
                    delayBlock = {},
                ) {
                    calls++
                    if (calls == 1) throw HttpStatusException(status)
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `does not retry disk failures`() {
        var calls = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                retryTransientDownload(delayBlock = {}) {
                    calls++
                    throw IOException("No space left on device")
                }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `does not swallow coroutine cancellation`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                retryTransientDownload(delayBlock = {}) {
                    throw CancellationException("user canceled")
                }
            }
        }
    }
}
