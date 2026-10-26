package com.snapnet.download.http

import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Resolves direct public HTTP(S) file links using headers only (or a one-byte range probe). */
class DirectHttpLinkResolver(private val client: OkHttpClient = DirectHttpClientProvider.create()) {
    private val active = ConcurrentHashMap<String, WorkHandle>()

    /** Returns null for web pages or responses that are not recognizable as a downloadable file. */
    @Throws(IOException::class)
    fun resolve(url: String, taskKey: String? = null): DirectHttpFile? {
        val initial = url.trim().toHttpUrlOrNull() ?: return null
        if (initial.scheme != "http" && initial.scheme != "https") return null
        if (initial.username.isNotEmpty() || initial.password.isNotEmpty()) {
            throw IOException("Credentials in URLs are not supported")
        }
        if (!shouldProbeHost(initial.host)) return null
        val handle = taskKey?.let { key -> WorkHandle().also { active[key] = it } }
        try {
            val head = executeFollowingRedirects(initial, "HEAD", null, taskKey, handle)
            head.use { response ->
                if (response.code == 405 || response.code == 501) {
                    // Some file hosts do not implement HEAD. The follow-up only requests byte 0.
                } else if (response.isSuccessful) {
                    val fromHead = response.toDirectFileOrNull()
                    if (fromHead != null) return fromHead
                    if (response.looksLikeHtmlPage()) return null
                } else {
                    return null
                }
            }

            checkNotCanceled(handle)
            val probe = executeFollowingRedirects(initial, "GET", "bytes=0-0", taskKey, handle)
            probe.use { response ->
                if (!response.isSuccessful) return null
                return response.toDirectFileOrNull()
            }
        } finally {
            if (taskKey != null && handle != null) active.remove(taskKey, handle)
        }
    }

    fun cancel(taskKey: String) {
        active[taskKey]?.cancel()
    }

    private fun executeFollowingRedirects(
        start: HttpUrl,
        method: String,
        range: String?,
        taskKey: String?,
        handle: WorkHandle?,
    ): Response {
        var current = start
        repeat(MAX_REDIRECTS + 1) { hop ->
            checkNotCanceled(handle)
            val requestBuilder =
                Request.Builder()
                    .url(current)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Cache-Control", "no-cache")
                    .method(method, null)
            range?.let { requestBuilder.header("Range", it) }
            val call = client.newCall(requestBuilder.build())
            handle?.call = call
            if (handle?.canceled?.get() == true) call.cancel()
            val response = call.execute()
            val location = response.header("Location")
            if (response.code !in REDIRECT_CODES) return response
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

    private fun Response.toDirectFileOrNull(): DirectHttpFile? {
        val contentType =
            header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf(String::isNotEmpty)
        val disposition = header("Content-Disposition")
        val dispositionName = parseDispositionFileName(disposition)
        val pathName = request.url.encodedPathSegments.lastOrNull().orEmpty().decodeUrlComponent()
        val hasPathExtension = pathName.substringAfterLast('.', "").isNotEmpty()
        val isAttachment = disposition?.contains("attachment", ignoreCase = true) == true
        val html = contentType == "text/html" || contentType == "application/xhtml+xml"

        val recognizedAsFile =
            isAttachment ||
                dispositionName != null ||
                (html && hasPathExtension) ||
                (!html && (contentType != null || hasPathExtension))
        if (!recognizedAsFile) return null

        val safeName = FileNamingSupport.safeFileName(dispositionName ?: pathName, contentType)
        val rangeTotal = parseContentRangeTotal(header("Content-Range"))
        val contentLength = header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
        val size = if (code == 206) rangeTotal else contentLength
        return DirectHttpFile(
            downloadUrl = request.url.toString(),
            fileName = safeName,
            contentType = contentType ?: "application/octet-stream",
            sizeBytes = size,
            supportsRanges =
                header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true ||
                    code == 206,
            etag = header("ETag"),
            lastModified = header("Last-Modified"),
        )
    }

    private fun Response.looksLikeHtmlPage(): Boolean {
        val type = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val hasDownloadName = parseDispositionFileName(header("Content-Disposition")) != null
        val pathHasExtension = request.url.encodedPathSegments.lastOrNull().orEmpty().contains('.')
        return (type == "text/html" || type == "application/xhtml+xml") &&
            !hasDownloadName &&
            !pathHasExtension
    }

    private fun checkNotCanceled(handle: WorkHandle?) {
        if (handle?.canceled?.get() == true) throw java.util.concurrent.CancellationException()
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
        private const val MAX_REDIRECTS = 8
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val VIDEO_PAGE_HOSTS =
            setOf(
                "youtube.com",
                "youtube-nocookie.com",
                "youtu.be",
                "facebook.com",
                "fb.watch",
                "x.com",
                "twitter.com",
                "t.co",
            )

        internal fun shouldProbeHost(host: String): Boolean {
            val normalizedHost = host.lowercase(Locale.ROOT)
            return VIDEO_PAGE_HOSTS.none { normalizedHost == it || normalizedHost.endsWith(".$it") }
        }

        private fun parseContentRangeTotal(value: String?): Long? =
            value?.substringAfterLast('/')?.takeUnless { it == "*" }?.toLongOrNull()

        private fun parseDispositionFileName(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val extended =
                Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)")
                    .find(value)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.trim('"', '\'')
            if (!extended.isNullOrBlank()) {
                val encoded = extended.substringAfter("''", extended)
                val decoded = encoded.decodeUrlComponent()
                if (decoded.isNotBlank()) return decoded
            }
            val regular =
                Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))")
                    .find(value)
                    ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
                    ?.trim()
            return regular?.takeIf(String::isNotBlank)
        }

        private fun String.decodeUrlComponent(): String =
            runCatching { URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
                .getOrDefault(this)
    }
}

internal object DirectHttpClientProvider {
    fun create(): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .dns(PublicInternetDns)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
}

private object PublicInternetDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !it.isPublicAddress() }) {
            throw UnknownHostException("Private or reserved network targets are blocked")
        }
        return addresses
    }

    private fun InetAddress.isPublicAddress(): Boolean {
        if (
            isAnyLocalAddress ||
                isLoopbackAddress ||
                isLinkLocalAddress ||
                isSiteLocalAddress ||
                isMulticastAddress
        ) {
            return false
        }
        val bytes = address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return when {
                a == 0 || a == 10 || a == 127 || a >= 224 -> false
                a == 100 && b in 64..127 -> false
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && b == 168 -> false
                a == 192 && b == 0 && c == 0 -> false
                a == 192 && b == 0 && c == 2 -> false
                a == 198 && b in 18..19 -> false
                a == 198 && b == 51 && c == 100 -> false
                a == 203 && b == 0 && c == 113 -> false
                else -> true
            }
        }
        if (bytes.size == 16) {
            val first = bytes[0]
            val second = bytes[1]
            if (first == 0xff) return false
            if (first == 0xfe && second and 0xc0 == 0x80) return false
            if (first and 0xfe == 0xfc) return false
            if (first == 0x20 && second == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
                return false
        }
        return true
    }
}

private object FileNamingSupport {
    private val extensionByMime =
        mapOf(
            "application/pdf" to ".pdf",
            "application/zip" to ".zip",
            "application/gzip" to ".gz",
            "application/json" to ".json",
            "application/octet-stream" to "",
            "text/plain" to ".txt",
            "text/csv" to ".csv",
            "image/jpeg" to ".jpg",
            "image/png" to ".png",
            "image/gif" to ".gif",
            "image/webp" to ".webp",
            "video/mp4" to ".mp4",
            "audio/mpeg" to ".mp3",
        )

    fun safeFileName(raw: String?, contentType: String?): String {
        val candidate = raw.orEmpty().substringAfterLast('/').ifBlank { "download" }
        val safe = com.snapnet.util.FileNaming.sanitize(candidate)
        val extension = com.snapnet.util.FileNaming.extensionOf(safe)
        val mimeExtension = extensionByMime[contentType].orEmpty()
        return if (extension.isEmpty() && mimeExtension.isNotEmpty()) {
            com.snapnet.util.FileNaming.sanitize("$safe$mimeExtension")
        } else safe
    }
}
