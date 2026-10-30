package com.snapnet.ui.page

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hoodmoshla.nazelha.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val SAFE_EXTERNAL_BROWSER_SCHEMES = setOf("mailto", "tel", "sms", "geo")

internal fun isSafeExternalBrowserScheme(scheme: String?, hasGesture: Boolean): Boolean {
    val normalizedScheme = scheme?.lowercase() ?: return false
    return hasGesture && normalizedScheme in SAFE_EXTERNAL_BROWSER_SCHEMES
}

internal fun isAllowedInAppBrowserScheme(scheme: String?): Boolean =
    scheme?.equals("https", ignoreCase = true) == true

@Composable
fun BrowserPage(onDownloadLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var currentUrl by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageFailed by remember { mutableStateOf(false) }

    val browser = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webChromeClient =
                object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        progress = newProgress
                        canGoBack = view.canGoBack()
                        canGoForward = view.canGoForward()
                    }
                }
            webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase()
                        if (isAllowedInAppBrowserScheme(scheme)) return false
                        if (scheme == "http") {
                            pageFailed = true
                            return true
                        }
                        if (!isSafeExternalBrowserScheme(scheme, request.hasGesture())) return true
                        return try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                                    .addCategory(Intent.CATEGORY_BROWSABLE)
                            )
                            true
                        } catch (_: Exception) {
                            true
                        }
                    }

                    override fun onPageStarted(
                        view: WebView,
                        url: String,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        currentUrl = url
                        address = url
                        pageFailed = false
                        canGoBack = view.canGoBack()
                        canGoForward = view.canGoForward()
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        currentUrl = url
                        address = url
                        canGoBack = view.canGoBack()
                        canGoForward = view.canGoForward()
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) pageFailed = true
                    }
                }
            setDownloadListener { url, _, _, _, _ -> onDownloadLink(url) }
        }
    }
    DisposableEffect(browser) {
        onDispose {
            browser.stopLoading()
            browser.webChromeClient = null
            browser.webViewClient = WebViewClient()
            browser.destroy()
        }
    }
    BackHandler(enabled = canGoBack) { browser.goBack() }

    fun navigateToAddress() {
        val input = address.trim()
        if (input.isBlank()) return
        val candidate =
            if (input.contains(' ') || !input.contains('.')) {
                val query = URLEncoder.encode(input, StandardCharsets.UTF_8.toString())
                "https://www.google.com/search?q=$query"
            } else if (
                input.startsWith("https://", ignoreCase = true) ||
                    input.startsWith("http://", ignoreCase = true)
            ) {
                input
            } else {
                "https://$input"
            }
        val uri = Uri.parse(candidate)
        if (!isAllowedInAppBrowserScheme(uri.scheme) || uri.host.isNullOrBlank()) {
            pageFailed = true
            return
        }
        val nextUrl = uri.toString()
        address = nextUrl
        pageFailed = false
        if (currentUrl == nextUrl) {
            browser.reload()
        } else {
            currentUrl = nextUrl
            browser.loadUrl(nextUrl)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f).testTag("browser_address_input"),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text(stringResource(R.string.browser_address), maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigateToAddress() }),
            )
            IconButton(
                onClick = { navigateToAddress() },
                modifier =
                    Modifier.semantics {
                        contentDescription = context.getString(R.string.browser_go)
                    }.testTag("browser_go_button"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
            }
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .testTag("browser_action_bar"),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { browser.goBack() }, enabled = canGoBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.browser_back),
                )
            }
            IconButton(onClick = { browser.goForward() }, enabled = canGoForward) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = stringResource(R.string.browser_forward),
                )
            }
            IconButton(
                onClick = {
                    if (browser.progress < 100) browser.stopLoading() else browser.reload()
                }
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.browser_refresh),
                )
            }
            IconButton(
                onClick = {
                    currentUrl.takeIf(String::isNotBlank)?.let { url ->
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, url),
                                    context.getString(R.string.share),
                                )
                            )
                        }
                    }
                },
                enabled = currentUrl.isNotBlank(),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share))
            }
        }
        if (currentUrl.isBlank()) {
            Text(
                stringResource(R.string.browser_privacy_note),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("browser_privacy_note"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (currentUrl.isBlank()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp).testTag("browser_start_panel"),
                contentAlignment = Alignment.Center,
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            stringResource(R.string.browser_welcome),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        } else {
            AndroidView(
                factory = { browser },
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("browser_webview"),
                update = { view ->
                    if (view.url.isNullOrBlank()) view.loadUrl(currentUrl)
                },
            )
        }
        if (pageFailed) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.browser_load_error),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        browser.reload()
                        pageFailed = false
                    }
                ) {
                    Text(stringResource(R.string.browser_retry))
                }
            }
        }
    }
}
