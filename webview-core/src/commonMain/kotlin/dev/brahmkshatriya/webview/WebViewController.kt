package dev.brahmkshatriya.webview

import kotlinx.coroutines.flow.StateFlow

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

public interface WebViewController {
    public val backendId: WebViewBackendId
    public val capabilities: WebViewCapabilities

    public val state: WebViewState

    public val states: StateFlow<WebViewState>

    public fun loadUrl(url: String, headers: Map<String, String> = emptyMap())

    public fun loadHtml(html: String, baseUrl: String? = null)

    public fun reload()

    public fun stopLoading()

    public fun goBack()

    public fun goForward()

    public fun evaluateJavaScript(script: String, callback: (JavaScriptResult) -> Unit = {})

    public fun close()
}
