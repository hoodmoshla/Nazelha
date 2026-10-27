package com.snapnet.download.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** A candidate shown to the user; it is never fetched until the user chooses it. */
data class DiscoveredDownloadLink(val url: String, val label: String, val host: String)

/**
 * Extracts only explicit anchor links that look like files. This is intentionally not a crawler:
 * it does not execute JavaScript, submit forms, follow links, or carry page cookies to another host.
 */
internal object WebPageLinkDiscovery {
    const val MAX_HTML_BYTES = 1_048_576
    const val MAX_LINKS = 30

    private val extensions =
        setOf(
            "7z", "apk", "avi", "csv", "doc", "docx", "flac", "gif", "gz", "jpeg", "jpg",
            "m4a", "mkv", "mov", "mp3", "mp4", "ogg", "opus", "pdf", "png", "pptx",
            "rar", "tar", "txt", "wav", "webm", "webp", "xlsx", "zip",
        )
    private val downloadableMimeTypes =
        setOf(
            "application/octet-stream", "application/pdf", "application/zip", "audio/", "image/",
            "video/",
        )

    fun extract(html: String, pageUrl: String): List<DiscoveredDownloadLink> {
        if (html.length > MAX_HTML_BYTES) return emptyList()
        val base = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        if (base.scheme != "http" && base.scheme != "https") return emptyList()
        val document = Jsoup.parse(html, base.toString())
        val baseUrl =
            document.selectFirst("base[href]")
                ?.absUrl("href")
                ?.toHttpUrlOrNull()
                ?.takeIf { (it.scheme == "http" || it.scheme == "https") && it.username.isEmpty() && it.password.isEmpty() }
                ?: base

        val results = LinkedHashMap<String, DiscoveredDownloadLink>()
        for (anchor in document.select("a[href]")) {
            if (results.size >= MAX_LINKS) break
            val raw = anchor.attr("href").trim()
            if (raw.isEmpty()) continue
            val target = baseUrl.resolve(raw) ?: continue
            if (target.scheme != "http" && target.scheme != "https") continue
            if (target.username.isNotEmpty() || target.password.isNotEmpty()) continue

            val handlerTarget =
                GitHubDownloadUrlHandler.normalize(target)
                    ?: DropboxDownloadUrlHandler.normalize(target)
                    ?: GoogleDriveDownloadUrlHandler.normalize(target)
                    ?: target
            if (!looksDownloadable(anchor.hasAttr("download"), anchor.attr("type"), handlerTarget)) continue

            val url = handlerTarget.toString()
            val label = anchor.text().trim().take(120).ifBlank { handlerTarget.host }
            results.putIfAbsent(url, DiscoveredDownloadLink(url, label, handlerTarget.host))
        }
        return results.values.toList()
    }

    private fun looksDownloadable(downloadAttribute: Boolean, rawType: String, target: HttpUrl): Boolean {
        if (downloadAttribute) return true
        val mime = rawType.substringBefore(';').trim().lowercase()
        if (mime in downloadableMimeTypes || downloadableMimeTypes.any { it.endsWith('/') && mime.startsWith(it) }) {
            return true
        }
        val extension = target.encodedPath.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return extension in extensions
    }
}
