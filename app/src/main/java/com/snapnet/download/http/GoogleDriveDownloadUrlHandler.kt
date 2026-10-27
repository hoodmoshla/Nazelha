package com.snapnet.download.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalizes public Drive file-share links; it does not authenticate or bypass Google interstitials. */
internal object GoogleDriveDownloadUrlHandler {
    private val fileIdPattern = Regex("^[A-Za-z0-9_-]{10,200}$")

    fun normalize(url: HttpUrl): HttpUrl? {
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (!url.host.equals("drive.google.com", ignoreCase = true)) return null

        val path = url.encodedPathSegments
        val fileId =
            when {
                path.size >= 3 && path[0] == "file" && path[1] == "d" -> path[2]
                path.size == 1 && path[0] == "uc" -> url.queryParameter("id")
                path.size == 1 && path[0] == "open" -> url.queryParameter("id")
                else -> null
            }
        if (fileId == null || !fileIdPattern.matches(fileId)) return null

        return url.newBuilder()
            .scheme("https")
            .host("drive.google.com")
            .encodedPath("/uc")
            .removeAllQueryParameters("id")
            .removeAllQueryParameters("export")
            .removeAllQueryParameters("usp")
            .removeAllQueryParameters("authuser")
            .addQueryParameter("export", "download")
            .addQueryParameter("id", fileId)
            .build()
    }

    fun normalize(url: String): String? = url.toHttpUrlOrNull()?.let(::normalize)?.toString()
}
