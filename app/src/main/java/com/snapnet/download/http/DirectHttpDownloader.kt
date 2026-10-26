package com.snapnet.download.http

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Streams public file responses to a resumable partial file without loading the body into memory.
 */
class DirectHttpDownloader(private val client: OkHttpClient = DirectHttpClientProvider.create()) {
    private val active = ConcurrentHashMap<String, WorkHandle>()

    /**
     * Returns the final partial-file length. On failure, already-written bytes remain resumable.
     */
    @Throws(IOException::class, CancellationException::class)
    fun download(
        taskId: String,
        file: DirectHttpFile,
        partialFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Long {
        val handle = WorkHandle()
        active[taskId] = handle
        try {
            partialFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs())
                    throw IOException("Cannot create temporary folder")
            }
            if (
                partialFile.exists() &&
                    partialFile.length() > 0L &&
                    file.etag.isNullOrBlank() &&
                    file.lastModified.isNullOrBlank()
            ) {
                // Without a validator, appending could corrupt a file changed on the server.
                if (!partialFile.delete())
                    throw IOException("Cannot reset an unverifiable partial file")
            }
            val existingBytes = partialFile.length().coerceAtLeast(0L)
            val responseAndCall = executeFollowingRedirects(file, existingBytes, handle)
            val (response, call) = responseAndCall
            response.use { result ->
                checkNotCanceled(handle, call)
                if (result.code == 416) {
                    val serverTotal = parseUnsatisfiedRangeTotal(result.header("Content-Range"))
                    if (serverTotal != null && existingBytes == serverTotal) {
                        onProgress(existingBytes, serverTotal)
                        return existingBytes
                    }
                    throw IOException("Server rejected the requested resume range")
                }
                if (result.code !in 200..299)
                    throw IOException("Server returned HTTP ${result.code}")

                val responseRangeStart = parseContentRangeStart(result.header("Content-Range"))
                val append = result.code == 206
                if (append && (responseRangeStart == null || responseRangeStart != existingBytes)) {
                    throw IOException("Server returned an invalid resume range")
                }
                val writeOffset = if (append) existingBytes else 0L
                if (!append && existingBytes > 0L) {
                    FileOutputStream(partialFile, false).use { it.fd.sync() }
                }

                val responseLength = result.body?.contentLength()?.takeIf { it >= 0L }
                val totalBytes =
                    parseContentRangeTotal(result.header("Content-Range"))
                        ?: responseLength?.let { writeOffset + it }
                        ?: file.sizeBytes
                var written = writeOffset
                val body = result.body ?: throw IOException("Empty HTTP response")
                body.byteStream().use { input ->
                    FileOutputStream(partialFile, append).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            checkNotCanceled(handle, call)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            written += count
                            onProgress(written, totalBytes)
                        }
                        output.fd.sync()
                    }
                }
                checkNotCanceled(handle, call)
                if (totalBytes != null && written != totalBytes) {
                    throw IOException(
                        "Connection ended before the announced file size was received"
                    )
                }
                return written
            }
        } catch (failure: IOException) {
            if (handle.canceled.get() || handle.call?.isCanceled() == true) {
                throw CancellationException("HTTP download canceled").apply { initCause(failure) }
            }
            throw failure
        } finally {
            active.remove(taskId, handle)
        }
    }

    fun cancel(taskId: String) {
        active[taskId]?.cancel()
    }

    private fun executeFollowingRedirects(
        file: DirectHttpFile,
        existingBytes: Long,
        handle: WorkHandle,
    ): Pair<Response, Call> {
        var current =
            file.downloadUrl.toHttpUrlOrNull() ?: throw IOException("Invalid HTTP download URL")
        if (current.scheme != "http" && current.scheme != "https") {
            throw IOException("Only HTTP(S) download URLs are supported")
        }
        if (current.username.isNotEmpty() || current.password.isNotEmpty()) {
            throw IOException("Credentials in URLs are not supported")
        }

        repeat(MAX_REDIRECTS + 1) { hop ->
            checkNotCanceled(handle, handle.call)
            val requestBuilder =
                Request.Builder()
                    .url(current)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Cache-Control", "no-cache")
            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                when {
                    !file.etag.isNullOrBlank() -> requestBuilder.header("If-Range", file.etag)
                    !file.lastModified.isNullOrBlank() ->
                        requestBuilder.header("If-Range", file.lastModified)
                }
            }
            val call = client.newCall(requestBuilder.build())
            handle.call = call
            if (handle.canceled.get()) call.cancel()
            val response = call.execute()
            if (response.code !in REDIRECT_CODES) return response to call

            val location = response.header("Location")
            response.close()
            if (hop == MAX_REDIRECTS || location.isNullOrBlank()) {
                throw IOException("Too many or invalid HTTP redirects")
            }
            val next = current.resolve(location) ?: throw IOException("Invalid HTTP redirect")
            if (current.scheme == "https" && next.scheme != "https") {
                throw IOException("HTTPS downgrade redirect blocked")
            }
            if (next.scheme != "http" && next.scheme != "https") {
                throw IOException("Non-HTTP redirect blocked")
            }
            if (next.username.isNotEmpty() || next.password.isNotEmpty()) {
                throw IOException("Credentials in redirects are not supported")
            }
            current = next
        }
        throw IOException("Too many HTTP redirects")
    }

    private fun checkNotCanceled(handle: WorkHandle, call: Call?) {
        if (handle.canceled.get() || call?.isCanceled() == true)
            throw CancellationException("HTTP download canceled")
    }

    private class WorkHandle {
        val canceled = AtomicBoolean(false)
        @Volatile var call: Call? = null

        fun cancel() {
            canceled.set(true)
            call?.cancel()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REDIRECTS = 8
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun parseContentRangeStart(value: String?): Long? =
            value?.substringAfter(' ')?.substringBefore('-')?.toLongOrNull()

        private fun parseContentRangeTotal(value: String?): Long? =
            value?.substringAfterLast('/')?.takeUnless { it == "*" }?.toLongOrNull()

        private fun parseUnsatisfiedRangeTotal(value: String?): Long? =
            value
                ?.takeIf { it.startsWith("bytes */", ignoreCase = true) }
                ?.substringAfter('/')
                ?.toLongOrNull()
    }
}
