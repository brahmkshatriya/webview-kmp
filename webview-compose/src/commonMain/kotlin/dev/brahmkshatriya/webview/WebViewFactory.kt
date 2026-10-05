package dev.brahmkshatriya.webview

/** Returns the WebView backend providers implemented by the current platform. */
public expect fun platformWebViewBackends(): List<WebViewBackendProvider>

/**
 * Creates a WebView using the first available backend in [order].
 *
 * Platforms may register additional providers alongside the system backend; callers can select
 * them explicitly through [order] without changing the controller API.
 */
public fun createWebViewController(
    order: List<WebViewBackendId> = listOf(WebViewBackendId.System),
    config: WebViewConfig = WebViewConfig(),
    requiredCapabilities: Set<WebViewCapability> = emptySet(),
): WebViewController =
    WebViewBackendSelector(platformWebViewBackends()).create(order, config, requiredCapabilities)
