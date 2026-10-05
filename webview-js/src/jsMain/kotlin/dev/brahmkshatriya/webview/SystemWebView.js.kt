package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.HTMLIFrameElement

/** Browser iframe backend. The current browser itself is the system web runtime. */
public object SystemWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.System
    override val displayName: String = "Browser iframe"
    override val capabilities: WebViewCapabilities =
        WebViewCapabilities.of(
            WebViewCapability.JavaScriptEvaluation,
            WebViewCapability.JavaScriptControl,
        )

    override fun availability(): WebViewBackendAvailability = WebViewBackendAvailability.Available

    override fun create(config: WebViewConfig): WebViewController {
        require(config.userAgent == UserAgent.Default) {
            "A browser iframe cannot override the browser user agent"
        }
        return SystemWebViewController(config)
    }
}

public fun platformBackendProviders(): List<WebViewBackendProvider> = listOf(SystemWebViewBackend)

/** JavaScript browser controller backed by an HTML iframe. */
public class SystemWebViewController public constructor(
    private val config: WebViewConfig = WebViewConfig(),
) : WebViewController {
    private val mutableStates = MutableStateFlow(WebViewState())
    private val pendingCommands = mutableListOf<(HTMLIFrameElement) -> Unit>()
    private var iframe: HTMLIFrameElement? = null
    private var closed: Boolean = false

    override val backendId: WebViewBackendId = WebViewBackendId.System
    override val capabilities: WebViewCapabilities = SystemWebViewBackend.capabilities
    override val states: StateFlow<WebViewState> = mutableStates
    override val state: WebViewState
        get() = mutableStates.value

    init {
        config.initialUrl?.takeIf(String::isNotBlank)?.let { url ->
            pendingCommands += { it.src = url }
        }
    }

    override fun loadUrl(url: String, headers: Map<String, String>): Unit {
        require(url.isNotBlank()) { "URL must not be blank" }
        require(headers.isEmpty()) { "Custom request headers are not supported by browser iframes" }
        withFrame {
            mutableStates.value = state.copy(url = url, isLoading = true, progress = 0f, error = null)
            it.src = url
        }
    }

    override fun loadHtml(html: String, baseUrl: String?): Unit {
        withFrame { frame ->
            mutableStates.value = state.copy(isLoading = true, progress = 0f, error = null)
            frame.srcdoc =
                if (baseUrl.isNullOrBlank()) html
                else "<base href=\"${escapeAttribute(baseUrl)}\">$html"
        }
    }

    override fun reload(): Unit = withFrame { it.contentWindow?.location?.reload() }

    override fun stopLoading(): Unit = withFrame { it.contentWindow?.stop() }

    override fun goBack(): Unit = withFrame { it.contentWindow?.history?.back() }

    override fun goForward(): Unit = withFrame { it.contentWindow?.history?.forward() }

    override fun evaluateJavaScript(
        script: String,
        callback: (JavaScriptResult) -> Unit,
    ): Unit {
        withFrame { frame ->
            try {
                val result = frame.contentWindow?.asDynamic()?.eval(script)
                val json = kotlin.js.JSON.stringify(result)
                callback(JavaScriptResult.Value(json))
            } catch (failure: Throwable) {
                callback(
                    JavaScriptResult.Error(
                        failure.message
                            ?: "JavaScript evaluation failed; cross-origin frames cannot be scripted",
                    ),
                )
            }
        }
    }

    override fun close(): Unit {
        if (closed) return
        closed = true
        pendingCommands.clear()
        iframe?.onload = null
        iframe = null
    }

    internal fun attach(): HTMLIFrameElement {
        check(!closed) { "WebViewController is closed" }
        iframe?.let { return it }
        val frame = document.createElement("iframe") as HTMLIFrameElement
        iframe = frame
        frame.style.border = "0"
        frame.style.width = "100%"
        frame.style.height = "100%"
        if (config.javaScript == JavaScriptMode.Disabled) {
            frame.setAttribute("sandbox", "allow-forms allow-popups allow-same-origin")
        }
        frame.onload = {
            refreshState(frame)
            null
        }
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { it(frame) }
        return frame
    }

    internal fun detach(frame: HTMLIFrameElement): Unit {
        if (iframe !== frame) return
        frame.onload = null
        iframe = null
    }

    private fun withFrame(command: (HTMLIFrameElement) -> Unit): Unit {
        check(!closed) { "WebViewController is closed" }
        val frame = iframe
        if (frame == null) pendingCommands += command else command(frame)
    }

    private fun refreshState(frame: HTMLIFrameElement): Unit {
        val url =
            try {
                frame.contentWindow?.location?.href ?: frame.src
            } catch (_: Throwable) {
                frame.src
            }
        val title =
            try {
                frame.contentDocument?.title
            } catch (_: Throwable) {
                null
            }
        mutableStates.value =
            WebViewState(
                url = url,
                title = title,
                isLoading = false,
                progress = 1f,
                canGoBack = false,
                canGoForward = false,
                error = null,
            )
    }
}

private fun escapeAttribute(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;")

@Composable
public fun PlatformWebView(
    controller: WebViewController,
    modifier: Modifier,
): Unit {
    val systemController =
        controller as? SystemWebViewController
            ?: error("The selected backend cannot be rendered as an iframe")
    HtmlElementView(
        factory = systemController::attach,
        modifier = modifier,
        onRelease = systemController::detach,
    )
}
