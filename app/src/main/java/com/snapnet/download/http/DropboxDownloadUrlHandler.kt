package com.snapnet.download.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalizes public Dropbox file links without adding cookies, credentials, or private API access. */
internal object DropboxDownloadUrlHandler {
    fun normalize(url: HttpUrl): HttpUrl? {
        if (url.scheme != "https") return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null

        val host = url.host.lowercase()
        if (host != "dropbox.com" && host != "www.dropbox.com") return null

        val path = url.encodedPathSegments
        val isLegacyFileLink =
            path.size == 3 && path[0] == "s" && path[1].isNotEmpty() && path[2].isNotEmpty()
        val isSharedLinkFile =
            path.size == 4 && path[0] == "scl" && path[1] == "fi" &&
                path[2].isNotEmpty() && path[3].isNotEmpty()
        if (!isLegacyFileLink && !isSharedLinkFile) return null

        return url.newBuilder()
            .removeAllQueryParameters("dl")
            .removeAllQueryParameters("raw")
            .addQueryParameter("dl", "1")
            .build()
    }

    fun normalize(url: String): String? = url.toHttpUrlOrNull()?.let(::normalize)?.toString()
}
