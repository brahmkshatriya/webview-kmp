@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.InteropKeyEvent
import androidx.compose.ui.viewinterop.InteropPointerEvent
import androidx.compose.ui.viewinterop.InteropPointerEventType
import androidx.compose.ui.viewinterop.InteropRenderTarget
import androidx.compose.ui.viewinterop.NativeInteropView
import androidx.compose.ui.viewinterop.NativeView
import cnames.structs.KtnWebView2
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_can_go_back
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_can_go_forward
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_create
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_destroy
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_error
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_evaluate_javascript
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_go_back
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_go_forward
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_is_loading
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_is_ready
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_navigate
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_navigate_html
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_pointer_button
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_pointer_motion
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_progress
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_reload
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_render_pixels
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_runtime_available
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_runtime_status
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_scroll
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_set_focused
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_stop
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_title
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_url
import dev.brahmkshatriya.webview.internal.webview2.ktn_webview2_key
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Windows system WebView provider backed by the installed Evergreen WebView2 Runtime. */
public object SystemWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.System
    override val displayName: String = "Windows WebView2"
    override val capabilities: WebViewCapabilities =
        WebViewCapabilities.of(
            WebViewCapability.CustomUserAgent,
            WebViewCapability.JavaScriptEvaluation,
            WebViewCapability.CrossOriginJavaScriptEvaluation,
            WebViewCapability.NavigationState,
            WebViewCapability.JavaScriptControl,
        )

    override fun availability(): WebViewBackendAvailability =
        if (ktn_webview2_runtime_available() != 0) {
            WebViewBackendAvailability.Available
        } else {
            val reason =
                ktn_webview2_runtime_status()
                    ?.toKString()
                    ?.takeIf(String::isNotBlank)
                    ?: "The system WebView2 Runtime is unavailable"
            WebViewBackendAvailability.Unavailable(reason)
        }

    override fun create(config: WebViewConfig): WebViewController {
        require(config.mediaPlaybackRequiresUserGesture) {
            "Disabling the media-playback user-gesture requirement is not supported by the Windows WebView2 backend"
        }
        return SystemWebViewController(config)
    }
}

public fun platformBackendProviders(): List<WebViewBackendProvider> = listOf(SystemWebViewBackend)

/** Windows controller backed by the system-installed Microsoft Edge WebView2 Runtime. */
public class SystemWebViewController public constructor(
    private val config: WebViewConfig = WebViewConfig(),
) : WebViewController {
    private val mutableStates = MutableStateFlow(WebViewState())
    private val pendingCommands = mutableListOf<(CPointer<KtnWebView2>) -> Unit>()
    private var nativeHandle: CPointer<KtnWebView2>? = null
    private var stateRef: StableRef<SystemWebViewController>? = null
    private var closed: Boolean = false

    internal val interopView: NativeInteropView =
        NativeInteropView(
            renderer = ::render,
            continuousRendering = true,
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
            pendingCommands += { view -> ktn_webview2_navigate(view, url) }
            mutableStates.value = WebViewState(url = url, isLoading = true, progress = 0f)
        }
    }

    override fun loadUrl(url: String, headers: Map<String, String>): Unit {
        checkOpen()
        require(url.isNotBlank()) { "URL must not be blank" }
        require(headers.isEmpty()) {
            "Custom request headers are not supported by the Windows WebView2 backend"
        }
        mutableStates.value = state.copy(url = url, isLoading = true, progress = 0f, error = null)
        submit { view -> ktn_webview2_navigate(view, url) }
    }

    override fun loadHtml(html: String, baseUrl: String?): Unit {
        checkOpen()
        val content =
            baseUrl?.takeIf(String::isNotBlank)?.let { base ->
                "<base href=\"${escapeHtmlAttribute(base)}\">$html"
            } ?: html
        mutableStates.value =
            state.copy(url = baseUrl, isLoading = true, progress = 0f, error = null)
        submit { view -> ktn_webview2_navigate_html(view, content) }
    }

    override fun reload(): Unit {
        checkOpen()
        mutableStates.value = state.copy(isLoading = true, progress = 0f, error = null)
        submit { view -> ktn_webview2_reload(view) }
    }

    override fun stopLoading(): Unit {
        checkOpen()
        submit { view -> ktn_webview2_stop(view) }
    }

    override fun goBack(): Unit {
        checkOpen()
        submit { view -> ktn_webview2_go_back(view) }
    }

    override fun goForward(): Unit {
        checkOpen()
        submit { view -> ktn_webview2_go_forward(view) }
    }

    override fun evaluateJavaScript(
        script: String,
        callback: (JavaScriptResult) -> Unit,
    ): Unit {
        checkOpen()
        submit { view ->
            val callbackRef = StableRef.create(JavaScriptCallback(callback))
            ktn_webview2_evaluate_javascript(
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

    private fun ensureHandle(): CPointer<KtnWebView2> {
        checkOpen()
        nativeHandle?.let { return it }

        val callbackRef = StableRef.create(this)
        stateRef = callbackRef
        val created =
            memScoped {
                val userAgent =
                    when (val configured = config.userAgent) {
                        UserAgent.Default -> null
                        is UserAgent.Custom -> configured.value.cstr.getPointer(this)
                    }
                ktn_webview2_create(
                    if (config.javaScript == JavaScriptMode.Enabled) 1 else 0,
                    userAgent,
                    staticCFunction(::onWebView2StateChanged),
                    callbackRef.asCPointer(),
                )
            }
        if (created == null) {
            callbackRef.dispose()
            stateRef = null
            error("Could not create the WebView2 bridge")
        }
        nativeHandle = created
        refreshState()
        flushPendingIfReady()
        return created
    }

    private fun render(target: InteropRenderTarget): Boolean {
        val view = ensureHandle()
        return ktn_webview2_render_pixels(
            view,
            target.pixels,
            target.width,
            target.height,
            target.stride,
        ) != 0
    }

    private fun pointer(event: InteropPointerEvent): Boolean {
        val view = ensureHandle()
        when (event.type) {
            InteropPointerEventType.Move ->
                ktn_webview2_pointer_motion(
                    view,
                    event.x.toInt(),
                    event.y.toInt(),
                    event.timeMillis.toUInt(),
                    event.modifiers.toUInt(),
                )

            InteropPointerEventType.Button ->
                ktn_webview2_pointer_button(
                    view,
                    event.x.toInt(),
                    event.y.toInt(),
                    event.timeMillis.toUInt(),
                    event.button.toUInt(),
                    if (event.pressed) 1 else 0,
                    event.modifiers.toUInt(),
                )

            InteropPointerEventType.Scroll ->
                ktn_webview2_scroll(
                    view,
                    event.x.toInt(),
                    event.y.toInt(),
                    event.timeMillis.toUInt(),
                    event.scrollDeltaX.toDouble(),
                    event.scrollDeltaY.toDouble(),
                    event.modifiers.toUInt(),
                )
        }
        interopView.requestRender()
        return true
    }

    private fun key(event: InteropKeyEvent): Boolean {
        val view = ensureHandle()
        ktn_webview2_key(
            view,
            event.keyCode,
            event.codePoint.toUInt(),
            if (event.pressed) 1 else 0,
            event.modifiers.toUInt(),
        )
        interopView.requestRender()
        return true
    }

    private fun focus(focused: Boolean): Unit {
        val view = ensureHandle()
        ktn_webview2_set_focused(view, if (focused) 1 else 0)
    }

    private fun destroyNative(): Unit {
        val view = nativeHandle
        nativeHandle = null
        if (view != null) ktn_webview2_destroy(view)
        stateRef?.dispose()
        stateRef = null
    }

    internal fun nativeStateChanged(): Unit {
        if (closed) return
        refreshState()
        flushPendingIfReady()
        interopView.requestRender()
    }

    private fun submit(command: (CPointer<KtnWebView2>) -> Unit): Unit {
        val view = nativeHandle
        if (view != null && ktn_webview2_is_ready(view) != 0) {
            command(view)
        } else {
            pendingCommands += command
        }
    }

    private fun flushPendingIfReady(): Unit {
        val view = nativeHandle ?: return
        if (ktn_webview2_is_ready(view) == 0 || pendingCommands.isEmpty()) return
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { command -> command(view) }
    }

    private fun refreshState(): Unit {
        val view = nativeHandle ?: return
        val previous = mutableStates.value
        val url = ktn_webview2_url(view)?.toKString()?.takeIf(String::isNotBlank)
        val title = ktn_webview2_title(view)?.toKString()?.takeIf(String::isNotBlank)
        val error = ktn_webview2_error(view)?.toKString()?.takeIf(String::isNotBlank)
        mutableStates.value =
            previous.copy(
                url = url ?: previous.url,
                title = title ?: previous.title,
                isLoading = ktn_webview2_is_loading(view) != 0,
                progress = ktn_webview2_progress(view).coerceIn(0f, 1f),
                canGoBack = ktn_webview2_can_go_back(view) != 0,
                canGoForward = ktn_webview2_can_go_forward(view) != 0,
                error = error,
            )
    }

    private fun checkOpen(): Unit = check(!closed) { "WebViewController is closed" }
}

private class JavaScriptCallback(
    val callback: (JavaScriptResult) -> Unit,
)

private fun onWebView2StateChanged(userData: COpaquePointer?): Unit {
    userData?.asStableRef<SystemWebViewController>()?.get()?.nativeStateChanged()
}

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

private fun escapeHtmlAttribute(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '"' -> "&quot;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    else -> character
                },
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
            ?: error("The selected backend cannot be rendered by NativeView")
    NativeView(
        factory = { systemController.interopView },
        modifier = modifier,
    )
}
