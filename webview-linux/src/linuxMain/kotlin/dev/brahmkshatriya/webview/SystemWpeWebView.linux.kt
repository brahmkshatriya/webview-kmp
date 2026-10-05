@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.InteropKeyEvent
import androidx.compose.ui.viewinterop.InteropPointerEvent
import androidx.compose.ui.viewinterop.InteropPointerEventType
import androidx.compose.ui.viewinterop.NativeInteropView
import androidx.compose.ui.viewinterop.NativeView
import androidx.compose.ui.viewinterop.OpenGlInteropRenderTarget
import cnames.structs.KtnWpeWebView
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_can_go_back
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_can_go_forward
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_create
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_destroy
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_error
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_evaluate_javascript
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_go_back
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_go_forward
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_is_loading
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_key
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_load_html
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_load_uri
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_load_uri_with_headers
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_pointer_button
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_pointer_motion
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_progress
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_reload
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_render
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_scroll
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_set_focused
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_stop
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_title
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_url
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Linux system WebView backed by WPE WebKit. */
public object SystemWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.System
    override val displayName: String = "WPE WebKit"
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

public fun platformBackendProviders(): List<WebViewBackendProvider> =
    listOf(SystemWebViewBackend, ChromiumWebViewBackend, FirefoxWebViewBackend)

/** Controller for the Linux system WPE WebKit backend. */
public class SystemWebViewController public constructor(
    private val config: WebViewConfig = WebViewConfig(),
) : WebViewController {
    private val mutableStates = MutableStateFlow(WebViewState())
    private val pendingCommands = mutableListOf<(CPointer<KtnWpeWebView>) -> Unit>()
    private var nativeHandle: CPointer<KtnWpeWebView>? = null
    private var closed: Boolean = false

    internal val interopView: NativeInteropView =
        NativeInteropView.openGl(
            renderer = ::render,
            continuousRendering = true,
            releaser = ::destroyNative,
            pointerHandler = ::pointer,
            keyHandler = ::key,
            focusHandler = ::focus,
        )

    override val backendId: WebViewBackendId = WebViewBackendId.System
    override val capabilities: WebViewCapabilities = SystemWebViewBackend.capabilities
    override val states: StateFlow<WebViewState> = mutableStates
    override val state: WebViewState
        get() = mutableStates.value

    init {
        config.initialUrl?.takeIf(String::isNotBlank)?.let { url ->
            mutableStates.value = WebViewState(url = url, isLoading = true)
            pendingCommands += { view -> ktn_wpe_webview_load_uri(view, url) }
        }
    }

    override fun loadUrl(url: String, headers: Map<String, String>): Unit {
        checkOpen()
        require(url.isNotBlank()) { "URL must not be blank" }
        mutableStates.value = state.copy(url = url, isLoading = true, progress = 0f, error = null)
        submit { view -> loadUrlNative(view, url, headers) }
    }

    override fun loadHtml(html: String, baseUrl: String?): Unit {
        checkOpen()
        mutableStates.value = state.copy(url = baseUrl, isLoading = true, progress = 0f, error = null)
        submit { view -> ktn_wpe_webview_load_html(view, html, baseUrl) }
    }

    override fun reload(): Unit {
        checkOpen()
        mutableStates.value = state.copy(isLoading = true, progress = 0f, error = null)
        submit(::ktn_wpe_webview_reload)
    }

    override fun stopLoading(): Unit {
        checkOpen()
        submit(::ktn_wpe_webview_stop)
    }

    override fun goBack(): Unit {
        checkOpen()
        submit(::ktn_wpe_webview_go_back)
    }

    override fun goForward(): Unit {
        checkOpen()
        submit(::ktn_wpe_webview_go_forward)
    }

    override fun evaluateJavaScript(
        script: String,
        callback: (JavaScriptResult) -> Unit,
    ): Unit {
        checkOpen()
        submit { view ->
            val callbackRef = StableRef.create(JavaScriptCallback(callback))
            ktn_wpe_webview_evaluate_javascript(
                view,
                script,
                staticCFunction(::onJavaScriptResult),
                callbackRef.asCPointer(),
            )
        }
    }

    override fun close(): Unit {
        if (closed) return
        closed = true
        pendingCommands.clear()
        destroyNative()
    }

    private fun render(target: OpenGlInteropRenderTarget): Boolean {
        if (closed) return false
        val view = ensureHandle()
        val rendered =
            ktn_wpe_webview_render(
                view,
                target.framebuffer,
                target.width,
                target.height,
                target.density,
            ) != 0
        flushPending(view)
        refreshState(view)
        return rendered
    }

    private fun ensureHandle(): CPointer<KtnWpeWebView> {
        nativeHandle?.let { return it }
        val created =
            memScoped {
                val userAgent =
                    when (val configured = config.userAgent) {
                        UserAgent.Default -> null
                        is UserAgent.Custom -> configured.value.cstr.getPointer(this)
                    }
                ktn_wpe_webview_create(
                    null,
                    if (config.javaScript == JavaScriptMode.Enabled) 1 else 0,
                    userAgent,
                    if (config.mediaPlaybackRequiresUserGesture) 1 else 0,
                    if (config.debugLogging) 1 else 0,
                )
            } ?: error("Could not create WPE WebKit")
        nativeHandle = created
        val error = ktn_wpe_webview_error(created)?.toKString()?.takeIf(String::isNotBlank)
        if (error != null) mutableStates.value = state.copy(isLoading = false, error = error)
        return created
    }

    private fun loadUrlNative(
        view: CPointer<KtnWpeWebView>,
        url: String,
        headers: Map<String, String>,
    ): Unit {
        if (headers.isEmpty()) {
            ktn_wpe_webview_load_uri(view, url)
            return
        }
        memScoped {
            val names = allocArray<CPointerVar<ByteVar>>(headers.size)
            val values = allocArray<CPointerVar<ByteVar>>(headers.size)
            headers.entries.forEachIndexed { index, (name, value) ->
                names[index] = name.cstr.getPointer(this)
                values[index] = value.cstr.getPointer(this)
            }
            ktn_wpe_webview_load_uri_with_headers(view, url, names, values, headers.size)
        }
    }

    private fun submit(command: (CPointer<KtnWpeWebView>) -> Unit): Unit {
        val view = nativeHandle
        if (view == null) {
            pendingCommands += command
        } else {
            command(view)
            interopView.requestRender()
        }
    }

    private fun flushPending(view: CPointer<KtnWpeWebView>): Unit {
        if (pendingCommands.isEmpty()) return
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { command -> command(view) }
    }

    private fun pointer(event: InteropPointerEvent): Boolean {
        val view = nativeHandle ?: return false
        when (event.type) {
            InteropPointerEventType.Move ->
                ktn_wpe_webview_pointer_motion(
                    view,
                    event.x.toInt(),
                    event.y.toInt(),
                    event.timeMillis.toUInt(),
                    event.modifiers.toUInt(),
                )

            InteropPointerEventType.Button ->
                ktn_wpe_webview_pointer_button(
                    view,
                    event.x.toInt(),
                    event.y.toInt(),
                    event.timeMillis.toUInt(),
                    event.button.toUInt(),
                    if (event.pressed) 1 else 0,
                    event.modifiers.toUInt(),
                )

            InteropPointerEventType.Scroll ->
                ktn_wpe_webview_scroll(
                    view,
                    event.x.toInt(),
                    event.y.toInt(),
                    event.timeMillis.toUInt(),
                    event.scrollDeltaX.toDouble(),
                    event.scrollDeltaY.toDouble(),
                    event.modifiers.toUInt(),
                )
        }
        return true
    }

    private fun key(event: InteropKeyEvent): Boolean {
        val view = nativeHandle ?: return false
        ktn_wpe_webview_key(
            view,
            event.keyCode,
            event.codePoint.toUInt(),
            if (event.pressed) 1 else 0,
            event.modifiers.toUInt(),
        )
        return true
    }

    private fun focus(focused: Boolean): Unit {
        nativeHandle?.let { view -> ktn_wpe_webview_set_focused(view, if (focused) 1 else 0) }
    }

    private fun refreshState(view: CPointer<KtnWpeWebView>): Unit {
        val previous = mutableStates.value
        mutableStates.value =
            previous.copy(
                url = ktn_wpe_webview_url(view)?.toKString()?.takeIf(String::isNotBlank) ?: previous.url,
                title = ktn_wpe_webview_title(view)?.toKString()?.takeIf(String::isNotBlank),
                isLoading = ktn_wpe_webview_is_loading(view) != 0,
                progress = ktn_wpe_webview_progress(view).coerceIn(0f, 1f),
                canGoBack = ktn_wpe_webview_can_go_back(view) != 0,
                canGoForward = ktn_wpe_webview_can_go_forward(view) != 0,
                error = ktn_wpe_webview_error(view)?.toKString()?.takeIf(String::isNotBlank),
            )
    }

    private fun destroyNative(): Unit {
        val view = nativeHandle ?: return
        nativeHandle = null
        ktn_wpe_webview_destroy(view)
    }

    private fun checkOpen(): Unit = check(!closed) { "WebViewController is closed" }
}

private class JavaScriptCallback(
    val callback: (JavaScriptResult) -> Unit,
)

private fun onJavaScriptResult(
    userData: COpaquePointer?,
    success: Int,
    value: CPointer<ByteVar>?,
): Unit {
    val stableRef = userData?.asStableRef<JavaScriptCallback>() ?: return
    val callback = stableRef.get().callback
    val text = value?.toKString()
    stableRef.dispose()
    if (success != 0) {
        callback(JavaScriptResult.Value(text))
    } else {
        callback(JavaScriptResult.Error(text ?: "JavaScript evaluation failed"))
    }
}

@Composable
public fun PlatformWebView(
    controller: WebViewController,
    modifier: Modifier,
): Unit {
    when (controller) {
        is SystemWebViewController ->
            NativeView(
                factory = { controller.interopView },
                modifier = modifier,
            )

        is BrowserAppViewController -> BrowserPlatformWebView(controller, modifier)
        else -> error("The selected Linux backend cannot be rendered")
    }
}
