package com.snapnet.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Retries only transient network/rate-limit failures; authorization, disk and permanent errors fail fast. */
internal suspend fun <T> retryTransientDownload(
    policy: RetryPolicy = RetryPolicy.DEFAULT,
    delayBlock: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = DownloadError.of(failure)
            val transient =
                error.kind == DownloadErrorKind.NETWORK_ERROR ||
                    error.kind == DownloadErrorKind.RATE_LIMITED
            if (!transient || !policy.shouldRetry(attempt, error)) throw failure
            delayBlock(policy.delayMillisFor(attempt))
            attempt++
        }
    }
}
