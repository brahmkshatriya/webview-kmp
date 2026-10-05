@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AppKitInteropPlacement
import androidx.compose.ui.viewinterop.AppKitInteropProperties
import androidx.compose.ui.viewinterop.AppKitView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setValue
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/** macOS system WebKit provider. The browser engine is supplied by macOS. */
public object SystemWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.System
    override val displayName: String = "macOS WKWebView"
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

    override fun availability(): WebViewBackendAvailability = WebViewBackendAvailability.Available

    override fun create(config: WebViewConfig): WebViewController = SystemWebViewController(config)
}

public fun platformBackendProviders(): List<WebViewBackendProvider> = listOf(SystemWebViewBackend)

/** macOS controller backed by the system WebKit framework's [WKWebView]. */
public class SystemWebViewController public constructor(
    private val config: WebViewConfig = WebViewConfig(),
) : WebViewController {
    private val mutableStates = MutableStateFlow(WebViewState())
    private val pendingCommands = mutableListOf<(WKWebView) -> Unit>()
    private val navigationDelegate = NavigationDelegate(this)
    private var nativeView: WKWebView? = null
    private var closed: Boolean = false

    override val backendId: WebViewBackendId = WebViewBackendId.System
    override val capabilities: WebViewCapabilities = SystemWebViewBackend.capabilities
    override val states: StateFlow<WebViewState> = mutableStates
    override val state: WebViewState
        get() = mutableStates.value

    init {
        config.initialUrl?.takeIf(String::isNotBlank)?.let { url ->
            pendingCommands += { view ->
                mutableStates.value =
                    state.copy(url = url, isLoading = true, progress = 0f, error = null)
                view.loadRequest(request(url, emptyMap()))
            }
        }
    }

    override fun loadUrl(url: String, headers: Map<String, String>): Unit {
        require(url.isNotBlank()) { "URL must not be blank" }
        withView { view ->
            mutableStates.value =
                state.copy(url = url, isLoading = true, progress = 0f, error = null)
            view.loadRequest(request(url, headers))
        }
    }

    override fun loadHtml(html: String, baseUrl: String?): Unit {
        withView { view ->
            val base = baseUrl?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString)
            mutableStates.value =
                state.copy(url = baseUrl, isLoading = true, progress = 0f, error = null)
            view.loadHTMLString(html, baseURL = base)
        }
    }

    override fun reload(): Unit {
        withView { view ->
            mutableStates.value = state.copy(isLoading = true, progress = 0f, error = null)
            view.reload()
        }
    }

    override fun stopLoading(): Unit {
        withView { view ->
            view.stopLoading()
            refreshState(view)
        }
    }

    override fun goBack(): Unit {
        withView { view ->
            if (view.canGoBack) {
                mutableStates.value = state.copy(isLoading = true, progress = 0f, error = null)
                view.goBack()
            }
        }
    }

    override fun goForward(): Unit {
        withView { view ->
            if (view.canGoForward) {
                mutableStates.value = state.copy(isLoading = true, progress = 0f, error = null)
                view.goForward()
            }
        }
    }

    override fun evaluateJavaScript(
        script: String,
        callback: (JavaScriptResult) -> Unit,
    ): Unit {
        withView { view ->
            view.evaluateJavaScript(script) { result, error ->
                if (error != null) {
                    callback(JavaScriptResult.Error(error.localizedDescription))
                } else {
                    callback(JavaScriptResult.Value(result?.toString()))
                }
            }
        }
    }

    override fun close(): Unit {
        if (closed) return
        closed = true
        pendingCommands.clear()
        nativeView?.navigationDelegate = null
        nativeView?.stopLoading()
        nativeView = null
    }

    internal fun attach(): WKWebView {
        check(!closed) { "WebViewController is closed" }
        nativeView?.let { return it }

        val configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript =
            config.javaScript == JavaScriptMode.Enabled
        configuration.mediaTypesRequiringUserActionForPlayback =
            if (config.mediaPlaybackRequiresUserGesture) {
                WKAudiovisualMediaTypeAll
            } else {
                WKAudiovisualMediaTypeNone
            }

        val view =
            WKWebView(
                frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = configuration,
            )
        nativeView = view
        view.navigationDelegate = navigationDelegate
        when (val userAgent = config.userAgent) {
            UserAgent.Default -> Unit
            is UserAgent.Custom -> view.customUserAgent = userAgent.value
        }

        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { command -> command(view) }
        refreshState(view)
        return view
    }

    internal fun detach(view: WKWebView): Unit {
        if (nativeView !== view) return
        view.navigationDelegate = null
        view.stopLoading()
        nativeView = null
    }

    internal fun refreshState(
        view: WKWebView,
        error: String? = state.error,
        progress: Float = view.estimatedProgress.toFloat().coerceIn(0f, 1f),
    ): Unit {
        mutableStates.value =
            WebViewState(
                url = view.URL?.absoluteString,
                title = view.title,
                isLoading = view.loading,
                progress = progress,
                canGoBack = view.canGoBack,
                canGoForward = view.canGoForward,
                error = error,
            )
    }

    private fun withView(command: (WKWebView) -> Unit): Unit {
        check(!closed) { "WebViewController is closed" }
        val view = nativeView
        if (view == null) pendingCommands += command else command(view)
    }

    private fun request(url: String, headers: Map<String, String>): NSMutableURLRequest {
        val nsUrl = NSURL.URLWithString(url) ?: error("Invalid URL: $url")
        return NSMutableURLRequest.requestWithURL(nsUrl).apply {
            headers.forEach { (name, value) -> setValue(value, name) }
        }
    }
}

private class NavigationDelegate(
    private val controller: SystemWebViewController,
) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?): Unit {
        controller.refreshState(webView, error = null, progress = 0f)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation: WKNavigation?): Unit {
        controller.refreshState(webView, error = null)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?): Unit {
        controller.refreshState(webView, error = null)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?): Unit {
        controller.refreshState(webView, error = null, progress = 1f)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError,
    ): Unit {
        controller.refreshState(webView, error = withError.localizedDescription)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ): Unit {
        controller.refreshState(webView, error = withError.localizedDescription)
    }
}

@Composable
public fun PlatformWebView(
    controller: WebViewController,
    modifier: Modifier,
): Unit {
    val systemController =
        controller as? SystemWebViewController
            ?: error("The selected backend cannot be rendered by AppKitView")
    AppKitView(
        factory = systemController::attach,
        modifier = modifier,
        onRelease = systemController::detach,
        properties =
            AppKitInteropProperties(
                placement = AppKitInteropPlacement.Background,
                isInteractive = true,
                clipToBounds = true,
            ),
    )
}
