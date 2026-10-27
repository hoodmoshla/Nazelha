package com.snapnet.download.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.snapnet.download.HttpStatusException
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
        val original = url.trim().toHttpUrlOrNull() ?: return null
        if (original.scheme != "http" && original.scheme != "https") return null
        if (original.username.isNotEmpty() || original.password.isNotEmpty()) {
            throw IOException("Credentials in URLs are not supported")
        }
        val initial = GitHubDownloadUrlHandler.normalize(original)
            ?: DropboxDownloadUrlHandler.normalize(original)
            ?: GoogleDriveDownloadUrlHandler.normalize(original)
            ?: original
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
                    throw HttpStatusException(response.code)
                }
            }

            checkNotCanceled(handle)
            val probe = executeFollowingRedirects(initial, "GET", "bytes=0-0", taskKey, handle)
            probe.use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                return response.toDirectFileOrNull()
            }
        } finally {
            if (taskKey != null && handle != null) active.remove(taskKey, handle)
        }
    }

    /**
     * Reads one bounded HTML page and returns candidate file anchors. Candidates are only displayed;
     * this method never fetches them, executes scripts, or forwards cookies or authorization headers.
     */
    @Throws(IOException::class)
    fun discoverPageDownloads(url: String, taskKey: String? = null): List<DiscoveredDownloadLink> {
        val original = url.trim().toHttpUrlOrNull() ?: return emptyList()
        if (original.scheme != "http" && original.scheme != "https") return emptyList()
        if (original.username.isNotEmpty() || original.password.isNotEmpty()) {
            throw IOException("Credentials in URLs are not supported")
        }
        val initial =
            GitHubDownloadUrlHandler.normalize(original)
                ?: DropboxDownloadUrlHandler.normalize(original)
                ?: GoogleDriveDownloadUrlHandler.normalize(original)
                ?: original
        if (!shouldProbeHost(initial.host) || isGoogleDriveHost(initial.host)) return emptyList()

        val handle = taskKey?.let { key -> WorkHandle().also { active[key] = it } }
        try {
            val response = executeFollowingRedirects(initial, "GET", null, taskKey, handle)
            response.use { result ->
                if (!result.isSuccessful || !result.looksLikeHtmlPage()) return emptyList()
                val body = result.body ?: return emptyList()
                if (body.contentLength() > WebPageLinkDiscovery.MAX_HTML_BYTES) return emptyList()
                val bytes =
                    body.byteStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            checkNotCanceled(handle)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (total + count > WebPageLinkDiscovery.MAX_HTML_BYTES) return emptyList()
                            output.write(buffer, 0, count)
                            total += count
                        }
                        output.toByteArray()
                    }
                val charset = body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
                return WebPageLinkDiscovery.extract(String(bytes, charset), result.request.url.toString())
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
            isAttachment || dispositionName != null || (!html && (contentType != null || hasPathExtension))
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
        return (type == "text/html" || type == "application/xhtml+xml") &&
            !hasDownloadName
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

        private fun isGoogleDriveHost(host: String): Boolean =
            host.equals("drive.google.com", ignoreCase = true) ||
                host.equals("docs.google.com", ignoreCase = true) ||
                host.equals("drive.usercontent.google.com", ignoreCase = true)

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

internal object PublicInternetDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw UnknownHostException("Private or reserved network targets are blocked")
        }
        return addresses
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) return isPublicIpv4(bytes)
        if (bytes.size == 16) {
            val isIpv4Mapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
            if (isIpv4Mapped) return isPublicIpv4(bytes.drop(12))
            if (bytes.take(12).all { it == 0 }) return false // Deprecated IPv4-compatible form.

            val first = bytes[0]
            val second = bytes[1]
            if (first == 0xff) return false
            if (first == 0xfe && second and 0xc0 == 0x80) return false
            if (first and 0xfe == 0xfc) return false
            if (first == 0x20 && second == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
                return false
            if (first == 0x20 && second == 0x01 && bytes[2] == 0x00 && bytes[3] == 0x00)
                return false // Teredo / protocol-assignment space.
            if (first == 0x20 && second == 0x02) return false // 6to4 can encode private IPv4.
            if (bytes[0] == 0x00 && bytes[1] == 0x64 && bytes[2] == 0xff && bytes[3] == 0x9b) {
                return false // Well-known NAT64 prefix can encode a private IPv4 target.
            }
            if (
                bytes[0] == 0x00 && bytes[1] == 0x64 && bytes[2] == 0xff && bytes[3] == 0x9b &&
                    bytes[4] == 0x00 && bytes[5] == 0x01
            ) {
                return false // Local-use NAT64 prefix 64:ff9b:1::/48 may encode private IPv4.
            }
            if (first == 0x01 && bytes.drop(1).take(7).all { it == 0 }) {
                return false // Discard-only 100::/64.
            }
        }
        return true
    }

    private fun isPublicIpv4(bytes: List<Int>): Boolean {
        if (bytes.size != 4) return false
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
            a == 192 && b == 88 && c == 99 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            else -> true
        }
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
