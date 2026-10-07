package dev.brahmkshatriya.webview

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

public data class WebViewState(
    public val url: String? = null,
    public val title: String? = null,
    public val isLoading: Boolean = false,
    public val progress: Float = 0f,
    public val canGoBack: Boolean = false,
    public val canGoForward: Boolean = false,
    public val error: String? = null,
)

public sealed interface JavaScriptResult {
    public data class Value(public val json: String?) : JavaScriptResult

    public data class Error(public val message: String) : JavaScriptResult
}

public sealed interface WebViewEvent {
    public data class UrlChanged(public val url: String?) : WebViewEvent

    public data class NavigationStarted(public val url: String?) : WebViewEvent

    public data class NavigationFinished(public val url: String?) : WebViewEvent

    public data class NavigationFailed(
        public val url: String?,
        public val message: String,
    ) : WebViewEvent
}

public data class WebViewMessage(
    public val data: String,
    public val url: String? = null,
)

public interface WebViewController {
    public val backendId: WebViewBackendId
    public val capabilities: WebViewCapabilities

    public val state: WebViewState

    public val states: StateFlow<WebViewState>

    /** Profile/session descriptor used when this controller was created. */
    public val profile: WebViewProfile
        get() = WebViewProfile.Default

    /** Navigation lifecycle events derived from [states]. */
    public val events: Flow<WebViewEvent>
        get() = stateEvents(states)

    /** Messages posted by page JavaScript through `window.webviewKmp.postMessage(...)`. */
    public val messages: Flow<WebViewMessage>
        get() = emptyFlow()

    /** Cookie jar used by this controller's browser profile/session. */
    public val cookies: WebViewCookieStore
        get() = UnsupportedWebViewCookieStore

    public fun loadUrl(url: String, headers: Map<String, String> = emptyMap())

    public fun loadHtml(html: String, baseUrl: String? = null)

    public fun reload()

    public fun stopLoading()

    public fun goBack()

    public fun goForward()

    public fun evaluateJavaScript(script: String, callback: (JavaScriptResult) -> Unit)

    /** Delivers a string message to page JavaScript via `window.webviewKmp.onmessage`. */
    public fun postMessage(data: String) {
        throw UnsupportedOperationException("This WebView backend does not support web messaging")
    }

    public fun close()
}

/** Suspend-friendly JavaScript evaluation. */
public suspend fun WebViewController.evaluateJavaScript(script: String): JavaScriptResult =
    suspendCancellableCoroutine { continuation ->
        evaluateJavaScript(script) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }

/** Waits until the current navigation is no longer loading. */
public suspend fun WebViewController.awaitLoaded(): WebViewState {
    state.takeIf { !it.isLoading && it.url != null }?.let { return it }
    return states.first { !it.isLoading && it.url != null }
}

/** Waits until the current URL satisfies [predicate]. */
public suspend fun WebViewController.awaitUrl(predicate: (String) -> Boolean): String {
    state.url?.takeIf(predicate)?.let { return it }
    return states
        .filter { current -> current.url?.let(predicate) == true }
        .first()
        .url
        ?: error("Matched WebView state did not contain a URL")
}

/** Waits for the next completed navigation event. */
public suspend fun WebViewController.awaitNavigation(): WebViewEvent.NavigationFinished =
    events.filter { it is WebViewEvent.NavigationFinished }.first() as WebViewEvent.NavigationFinished

private fun stateEvents(states: StateFlow<WebViewState>): Flow<WebViewEvent> =
    flow {
        var previous = states.value
        states.collect { current ->
            if (current.url != previous.url) emit(WebViewEvent.UrlChanged(current.url))
            if (!previous.isLoading && current.isLoading) {
                emit(WebViewEvent.NavigationStarted(current.url))
            }
            if (previous.isLoading && !current.isLoading) {
                if (current.error != null) {
                    emit(WebViewEvent.NavigationFailed(current.url, current.error))
                } else {
                    emit(WebViewEvent.NavigationFinished(current.url))
                }
            } else if (current.error != null && current.error != previous.error) {
                emit(WebViewEvent.NavigationFailed(current.url, current.error))
            }
            previous = current
        }
    }
