@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.Foundation.NSURL
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/** iOS system WebKit provider. */
public object SystemWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.System
    override val displayName: String = "iOS WKWebView"
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
            WebViewCapability.Cookies,
            WebViewCapability.NavigationInterception,
            WebViewCapability.UserScripts,
            WebViewCapability.Profiles,
            WebViewCapability.WebMessaging,
        )
    override fun availability(): WebViewBackendAvailability = WebViewBackendAvailability.Available
    override fun create(config: WebViewConfig): WebViewController {
        require(config.profile !is WebViewProfile.Persistent || supportsPersistentAppleProfiles()) {
            "Named persistent WKWebView profiles require iOS 17 or newer"
        }
        return SystemWebViewController(config)
    }
}

public fun platformBackendProviders(): List<WebViewBackendProvider> = listOf(SystemWebViewBackend)

/** iOS controller backed by the WebKit framework's [WKWebView]. */
public class SystemWebViewController public constructor(
    private val config: WebViewConfig = WebViewConfig(),
) : WebViewController {
    private val mutableStates = MutableStateFlow(WebViewState())
    private val mutableMessages = MutableSharedFlow<WebViewMessage>(extraBufferCapacity = 64)
    private val pendingCommands = mutableListOf<(WKWebView) -> Unit>()
    private var nativeView: WKWebView? = null
    private var closed: Boolean = false
    private val navigationDelegate = NavigationDelegate(this)
    private val messageHandler = MessageHandler(this)
    private val websiteDataStore: WKWebsiteDataStore =
        when (val selectedProfile = config.profile) {
            WebViewProfile.Default -> WKWebsiteDataStore.defaultDataStore()
            WebViewProfile.Ephemeral -> WKWebsiteDataStore.nonPersistentDataStore()
            is WebViewProfile.Persistent ->
                WKWebsiteDataStore.dataStoreForIdentifier(appleProfileIdentifier(selectedProfile.name))
        }

    override val backendId: WebViewBackendId = WebViewBackendId.System
    override val capabilities: WebViewCapabilities = SystemWebViewBackend.capabilities
    override val states: StateFlow<WebViewState> = mutableStates
    override val state: WebViewState
        get() = mutableStates.value
    override val messages: Flow<WebViewMessage> = mutableMessages
    override val profile: WebViewProfile = config.profile
    override val cookies: WebViewCookieStore = AppleCookieStore(websiteDataStore.httpCookieStore)

    init {
        config.initialUrl?.takeIf(String::isNotBlank)?.let { url ->
            pendingCommands += { it.loadRequest(request(url, emptyMap())) }
        }
    }

    override fun loadUrl(url: String, headers: Map<String, String>): Unit {
        require(url.isNotBlank()) { "URL must not be blank" }
        withView { view ->
            mutableStates.value = state.copy(url = url, isLoading = true, progress = 0f, error = null)
            view.loadRequest(request(url, headers))
        }
    }

    override fun loadHtml(html: String, baseUrl: String?): Unit {
        withView { view ->
            val base = baseUrl?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString)
            mutableStates.value = state.copy(isLoading = true, progress = 0f, error = null)
            view.loadHTMLString(html, baseURL = base)
        }
    }

    override fun reload(): Unit = withView { it.reload() }

    override fun stopLoading(): Unit = withView { it.stopLoading() }

    override fun goBack(): Unit = withView { if (it.canGoBack) it.goBack() }

    override fun goForward(): Unit = withView { if (it.canGoForward) it.goForward() }

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

    override fun postMessage(data: String): Unit {
        val quoted = quoteJavaScriptString(data)
        withView { view ->
            view.evaluateJavaScript(
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
        nativeView?.configuration?.userContentController?.removeScriptMessageHandlerForName("webviewKmp")
        nativeView?.navigationDelegate = null
        nativeView?.stopLoading()
        nativeView = null
    }

    internal fun attach(): WKWebView {
        check(!closed) { "WebViewController is closed" }
        nativeView?.let { return it }

        val configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = websiteDataStore
        configuration.defaultWebpagePreferences.allowsContentJavaScript =
            config.javaScript == JavaScriptMode.Enabled
        configuration.mediaTypesRequiringUserActionForPlayback =
            if (config.mediaPlaybackRequiresUserGesture) {
                WKAudiovisualMediaTypeAll
            } else {
                WKAudiovisualMediaTypeNone
            }
        configuration.userContentController.addScriptMessageHandler(messageHandler, name = "webviewKmp")
        configuration.userContentController.addUserScript(
            WKUserScript(
                source = webViewKmpBridgeScript(),
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = false,
            ),
        )
        config.userScripts.forEach { script ->
            configuration.userContentController.addUserScript(
                WKUserScript(
                    source = script.source,
                    injectionTime =
                        when (script.injectionTime) {
                            WebViewUserScriptInjectionTime.DocumentStart ->
                                WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart
                            WebViewUserScriptInjectionTime.DocumentEnd ->
                                WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentEnd
                        },
                    forMainFrameOnly = script.mainFrameOnly,
                ),
            )
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
        commands.forEach { it(view) }
        refreshState(view)
        return view
    }

    internal fun detach(view: WKWebView): Unit {
        if (nativeView !== view) return
        view.configuration.userContentController.removeScriptMessageHandlerForName("webviewKmp")
        view.navigationDelegate = null
        view.stopLoading()
        nativeView = null
    }

    internal fun refreshState(view: WKWebView, error: String? = state.error): Unit {
        mutableStates.value =
            WebViewState(
                url = view.URL?.absoluteString,
                title = view.title,
                isLoading = view.loading,
                progress = view.estimatedProgress.toFloat().coerceIn(0f, 1f),
                canGoBack = view.canGoBack,
                canGoForward = view.canGoForward,
                error = error,
            )
    }

    internal fun decideNavigation(action: WKNavigationAction): WebViewNavigationDecision {
        val url = action.request.URL?.absoluteString ?: return WebViewNavigationDecision.Allow
        val request =
            WebViewNavigationRequest(
                url = url,
                method = "GET",
                isMainFrame = action.targetFrame?.mainFrame ?: true,
            )
        return config.navigationHandler?.decide(request) ?: WebViewNavigationDecision.Allow
    }

    internal fun openExternally(url: String): Unit {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target)
    }

    internal fun receiveMessage(data: String): Unit {
        mutableMessages.tryEmit(WebViewMessage(data = data, url = state.url))
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

private fun supportsPersistentAppleProfiles(): Boolean =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion >= 17 }

private fun appleProfileIdentifier(name: String): NSUUID {
    var high = 0xcbf29ce484222325uL
    var low = 0x84222325cbf29ce4uL
    name.encodeToByteArray().forEachIndexed { index, byte ->
        val value = byte.toUByte().toULong()
        high = (high xor value) * 0x100000001b3uL
        low = (low xor (value + index.toULong())) * 0x100000001b3uL
    }
    // Mark the custom deterministic identifier as UUID version 8 / RFC 4122 variant.
    high = (high and 0xffffffffffff0fffuL) or 0x0000000000008000uL
    low = (low and 0x3fffffffffffffffuL) or 0x8000000000000000uL
    val hex = high.toString(16).padStart(16, '0') + low.toString(16).padStart(16, '0')
    val uuid = buildString(36) {
        append(hex, 0, 8); append('-')
        append(hex, 8, 12); append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }
    return requireNotNull(NSUUID(uUIDString = uuid)) { "Could not create WebKit profile identifier" }
}

private class MessageHandler(
    private val controller: SystemWebViewController,
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ): Unit {
        controller.receiveMessage(didReceiveScriptMessage.body.toString())
    }
}

private fun webViewKmpBridgeScript(): String =
    """
    (() => {
        const nativeBridge = window.webkit?.messageHandlers?.webviewKmp;
        if (!nativeBridge) return;
        window.webviewKmp = window.webviewKmp || {};
        window.webviewKmp.postMessage = data => nativeBridge.postMessage(String(data));
    })();
    """.trimIndent()

private fun quoteJavaScriptString(value: String): String =
    buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

private class NavigationDelegate(
    private val controller: SystemWebViewController,
) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ): Unit {
        when (controller.decideNavigation(decidePolicyForNavigationAction)) {
            WebViewNavigationDecision.Allow ->
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            WebViewNavigationDecision.Cancel ->
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            WebViewNavigationDecision.OpenExternally -> {
                decidePolicyForNavigationAction.request.URL?.absoluteString?.let(controller::openExternally)
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?): Unit {
        controller.refreshState(webView, error = null)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?): Unit {
        controller.refreshState(webView, error = null)
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
            ?: error("The selected backend cannot be rendered by UIKitView")
    UIKitView(
        factory = systemController::attach,
        modifier = modifier,
        onRelease = systemController::detach,
    )
}
