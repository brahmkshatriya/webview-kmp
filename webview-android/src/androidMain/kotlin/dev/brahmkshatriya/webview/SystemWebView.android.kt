package dev.brahmkshatriya.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID

/** Android's framework WebView provider. No browser engine is packaged by this library. */
public object SystemWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.System
    override val displayName: String = "Android System WebView"
    override val capabilities: WebViewCapabilities
        get() = WebViewCapabilities.of(
            WebViewCapability.CustomRequestHeaders,
            WebViewCapability.CustomUserAgent,
            WebViewCapability.JavaScriptEvaluation,
            WebViewCapability.CrossOriginJavaScriptEvaluation,
            WebViewCapability.NavigationState,
            WebViewCapability.LoadingProgress,
            WebViewCapability.JavaScriptControl,
            WebViewCapability.MediaPlaybackPolicy,
            WebViewCapability.Cookies,
            WebViewCapability.NavigationInterception,
            WebViewCapability.UserScripts,
            WebViewCapability.WebMessaging,
            *if (supportsProfiles()) {
                arrayOf(WebViewCapability.Profiles)
            } else {
                emptyArray()
            },
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

    override fun create(config: WebViewConfig): WebViewController {
        require(config.profile == WebViewProfile.Default || supportsProfiles()) {
            "This Android System WebView does not support multiple profiles"
        }
        return SystemWebViewController(config)
    }

    private fun supportsProfiles(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
}

public fun platformBackendProviders(): List<WebViewBackendProvider> = listOf(SystemWebViewBackend)

/** Android controller backed by [android.webkit.WebView]. */
public class SystemWebViewController public constructor(
    private val config: WebViewConfig = WebViewConfig(),
) : WebViewController {
    private val mutableStates = MutableStateFlow(WebViewState())
    private val mutableMessages = MutableSharedFlow<WebViewMessage>(extraBufferCapacity = 64)
    private val pendingCommands = mutableListOf<(WebView) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val androidProfileName: String? = config.profile.androidProfileName()
    private var profileCookieManager: CookieManager? = null
    private var nativeView: WebView? = null
    private var closed: Boolean = false

    override val backendId: WebViewBackendId = WebViewBackendId.System
    override val capabilities: WebViewCapabilities = SystemWebViewBackend.capabilities
    override val states: StateFlow<WebViewState> = mutableStates
    override val state: WebViewState
        get() = mutableStates.value
    override val messages: Flow<WebViewMessage> = mutableMessages
    override val profile: WebViewProfile = config.profile
    override val cookies: WebViewCookieStore =
        AndroidCookieStore {
            if (config.profile == WebViewProfile.Default) {
                CookieManager.getInstance()
            } else {
                profileCookieManager
            }
        }

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

    override fun postMessage(data: String): Unit {
        val quoted = JSONObject.quote(data)
        withView { view ->
            view.evaluateJavascript(
                """
                (() => {
                    const event = new MessageEvent('message', { data: $quoted });
                    if (window.webviewKmp && typeof window.webviewKmp.onmessage === 'function') {
                        window.webviewKmp.onmessage(event);
                    }
                    window.dispatchEvent(new MessageEvent('webview-kmp-message', { data: $quoted }));
                })();
                """.trimIndent(),
                null,
            )
        }
    }

    override fun close(): Unit {
        if (closed) return
        closed = true
        pendingCommands.clear()
        val view = nativeView
        nativeView = null
        if (view != null) {
            destroyView(view, deleteEphemeralProfile = config.profile == WebViewProfile.Ephemeral)
        } else if (config.profile == WebViewProfile.Ephemeral) {
            scheduleEphemeralProfileDeletion()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun attach(context: Context): WebView {
        check(!closed) { "WebViewController is closed" }
        nativeView?.let { return it }

        val view = WebView(context)
        androidProfileName?.let { profileName ->
            WebViewCompat.setProfile(view, profileName)
            profileCookieManager = WebViewCompat.getProfile(view).cookieManager
        }
        nativeView = view
        view.settings.javaScriptEnabled = config.javaScript == JavaScriptMode.Enabled
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = config.mediaPlaybackRequiresUserGesture
        when (val userAgent = config.userAgent) {
            UserAgent.Default -> Unit
            is UserAgent.Custom -> view.settings.userAgentString = userAgent.value
        }
        if (config.debugLogging) WebView.setWebContentsDebuggingEnabled(true)

        view.addJavascriptInterface(
            AndroidMessageBridge { data ->
                mutableMessages.tryEmit(WebViewMessage(data = data, url = state.url))
            },
            "webviewKmp",
        )

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
        destroyView(view, deleteEphemeralProfile = false)
    }

    private fun destroyView(view: WebView, deleteEphemeralProfile: Boolean): Unit {
        val destroy = {
            view.stopLoading()
            view.removeJavascriptInterface("webviewKmp")
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.destroy()
            profileCookieManager = null
            if (deleteEphemeralProfile) scheduleEphemeralProfileDeletion()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) destroy() else mainHandler.post(destroy)
    }

    private fun scheduleEphemeralProfileDeletion(attempt: Int = 0): Unit {
        val profileName = androidProfileName ?: return
        mainHandler.post {
            try {
                ProfileStore.getInstance().deleteProfile(profileName)
            } catch (failure: IllegalStateException) {
                if (attempt < 3) {
                    mainHandler.postDelayed(
                        { scheduleEphemeralProfileDeletion(attempt + 1) },
                        50L * (attempt + 1),
                    )
                } else {
                    Log.w(TAG, "Could not delete ephemeral WebView profile $profileName", failure)
                }
            }
        }
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
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean =
                handleNavigation(
                    view,
                    WebViewNavigationRequest(
                        url = request.url.toString(),
                        method = request.method,
                        isMainFrame = request.isForMainFrame,
                        isRedirect = request.isRedirect,
                        hasUserGesture = request.hasGesture(),
                    ),
                )

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?): Unit {
                updateState(view, url = url, loading = true, progress = 0f, error = null)
                injectScripts(view, WebViewUserScriptInjectionTime.DocumentStart)
            }

            override fun onPageFinished(view: WebView, url: String?): Unit {
                injectScripts(view, WebViewUserScriptInjectionTime.DocumentEnd)
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

    private fun handleNavigation(
        view: WebView,
        request: WebViewNavigationRequest,
    ): Boolean =
        when (config.navigationHandler?.decide(request) ?: WebViewNavigationDecision.Allow) {
            WebViewNavigationDecision.Allow -> false
            WebViewNavigationDecision.Cancel -> true
            WebViewNavigationDecision.OpenExternally -> {
                runCatching {
                    view.context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(request.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                true
            }
        }

    private fun injectScripts(
        view: WebView,
        injectionTime: WebViewUserScriptInjectionTime,
    ): Unit {
        config.userScripts
            .asSequence()
            .filter { it.injectionTime == injectionTime }
            .forEach { script -> view.evaluateJavascript(script.source, null) }
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

    private companion object {
        const val TAG: String = "webview-kmp"
    }
}

private fun WebViewProfile.androidProfileName(): String? =
    when (this) {
        WebViewProfile.Default -> null
        WebViewProfile.Ephemeral -> "webview-kmp-ephemeral-${UUID.randomUUID()}"
        is WebViewProfile.Persistent ->
            "webview-kmp-${UUID.nameUUIDFromBytes("persistent:$name".toByteArray())}"
    }

private class AndroidMessageBridge(
    private val onMessage: (String) -> Unit,
) {
    @JavascriptInterface
    public fun postMessage(data: String): Unit = onMessage(data)
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
