package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
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
