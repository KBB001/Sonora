package com.example.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.remote.YouTubeMusicAccountApi
import com.example.ui.components.SonoraIcons
import org.json.JSONObject

private const val YOUTUBE_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F&hl=ru"

internal fun isAllowedAuthWebViewUrl(rawUrl: String?): Boolean {
    if (rawUrl.isNullOrBlank()) return false
    val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "google.com" ||
        host.endsWith(".google.com") ||
        host == "youtube.com" ||
        host.endsWith(".youtube.com") ||
        host.endsWith(".gstatic.com") ||
        host.endsWith(".googleusercontent.com") ||
        host.endsWith(".googleapis.com")
}

private fun displayHostOnly(rawUrl: String): String {
    val host = runCatching { android.net.Uri.parse(rawUrl).host }.getOrNull()
    return host?.takeIf { it.isNotBlank() } ?: "Защищённый вход YouTube"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginScreen(
    resetSession: Boolean,
    onBack: () -> Unit,
    onLoginSuccess: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    viewModel: YouTubeAccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pageProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(YOUTUBE_LOGIN_URL) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var hasCapturedLogin by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var useSoftwareLayer by remember { mutableStateOf(false) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var renderRecoveryCount by remember { mutableIntStateOf(0) }

    BackHandler(enabled = canGoBack) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            canGoBack = false
            onBack()
        }
    }

    fun collectMergedYouTubeCookies(): String {
        val cm = CookieManager.getInstance()
        cm.flush()
        val rootYtCookies = cm.getCookie("https://youtube.com").orEmpty()
        val ytCookies = cm.getCookie("https://www.youtube.com").orEmpty()
        val musicCookies = cm.getCookie("https://music.youtube.com").orEmpty()
        val merged = LinkedHashMap<String, String>()
        YouTubeMusicAccountApi.parseCookieMap(rootYtCookies).forEach { (k, v) -> merged[k] = v }
        YouTubeMusicAccountApi.parseCookieMap(ytCookies).forEach { (k, v) -> merged[k] = v }
        YouTubeMusicAccountApi.parseCookieMap(musicCookies).forEach { (k, v) -> merged[k] = v }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun tryFinalizeLogin(view: WebView?, manualTrigger: Boolean) {
        if (hasCapturedLogin || uiState.isSigningIn) return
        val mergedCookie = collectMergedYouTubeCookies()
        if (!YouTubeMusicAccountApi.hasAuthCookies(mergedCookie)) {
            if (manualTrigger) {
                onShowMessage("Сначала завершите вход в аккаунт Google / YouTube на странице")
            }
            return
        }
        hasCapturedLogin = true
        val script = """
            (function() {
                try {
                    if (window.ytcfg && typeof window.ytcfg.get === 'function') {
                        return JSON.stringify({
                            loggedIn: Boolean(window.ytcfg.get('LOGGED_IN')),
                            visitorData: window.ytcfg.get('VISITOR_DATA') || '',
                            dataSyncId: window.ytcfg.get('DATASYNC_ID') || '',
                            sessionIndex: String(window.ytcfg.get('SESSION_INDEX') ?? '0')
                        });
                    }
                } catch (e) {}
                return '{}';
            })();
        """.trimIndent()

        if (view != null) {
            view.evaluateJavascript(script) { raw ->
                val cleaned = raw
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    .orEmpty()
                val json = runCatching { JSONObject(cleaned) }.getOrNull()
                val loggedInFlag = json?.optBoolean("loggedIn", true) ?: true
                if (!manualTrigger && json != null && json.has("loggedIn") && !loggedInFlag) {
                    hasCapturedLogin = false
                    return@evaluateJavascript
                }
                val visitorData = json?.optString("visitorData").orEmpty()
                val dataSyncId = json?.optString("dataSyncId").orEmpty()
                val sessionIndex = json?.optString("sessionIndex")?.toIntOrNull()
                    ?: json?.optInt("sessionIndex", 0)
                    ?: 0

                viewModel.signInWithSession(
                    cookieHeader = mergedCookie,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                    authUserIndex = sessionIndex,
                    onSuccess = { account ->
                        onLoginSuccess("Вход выполнен: ${account.name}")
                    },
                    onError = { err ->
                        hasCapturedLogin = false
                        onShowMessage(err)
                    }
                )
            }
        } else {
            viewModel.signInWithSession(
                cookieHeader = mergedCookie,
                onSuccess = { account ->
                    onLoginSuccess("Вход выполнен: ${account.name}")
                },
                onError = { err ->
                    hasCapturedLogin = false
                    onShowMessage(err)
                }
            )
        }
    }

    DisposableEffect(webViewGeneration) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = if (resetSession) "Добавить аккаунт YouTube" else "Вход в YouTube",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = displayHostOnly(currentUrl),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("youtube_login_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Назад"
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.testTag("youtube_login_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Обновить страницу"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        if (pageProgress in 1..99 || uiState.isSigningIn) {
            LinearProgressIndicator(
                progress = { if (uiState.isSigningIn) 0.85f else pageProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            @SuppressLint("SetJavaScriptEnabled")
            androidx.compose.runtime.key(webViewGeneration) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        if (resetSession && webViewGeneration == 0) {
                            cookieManager.removeAllCookies(null)
                            cookieManager.flush()
                        }
                        WebView(ctx).apply {
                            webViewRef = this
                            if (useSoftwareLayer) {
                                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            }
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.setGeolocationEnabled(false)
                            settings.setSupportMultipleWindows(false)
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    pageProgress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val nextUrl = request?.url?.toString().orEmpty()
                                    if (!isAllowedAuthWebViewUrl(nextUrl)) {
                                        return true
                                    }
                                    currentUrl = nextUrl
                                    return false
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?
                                ) {
                                    currentUrl = url.orEmpty()
                                    canGoBack = view?.canGoBack() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val finishedUrl = url.orEmpty()
                                    currentUrl = finishedUrl
                                    canGoBack = view?.canGoBack() == true
                                    val isYouTubeDomain = finishedUrl.contains("music.youtube.com") ||
                                        finishedUrl.contains("www.youtube.com")
                                    if (isYouTubeDomain && !finishedUrl.contains("accounts.google.com")) {
                                        tryFinalizeLogin(view, manualTrigger = false)
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    // Prevent host app termination when GPU/rendernode fails on headless emulators
                                    val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        detail?.didCrash() == true
                                    } else {
                                        true
                                    }
                                    runCatching {
                                        view?.stopLoading()
                                        (view?.parent as? ViewGroup)?.removeView(view)
                                        view?.destroy()
                                    }
                                    webViewRef = null
                                    useSoftwareLayer = true
                                    if (renderRecoveryCount < 2) {
                                        renderRecoveryCount += 1
                                        webViewGeneration += 1
                                    } else {
                                        onShowMessage(
                                            if (crashed) {
                                                "Браузер переключился в безопасный режим — нажмите Обновить"
                                            } else {
                                                "Страница авторизации перезапущена"
                                            }
                                        )
                                    }
                                    return true
                                }
                            }
                            loadUrl(YOUTUBE_LOGIN_URL)
                        }
                    }
                )
            }

            if (uiState.isSigningIn) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.size(14.dp))
                        Text(
                            text = "Подключение профиля YouTube и синхронизация...",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

        Surface(
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        runCatching { WebStorage.getInstance().deleteAllData() }
                        webViewRef?.clearCache(true)
                        webViewRef?.clearHistory()
                        hasCapturedLogin = false
                        webViewRef?.loadUrl(YOUTUBE_LOGIN_URL)
                    },
                    modifier = Modifier.testTag("youtube_reset_webview_button")
                ) {
                    Text("Сбросить")
                }

                Button(
                    onClick = { tryFinalizeLogin(webViewRef, manualTrigger = true) },
                    enabled = !uiState.isSigningIn,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("youtube_complete_login_button")
                ) {
                    Icon(
                        imageVector = SonoraIcons.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Завершить вход")
                }
            }
        }
    }
}
