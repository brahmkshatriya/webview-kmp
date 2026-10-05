package dev.brahmkshatriya.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Android's framework WebView provider. No browser engine is packaged by this library. */
public object SystemWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.System
    override val displayName: String = "Android System WebView"
    override val capabilities: WebViewCapabilities =
        WebViewCapabilities.of(
            WebViewCapability.CustomRequestHeaders,
            WebViewCapability.CustomUserAgent,
            WebViewCapability.JavaScriptEvaluation,
            WebViewCapability.CrossOriginJavaScriptEvaluation,
            WebViewCapability.NavigationState,
            WebViewCapability.LoadingProgress,
            WebViewCapability.JavaScriptControl,
            WebViewCapability.MediaPlaybackPolicy,
        )

    override fun availability(): WebViewBackendAvailability =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (WebView.getCurrentWebViewPackage() != null) {
                    WebViewBackendAvailability.Available
                } else {
                    WebViewBackendAvailability.Unavailable("Android System WebView is disabled or missing")
                }
            } catch (failure: Throwable) {
                WebViewBackendAvailability.Unavailable(
                    failure.message ?: "Android System WebView is unavailable",
                )
            }
        } else {
            WebViewBackendAvailability.Available
        }

    override fun create(config: WebViewConfig): WebViewController = SystemWebViewController(config)
}

public fun platformBackendProviders(): List<WebViewBackendProvider> = listOf(SystemWebViewBackend)

/** Android controller backed by [android.webkit.WebView]. */
public class SystemWebViewController public constructor(
    private val config: WebViewConfig = WebViewConfig(),
) : WebViewController {
    private val mutableStates = MutableStateFlow(WebViewState())
    private val pendingCommands = mutableListOf<(WebView) -> Unit>()
    private var nativeView: WebView? = null
    private var closed: Boolean = false

    override val backendId: WebViewBackendId = WebViewBackendId.System
    override val capabilities: WebViewCapabilities = SystemWebViewBackend.capabilities
    override val states: StateFlow<WebViewState> = mutableStates
    override val state: WebViewState
        get() = mutableStates.value

    init {
        config.initialUrl?.takeIf(String::isNotBlank)?.let { url ->
            pendingCommands += { it.loadUrl(url) }
        }
    }

    override fun loadUrl(url: String, headers: Map<String, String>): Unit {
        require(url.isNotBlank()) { "URL must not be blank" }
        withView { view ->
            if (headers.isEmpty()) view.loadUrl(url) else view.loadUrl(url, headers)
        }
    }

    override fun loadHtml(html: String, baseUrl: String?): Unit {
        withView { view -> view.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null) }
    }

    override fun reload(): Unit = withView(WebView::reload)

    override fun stopLoading(): Unit = withView(WebView::stopLoading)

    override fun goBack(): Unit = withView { if (it.canGoBack()) it.goBack() }

    override fun goForward(): Unit = withView { if (it.canGoForward()) it.goForward() }

    override fun evaluateJavaScript(
        script: String,
        callback: (JavaScriptResult) -> Unit,
    ): Unit {
        withView { view ->
            view.evaluateJavascript(script) { value -> callback(JavaScriptResult.Value(value)) }
        }
    }

    override fun close(): Unit {
        if (closed) return
        closed = true
        pendingCommands.clear()
        val view = nativeView
        nativeView = null
        if (view != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) view.destroy() else view.post { view.destroy() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun attach(context: Context): WebView {
        check(!closed) { "WebViewController is closed" }
        nativeView?.let { return it }

        val view = WebView(context)
        nativeView = view
        view.settings.javaScriptEnabled = config.javaScript == JavaScriptMode.Enabled
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = config.mediaPlaybackRequiresUserGesture
        when (val userAgent = config.userAgent) {
            UserAgent.Default -> Unit
            is UserAgent.Custom -> view.settings.userAgentString = userAgent.value
        }
        if (config.debugLogging) WebView.setWebContentsDebuggingEnabled(true)

        view.webViewClient = createWebViewClient()
        view.webChromeClient = createWebChromeClient()

        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { it(view) }
        updateState(view)
        return view
    }

    internal fun detach(view: WebView): Unit {
        if (nativeView !== view) return
        nativeView = null
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    private fun withView(command: (WebView) -> Unit): Unit {
        check(!closed) { "WebViewController is closed" }
        val view = nativeView
        if (view == null) {
            pendingCommands += command
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) command(view) else view.post { command(view) }
    }

    private fun createWebViewClient(): WebViewClient =
        object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?): Unit {
                updateState(view, url = url, loading = true, progress = 0f, error = null)
            }

            override fun onPageFinished(view: WebView, url: String?): Unit {
                updateState(view, url = url, loading = false, progress = 1f)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ): Unit {
                if (request.isForMainFrame) {
                    updateState(
                        view,
                        error = error.description?.toString() ?: "WebView load failed",
                    )
                }
            }
        }

    private fun createWebChromeClient(): WebChromeClient =
        object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int): Unit {
                updateState(view, progress = (newProgress / 100f).coerceIn(0f, 1f))
            }

            override fun onReceivedTitle(view: WebView, title: String?): Unit {
                updateState(view, title = title)
            }
        }

    private fun updateState(
        view: WebView,
        url: String? = view.url,
        title: String? = view.title,
        loading: Boolean = view.progress < 100,
        progress: Float = (view.progress / 100f).coerceIn(0f, 1f),
        error: String? = state.error,
    ): Unit {
        mutableStates.value =
            WebViewState(
                url = url,
                title = title,
                isLoading = loading,
                progress = progress,
                canGoBack = view.canGoBack(),
                canGoForward = view.canGoForward(),
                error = error,
            )
    }
}

@Composable
public fun PlatformWebView(
    controller: WebViewController,
    modifier: Modifier,
): Unit {
    val systemController =
        controller as? SystemWebViewController
            ?: error("The selected backend cannot be rendered by AndroidView")
    AndroidView(
        factory = systemController::attach,
        modifier = modifier,
        onRelease = systemController::detach,
    )
}
