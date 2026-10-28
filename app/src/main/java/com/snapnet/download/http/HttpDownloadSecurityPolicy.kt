package com.snapnet.download.http

import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Security rules shared by user-selected URLs, the direct downloader, and redirect handling. */
object HttpDownloadSecurityPolicy {
    fun isSupportedSourceUrl(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.username.isEmpty() &&
            url.password.isEmpty() &&
            !url.hasCleartextCredentialQuery()
    }

    fun hasCleartextCredentials(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.scheme == "http" &&
            (url.username.isNotEmpty() ||
                url.password.isNotEmpty() ||
                url.hasCleartextCredentialQuery())
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
        if (next.hasCleartextCredentialQuery()) return false
        if (current.scheme == "https" && next.scheme == "http") return false
        return true
    }

    fun mayFollowRedirect(current: String, next: String): Boolean {
        val currentUrl = current.trim().toHttpUrlOrNull() ?: return false
        val nextUrl = currentUrl.resolve(next) ?: next.trim().toHttpUrlOrNull() ?: return false
        return mayFollowRedirect(currentUrl, nextUrl)
    }

    private fun HttpUrl.hasCleartextCredentialQuery(): Boolean =
        scheme == "http" && queryParameterNames.any(::isCredentialQueryParameter)

    private fun isCredentialQueryParameter(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return normalized in SENSITIVE_QUERY_KEYS ||
            SENSITIVE_QUERY_MARKERS.any { marker -> marker in normalized }
    }

    private val SENSITIVE_QUERY_KEYS =
        setOf(
            "accesscode",
            "auth",
            "authcode",
            "authkey",
            "authorizationcode",
            "code",
            "hdnea",
            "hdnts",
            "key",
            "oauthcode",
            "sid",
            "sig",
            "wmsauthsign",
        )

    private val SENSITIVE_QUERY_MARKERS =
        listOf(
            "accesskey",
            "apikey",
            "authorization",
            "bearer",
            "clearance",
            "clientkey",
            "credential",
            "cookie",
            "jwt",
            "passphrase",
            "password",
            "passwd",
            "privatekey",
            "secret",
            "session",
            "signature",
            "token",
            "username",
        )
}
