package com.snapnet.download.http

import com.snapnet.download.DownloadStoragePolicy
import com.snapnet.download.HttpStatusException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
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
 * Resume validators are saved next to partial files and are bound to an opaque task-id digest.
 */
class DirectHttpDownloader(private val client: OkHttpClient = DirectHttpClientProvider.create()) {
    private val active = ConcurrentHashMap<String, WorkHandle>()

    /**
     * Returns the final partial-file length. On failure, already-written bytes remain resumable
     * when the GET response supplied a safe validator.
     */
    @Suppress("UsableSpace") // Conservative: do not count cache bytes Android may reclaim.
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

            val owner = taskDigest(taskId)
            var resumeState = loadResumeState(partialFile, owner)
            if (partialFile.exists() && partialFile.length() > 0L && resumeState == null) {
                resetPartial(partialFile)
            }
            var retryFreshUsed = false

            while (true) {
                val existingBytes =
                    if (resumeState != null) partialFile.length().coerceAtLeast(0L) else 0L
                val (response, call) =
                    executeFollowingRedirects(
                        file,
                        existingBytes,
                        resumeState?.validator,
                        handle,
                    )
                var completed: Long? = null
                var retryFresh = false
                response.use { result ->
                    checkNotCanceled(handle, call)
                    if (result.code == 416) {
                        val serverTotal = parseUnsatisfiedRangeTotal(result.header("Content-Range"))
                        if (
                            existingBytes > 0L &&
                                resumeState != null &&
                                serverTotal == existingBytes &&
                                responseValidatorMatches(result, resumeState.validator)
                        ) {
                            onProgress(existingBytes, serverTotal)
                            completed = existingBytes
                        } else if (existingBytes > 0L && !retryFreshUsed) {
                            resetPartial(partialFile)
                            resumeState = null
                            retryFreshUsed = true
                            retryFresh = true
                        } else {
                            throw IOException("Server rejected the requested resume range")
                        }
                    } else {
                        if (result.code !in 200..299) throw HttpStatusException(result.code)

                        val append = result.code == 206
                        val responseRangeStart = parseContentRangeStart(result.header("Content-Range"))
                        val rangeTotal = parseContentRangeTotal(result.header("Content-Range"))
                        val invalidOffset = append && (responseRangeStart != existingBytes || rangeTotal == null)
                        val changedValidator =
                            append && existingBytes > 0L && resumeState != null &&
                                !responseValidatorMatches(result, resumeState.validator)
                        if (invalidOffset || changedValidator) {
                            if (!retryFreshUsed) {
                                resetPartial(partialFile)
                                resumeState = null
                                retryFreshUsed = true
                                retryFresh = true
                            } else {
                                resetPartial(partialFile)
                                throw IOException(
                                    if (changedValidator) {
                                        "Server returned a different validator for the resumed response"
                                    } else {
                                        "Server returned an invalid resume range"
                                    }
                                )
                            }
                        } else {
                            val writeOffset = if (append) existingBytes else 0L
                            if (!append) {
                                truncatePartial(partialFile)
                                // A 200 response ignores Range and replaces all previous bytes.
                                resumeState = persistResponseValidator(partialFile, owner, result)
                            }
                            if (append && existingBytes == 0L) {
                                // A valid unsolicited 206 starts at zero and may still be resumed.
                                resumeState = persistResponseValidator(partialFile, owner, result)
                            }

                            val responseLength = result.body?.contentLength()?.takeIf { it >= 0L }
                            val totalBytes =
                                rangeTotal
                                    ?: responseLength?.let { writeOffset + it }
                                    ?: file.sizeBytes
                            val availableBytes = partialFile.parentFile?.usableSpace
                            if (availableBytes != null &&
                                !DownloadStoragePolicy.canFit(availableBytes, totalBytes, writeOffset)) {
                                throw IOException("No space left on device for this download")
                            }
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
                            completed = written
                        }
                    }
                }
                if (retryFresh) continue
                return completed ?: throw IOException("HTTP download did not complete")
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

    /** Remove resume metadata after the partial file has been successfully published. */
    fun clearResumeMetadata(partialFile: File) {
        try {
            removeResumeMetadata(partialFile)
        } catch (_: Exception) {
            // Publication has already succeeded; stale hidden metadata is best-effort cleanup.
        }
    }

    fun cancel(taskId: String) {
        active[taskId]?.cancel()
    }

    private fun executeFollowingRedirects(
        file: DirectHttpFile,
        existingBytes: Long,
        validator: ResumeValidator?,
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
            if (existingBytes > 0L && validator != null) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                requestBuilder.header("If-Range", validator.value)
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

    private fun loadResumeState(partialFile: File, owner: String): ResumeState? {
        val metadata = metadataFile(partialFile)
        if (!metadata.isFile || !partialFile.isFile || partialFile.length() <= 0L) return null
        return try {
            DataInputStream(FileInputStream(metadata)).use { input ->
                if (input.readInt() != METADATA_MAGIC) return null
                val savedOwner = input.readUTF()
                val type = input.readUTF()
                val value = input.readUTF()
                if (savedOwner != owner || value.isBlank()) return null
                val validator =
                    when (type) {
                        ETAG_TYPE -> value.takeIf(::isStrongEtag)?.let { ResumeValidator(ETAG_TYPE, it) }
                        LAST_MODIFIED_TYPE -> ResumeValidator(LAST_MODIFIED_TYPE, value)
                        else -> null
                    } ?: return null
                ResumeState(validator)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun persistResponseValidator(
        partialFile: File,
        owner: String,
        response: Response,
    ): ResumeState? {
        val validator = responseValidator(response)
        if (validator == null) {
            removeResumeMetadata(partialFile)
            return null
        }
        val metadata = metadataFile(partialFile)
        try {
            val fileOutput = FileOutputStream(metadata, false)
            DataOutputStream(fileOutput).use { output ->
                output.writeInt(METADATA_MAGIC)
                output.writeUTF(owner)
                output.writeUTF(validator.type)
                output.writeUTF(validator.value)
                output.flush()
                fileOutput.fd.sync()
            }
        } catch (failure: IOException) {
            metadata.delete()
            throw failure
        }
        return ResumeState(validator)
    }

    private fun responseValidator(response: Response): ResumeValidator? {
        val etag = response.header("ETag")
        if (!etag.isNullOrBlank() && isStrongEtag(etag)) return ResumeValidator(ETAG_TYPE, etag)
        val lastModified = response.header("Last-Modified")?.takeIf { it.isNotBlank() }
        return lastModified?.let { ResumeValidator(LAST_MODIFIED_TYPE, it) }
    }

    private fun responseValidatorMatches(response: Response, saved: ResumeValidator): Boolean {
        val responseValue =
            when (saved.type) {
                ETAG_TYPE -> response.header("ETag")
                LAST_MODIFIED_TYPE -> response.header("Last-Modified")
                else -> null
            }
        // Fail closed if a server omits the validator: do not trust non-compliant range behavior.
        return responseValue == saved.value
    }

    private fun resetPartial(partialFile: File) {
        truncatePartial(partialFile)
        removeResumeMetadata(partialFile)
    }

    private fun removeResumeMetadata(partialFile: File) {
        val metadata = metadataFile(partialFile)
        if (metadata.exists() && !metadata.delete()) {
            throw IOException("Cannot remove HTTP resume metadata")
        }
    }

    private fun truncatePartial(partialFile: File) {
        FileOutputStream(partialFile, false).use { it.fd.sync() }
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

    private data class ResumeValidator(val type: String, val value: String)
    private data class ResumeState(val validator: ResumeValidator)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REDIRECTS = 8
        private const val METADATA_MAGIC = 0x534E4852 // SNHR
        private const val ETAG_TYPE = "etag"
        private const val LAST_MODIFIED_TYPE = "last-modified"
        private const val METADATA_SUFFIX = ".resume"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun metadataFile(partialFile: File): File =
            File(partialFile.parentFile, ".${partialFile.name}$METADATA_SUFFIX")

        private fun taskDigest(taskId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        private fun isStrongEtag(value: String): Boolean =
            value.length >= 2 && value.first() == '"' && value.last() == '"' &&
                value.substring(1, value.lastIndex).all { char ->
                    char.code == 0x21 || char.code in 0x23..0x7e || char.code in 0x80..0xff
                }

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
