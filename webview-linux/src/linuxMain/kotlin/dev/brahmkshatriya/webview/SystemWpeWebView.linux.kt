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
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_add_user_script
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_clear_cookies
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_create
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_delete_cookie
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_destroy
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_error
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_evaluate_javascript
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_get_cookies
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
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_set_cookie
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_set_focused
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_set_message_callback
import dev.brahmkshatriya.webview.internal.wpe.ktn_wpe_webview_set_navigation_callback
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import platform.posix.getenv
import platform.posix.system

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
            WebViewCapability.Cookies,
            WebViewCapability.NavigationInterception,
            WebViewCapability.UserScripts,
            WebViewCapability.Profiles,
            WebViewCapability.WebMessaging,
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
    private val mutableMessages = MutableSharedFlow<WebViewMessage>(extraBufferCapacity = 64)
    private val pendingCommands = mutableListOf<(CPointer<KtnWpeWebView>) -> Unit>()
    private var nativeHandle: CPointer<KtnWpeWebView>? = null
    private var navigationCallbackRef: StableRef<NavigationCallback>? = null
    private var messageCallbackRef: StableRef<SystemWebViewController>? = null
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
    override val messages: Flow<WebViewMessage> = mutableMessages
    override val state: WebViewState
        get() = mutableStates.value
    override val profile: WebViewProfile = config.profile
    override val cookies: WebViewCookieStore = WpeCookieStore()

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

    override fun postMessage(data: String): Unit {
        val quoted = JsonPrimitive(data).toString()
        evaluateJavaScript(
            """
            (() => {
                const event = new MessageEvent('message', { data: $quoted });
                if (window.webviewKmp && typeof window.webviewKmp.onmessage === 'function') {
                    window.webviewKmp.onmessage(event);
                }
                window.dispatchEvent(new MessageEvent('webview-kmp-message', { data: $quoted }));
            })();
            """.trimIndent(),
        ) {}
    }

    private inner class WpeCookieStore : WebViewCookieStore {
        override suspend fun get(url: String): List<WebViewCookie> {
            val text = awaitCookieCall { view, userData ->
                ktn_wpe_webview_get_cookies(
                    view,
                    url,
                    staticCFunction(::onCookieResult),
                    userData,
                )
            } ?: return emptyList()
            return Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                runCatching {
                    val item = element.jsonObject
                    val expires = item["expiresAtMillis"]?.jsonPrimitive?.longOrNull
                    WebViewCookie(
                        name = item.getValue("name").jsonPrimitive.contentOrNull.orEmpty(),
                        value = item.getValue("value").jsonPrimitive.contentOrNull.orEmpty(),
                        domain = item["domain"]?.jsonPrimitive?.contentOrNull,
                        path = item["path"]?.jsonPrimitive?.contentOrNull,
                        expiresAtMillis = expires?.takeIf { it >= 0 },
                        secure = item["secure"]?.jsonPrimitive?.booleanOrNull,
                        httpOnly = item["httpOnly"]?.jsonPrimitive?.booleanOrNull,
                        sameSite = when (item["sameSite"]?.jsonPrimitive?.intOrNull) {
                            0 -> WebViewCookieSameSite.None
                            1 -> WebViewCookieSameSite.Lax
                            2 -> WebViewCookieSameSite.Strict
                            else -> null
                        },
                    )
                }.getOrNull()
            }
        }

        override suspend fun set(url: String, cookie: WebViewCookie) {
            val domain = cookie.domain ?: cookieHost(url)
            awaitCookieCall { view, userData ->
                ktn_wpe_webview_set_cookie(
                    view = view,
                    name = cookie.name,
                    value = cookie.value,
                    domain = domain,
                    path = cookie.path ?: "/",
                    expires_at_millis = cookie.expiresAtMillis ?: -1L,
                    secure = cookie.secure.toNativeBoolean(),
                    http_only = cookie.httpOnly.toNativeBoolean(),
                    same_site = cookie.sameSite.toWpeSameSite(),
                    callback = staticCFunction(::onCookieResult),
                    user_data = userData,
                )
            }
        }

        override suspend fun delete(url: String, cookie: WebViewCookie) {
            val existing = get(url).firstOrNull { candidate ->
                candidate.name == cookie.name &&
                    (cookie.domain == null || candidate.domain == cookie.domain) &&
                    (cookie.path == null || candidate.path == cookie.path)
            } ?: return
            awaitCookieCall { view, userData ->
                ktn_wpe_webview_delete_cookie(
                    view = view,
                    name = existing.name,
                    value = existing.value,
                    domain = existing.domain ?: cookieHost(url),
                    path = existing.path ?: "/",
                    callback = staticCFunction(::onCookieResult),
                    user_data = userData,
                )
            }
        }

        override suspend fun clear() {
            awaitCookieCall { view, userData ->
                ktn_wpe_webview_clear_cookies(
                    view,
                    staticCFunction(::onCookieResult),
                    userData,
                )
            }
        }

        private suspend fun awaitCookieCall(
            call: (CPointer<KtnWpeWebView>, COpaquePointer?) -> Unit,
        ): String? = suspendCoroutine { continuation ->
            checkOpen()
            submit { view ->
                val ref = StableRef.create(CookieContinuation(continuation))
                call(view, ref.asCPointer())
            }
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
        val persistentDirectories = wpePersistentProfileDirectories(config.profile)
        val created =
            memScoped {
                val userAgent =
                    when (val configured = config.userAgent) {
                        UserAgent.Default -> null
                        is UserAgent.Custom -> configured.value.cstr.getPointer(this)
                    }
                val dataDirectory = persistentDirectories?.first?.cstr?.getPointer(this)
                val cacheDirectory = persistentDirectories?.second?.cstr?.getPointer(this)
                ktn_wpe_webview_create(
                    null,
                    if (config.javaScript == JavaScriptMode.Enabled) 1 else 0,
                    userAgent,
                    if (config.mediaPlaybackRequiresUserGesture) 1 else 0,
                    if (config.debugLogging) 1 else 0,
                    if (config.profile == WebViewProfile.Ephemeral) 1 else 0,
                    dataDirectory,
                    cacheDirectory,
                )
            } ?: error("Could not create WPE WebKit")
        nativeHandle = created
        config.navigationHandler?.let { handler ->
            val ref = StableRef.create(NavigationCallback(handler))
            navigationCallbackRef = ref
            ktn_wpe_webview_set_navigation_callback(
                created,
                staticCFunction(::onNavigationDecision),
                ref.asCPointer(),
            )
        }
        val messageRef = StableRef.create(this)
        messageCallbackRef = messageRef
        ktn_wpe_webview_set_message_callback(
            created,
            staticCFunction(::onWebMessage),
            messageRef.asCPointer(),
        )
        ktn_wpe_webview_add_user_script(
            created,
            wpeMessagingBridgeScript,
            0,
            0,
        )
        config.userScripts.forEach { script ->
            ktn_wpe_webview_add_user_script(
                created,
                script.source,
                if (script.injectionTime == WebViewUserScriptInjectionTime.DocumentStart) 0 else 1,
                if (script.mainFrameOnly) 1 else 0,
            )
        }
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
        ktn_wpe_webview_set_navigation_callback(view, null, null)
        ktn_wpe_webview_set_message_callback(view, null, null)
        ktn_wpe_webview_destroy(view)
        navigationCallbackRef?.dispose()
        navigationCallbackRef = null
        messageCallbackRef?.dispose()
        messageCallbackRef = null
    }

    internal fun receiveMessage(data: String): Unit {
        mutableMessages.tryEmit(WebViewMessage(data = data, url = state.url))
    }

    private fun checkOpen(): Unit = check(!closed) { "WebViewController is closed" }
}

private class JavaScriptCallback(
    val callback: (JavaScriptResult) -> Unit,
)

private class NavigationCallback(
    val handler: WebViewNavigationHandler,
)

private class CookieContinuation(
    val continuation: Continuation<String?>,
)

private fun onNavigationDecision(
    userData: COpaquePointer?,
    url: CPointer<ByteVar>?,
    method: CPointer<ByteVar>?,
    isMainFrame: Int,
    isRedirect: Int,
    hasUserGesture: Int,
): Int {
    val callback = userData?.asStableRef<NavigationCallback>()?.get() ?: return 0
    val requestUrl = url?.toKString() ?: return 0
    val decision =
        callback.handler.decide(
            WebViewNavigationRequest(
                url = requestUrl,
                method = method?.toKString() ?: "GET",
                isMainFrame = isMainFrame != 0,
                isRedirect = isRedirect != 0,
                hasUserGesture = hasUserGesture != 0,
            ),
        )
    return when (decision) {
        WebViewNavigationDecision.Allow -> 0
        WebViewNavigationDecision.Cancel -> 1
        WebViewNavigationDecision.OpenExternally -> 2
    }
}

private fun onWebMessage(
    userData: COpaquePointer?,
    data: CPointer<ByteVar>?,
): Unit {
    val controller = userData?.asStableRef<SystemWebViewController>()?.get() ?: return
    controller.receiveMessage(data?.toKString().orEmpty())
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

private fun onCookieResult(
    userData: COpaquePointer?,
    success: Int,
    value: CPointer<ByteVar>?,
): Unit {
    val stableRef = userData?.asStableRef<CookieContinuation>() ?: return
    val continuation = stableRef.get().continuation
    val text = value?.toKString()
    stableRef.dispose()
    if (success != 0) continuation.resume(text)
    else continuation.resumeWithException(IllegalStateException(text ?: "Cookie operation failed"))
}

private fun cookieHost(url: String): String {
    val authority = url.substringAfter("://", url).substringBefore('/')
    return authority.substringBefore(':').trim().also {
        require(it.isNotEmpty()) { "Cookie URL has no host: $url" }
    }
}

private fun Boolean?.toNativeBoolean(): Int = when (this) {
    true -> 1
    false -> 0
    null -> -1
}

private fun WebViewCookieSameSite?.toWpeSameSite(): Int = when (this) {
    WebViewCookieSameSite.None -> 0
    WebViewCookieSameSite.Lax -> 1
    WebViewCookieSameSite.Strict -> 2
    null -> -1
}

private val wpeMessagingBridgeScript: String =
    """
    (() => {
        const nativeBridge = window.webkit?.messageHandlers?.webviewKmp;
        if (!nativeBridge) return;
        window.webviewKmp = window.webviewKmp || {};
        window.webviewKmp.postMessage = data => nativeBridge.postMessage(String(data));
    })();
    """.trimIndent()

private fun wpePersistentProfileDirectories(profile: WebViewProfile): Pair<String, String>? {
    if (profile !is WebViewProfile.Persistent) return null
    val safeName =
        profile.name.map { char ->
            if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
        }.joinToString("")
    val dataHome =
        getenv("XDG_DATA_HOME")?.toKString()?.takeIf(String::isNotBlank)
            ?: getenv("HOME")?.toKString()?.let { "$it/.local/share" }
            ?: "/tmp"
    val cacheHome =
        getenv("XDG_CACHE_HOME")?.toKString()?.takeIf(String::isNotBlank)
            ?: getenv("HOME")?.toKString()?.let { "$it/.cache" }
            ?: "/tmp"
    val dataDirectory = "$dataHome/webview-kmp/profiles/wpe/$safeName"
    val cacheDirectory = "$cacheHome/webview-kmp/profiles/wpe/$safeName"
    check(system("mkdir -p -- '${dataDirectory.replace("'", "'\\''")}' '${cacheDirectory.replace("'", "'\\''")}'") == 0) {
        "Could not create WPE WebKit profile directories"
    }
    return dataDirectory to cacheDirectory
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
