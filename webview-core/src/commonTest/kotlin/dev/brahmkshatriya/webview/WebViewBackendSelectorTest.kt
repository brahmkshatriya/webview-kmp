package dev.brahmkshatriya.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

public class WebViewBackendSelectorTest {
    @Test
    public fun usesFirstAvailableBackendInRequestedOrder(): Unit {
        val unavailable = FakeProvider("first", available = false)
        val available = FakeProvider("second", available = true)
        val selector = WebViewBackendSelector(listOf(unavailable, available))

        val controller =
            selector.create(
                order = listOf(WebViewBackendId("first"), WebViewBackendId("second")),
            )

        assertEquals(WebViewBackendId("second"), controller.backendId)
    }

    @Test
    public fun recordsUnknownAndUnavailableProvidersWhenSelectionFails(): Unit {
        val selector = WebViewBackendSelector(listOf(FakeProvider("system", available = false)))

        val error =
            assertFailsWith<NoWebViewBackendException> {
                selector.create(
                    listOf(WebViewBackendId("unknown"), WebViewBackendId("system")),
                )
            }

        assertEquals(
            listOf(WebViewBackendId("unknown"), WebViewBackendId("system")),
            error.attempts.map { it.id },
        )
    }

    @Test
    public fun skipsBackendMissingRequiredCapability(): Unit {
        val first = FakeProvider("first", available = true)
        val second =
            FakeProvider(
                "second",
                available = true,
                capabilities = WebViewCapabilities.of(WebViewCapability.CustomUserAgent),
            )
        val selector = WebViewBackendSelector(listOf(first, second))

        val controller =
            selector.create(
                requiredCapabilities = setOf(WebViewCapability.CustomUserAgent),
            )

        assertEquals(WebViewBackendId("second"), controller.backendId)
    }
}

private class FakeProvider(
    id: String,
    private val available: Boolean,
    override val capabilities: WebViewCapabilities = WebViewCapabilities.None,
) : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId(id)
    override val displayName: String = id

    override fun availability(): WebViewBackendAvailability =
        if (available) {
            WebViewBackendAvailability.Available
        } else {
            WebViewBackendAvailability.Unavailable("not installed")
        }

    override fun create(config: WebViewConfig): WebViewController = FakeController(id)
}

private class FakeController(override val backendId: WebViewBackendId) : WebViewController {
    override val capabilities: WebViewCapabilities = WebViewCapabilities.None
    override val state: WebViewState = WebViewState()
    override val states: StateFlow<WebViewState> = MutableStateFlow(state)

    override fun loadUrl(url: String, headers: Map<String, String>): Unit = Unit
    override fun loadHtml(html: String, baseUrl: String?): Unit = Unit
    override fun reload(): Unit = Unit
    override fun stopLoading(): Unit = Unit
    override fun goBack(): Unit = Unit
    override fun goForward(): Unit = Unit
    override fun evaluateJavaScript(script: String, callback: (JavaScriptResult) -> Unit): Unit = Unit
    override fun close(): Unit = Unit
}
