package com.snapnet.download.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Security rules shared by user-selected URLs, the direct downloader, and redirect handling. */
object HttpDownloadSecurityPolicy {
    fun isSupportedSourceUrl(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.username.isEmpty() && url.password.isEmpty()
    }

    fun requiresUserConfirmation(value: String): Boolean =
        value.trim().toHttpUrlOrNull()?.scheme == "http"

    fun mayStartTask(value: String, isDirectHttpFile: Boolean, consentGranted: Boolean): Boolean {
        if (!requiresUserConfirmation(value)) return isSupportedSourceUrl(value)
        return isDirectHttpFile && consentGranted && isSupportedSourceUrl(value)
    }

    fun mayUseYtDlp(value: String): Boolean =
        isSupportedSourceUrl(value) && !requiresUserConfirmation(value)

    fun hostForDisplay(value: String): String =
        value.trim().toHttpUrlOrNull()?.host ?: "unknown host"

    fun mayFollowRedirect(current: HttpUrl, next: HttpUrl): Boolean {
        if (next.username.isNotEmpty() || next.password.isNotEmpty()) return false
        if (current.scheme == "https" && next.scheme == "http") return false
        return true
    }

    fun mayFollowRedirect(current: String, next: String): Boolean {
        val currentUrl = current.trim().toHttpUrlOrNull() ?: return false
        val nextUrl = currentUrl.resolve(next) ?: next.trim().toHttpUrlOrNull() ?: return false
        return mayFollowRedirect(currentUrl, nextUrl)
    }
}
