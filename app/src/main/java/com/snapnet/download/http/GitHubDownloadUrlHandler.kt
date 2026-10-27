package com.snapnet.download.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalizes public GitHub file links without adding cookies, credentials, or private API access. */
internal object GitHubDownloadUrlHandler {
    fun normalize(url: HttpUrl): HttpUrl? {
        if (url.scheme != "https" && url.scheme != "http") return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null

        val host = url.host.lowercase()
        if (host == "raw.githubusercontent.com" || host.endsWith(".githubusercontent.com")) {
            return url
        }
        if (host != "github.com" && host != "www.github.com") return null

        val path = url.encodedPathSegments
        if (path.size >= 5 && path[2] == "blob") {
            // The query form lets GitHub resolve refs that contain slashes before redirecting to
            // the public raw host. It avoids guessing where a branch name ends and a file path begins.
            return url.newBuilder()
                .apply { if (url.queryParameter("raw") == null) addQueryParameter("raw", "1") }
                .build()
        }

        val isReleaseAsset =
            path.size >= 6 && path[2] == "releases" && path[3] == "download"
        val isUserAttachment =
            path.size >= 3 && path[0] == "user-attachments" && path[1] in setOf("assets", "files")
        return url.takeIf { isReleaseAsset || isUserAttachment }
    }

    fun normalize(url: String): String? = url.toHttpUrlOrNull()?.let(::normalize)?.toString()
}
