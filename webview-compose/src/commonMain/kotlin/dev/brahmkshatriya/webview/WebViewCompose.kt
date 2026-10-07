package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier

/** Remembers and closes a WebView controller with the owning composition. */
@Composable
public expect fun rememberWebViewController(
    order: List<WebViewBackendId> = listOf(WebViewBackendId.System),
    config: WebViewConfig = WebViewConfig(),
    requiredCapabilities: Set<WebViewCapability> = emptySet(),
): WebViewController

/** Displays [controller] using the selected platform backend. */
@Composable
public expect fun WebView(
    controller: WebViewController,
    modifier: Modifier = Modifier,
): Unit

/**
 * Displays a WebView for [url] without requiring the caller to manage a controller explicitly.
 * Use [rememberWebViewController] when navigation or browser APIs are needed outside the composable.
 */
@Composable
public fun WebView(
    url: String,
    modifier: Modifier = Modifier,
    config: WebViewConfig = WebViewConfig(),
    order: List<WebViewBackendId> = listOf(WebViewBackendId.System),
    requiredCapabilities: Set<WebViewCapability> = emptySet(),
): Unit {
    val controller =
        rememberWebViewController(
            order = order,
            config = config.copy(initialUrl = url),
            requiredCapabilities = requiredCapabilities,
        )
    WebView(controller = controller, modifier = modifier)
}

/** Compose state adapter for [WebViewController.states]. */
@Composable
public fun WebViewController.collectState(): State<WebViewState> = states.collectAsState()
