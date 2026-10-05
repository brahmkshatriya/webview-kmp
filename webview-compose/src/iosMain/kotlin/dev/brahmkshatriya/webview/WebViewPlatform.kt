package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

public actual fun platformWebViewBackends(): List<WebViewBackendProvider> = platformBackendProviders()

@Composable
public actual fun rememberWebViewController(
    order: List<WebViewBackendId>,
    config: WebViewConfig,
    requiredCapabilities: Set<WebViewCapability>,
): WebViewController {
    val controller =
        remember(order, config, requiredCapabilities) {
            createWebViewController(order, config, requiredCapabilities)
        }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}

@Composable
public actual fun WebView(
    controller: WebViewController,
    modifier: Modifier,
): Unit = PlatformWebView(controller, modifier)
