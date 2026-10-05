package dev.brahmkshatriya.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Platform-neutral behavioral contract for a controllable page.
 *
 * Platform integration tests can adapt Android WebView, WKWebView, browser iframe, AppView CDP/BiDi,
 * or another backend to this interface and reuse the same navigation/JavaScript assertions.
 */
internal interface WebViewControllerTestHarness {
    suspend fun navigate(url: String, headers: Map<String, String> = emptyMap())

    suspend fun reload()

    suspend fun stopLoading()

    suspend fun goBack()

    suspend fun goForward()

    suspend fun evaluateJavaScript(script: String): JavaScriptResult

    suspend fun awaitState(predicate: (WebViewState) -> Boolean): WebViewState
}

/** Adapts the public controller API to the shared behavioral contract. */
internal class PublicWebViewControllerTestHarness(
    private val controller: WebViewController,
    private val timeoutMillis: Long = 10_000,
) : WebViewControllerTestHarness {
    override suspend fun navigate(url: String, headers: Map<String, String>): Unit =
        controller.loadUrl(url, headers)

    override suspend fun reload(): Unit = controller.reload()

    override suspend fun stopLoading(): Unit = controller.stopLoading()

    override suspend fun goBack(): Unit = controller.goBack()

    override suspend fun goForward(): Unit = controller.goForward()

    override suspend fun evaluateJavaScript(script: String): JavaScriptResult {
        val result = CompletableDeferred<JavaScriptResult>()
        controller.evaluateJavaScript(script) { value -> result.complete(value) }
        return withTimeout(timeoutMillis) { result.await() }
    }

    override suspend fun awaitState(predicate: (WebViewState) -> Boolean): WebViewState {
        controller.state.takeIf(predicate)?.let { return it }
        return withTimeout(timeoutMillis) { controller.states.first(predicate) }
    }
}

internal suspend fun verifyCustomRequestHeadersContract(
    harness: WebViewControllerTestHarness,
    headerEchoBaseUrl: String,
) {
    val withHeader = "$headerEchoBaseUrl/with-header"
    harness.navigate(withHeader, mapOf("X-WebView-Test" to "HEADER-OK"))
    harness.awaitState { it.url == withHeader && !it.isLoading }
    assertEquals(
        JavaScriptResult.Value("\"HEADER-OK\""),
        harness.evaluateJavaScript("document.body.textContent"),
    )

    val withoutHeader = "$headerEchoBaseUrl/without-header"
    harness.navigate(withoutHeader)
    harness.awaitState { it.url == withoutHeader && !it.isLoading }
    assertEquals(
        JavaScriptResult.Value("\"MISSING\""),
        harness.evaluateJavaScript("document.body.textContent"),
    )
}

internal suspend fun verifyWebViewControllerContract(harness: WebViewControllerTestHarness) {
    val first = "data:text/html,<title>First</title><script>window.answer=41</script>"
    val second = "data:text/html,<title>Second</title><script>window.answer=42</script>"

    harness.navigate(first)
    val firstState = harness.awaitState { it.title == "First" && !it.isLoading }
    assertEquals(first, firstState.url)
    assertEquals(JavaScriptResult.Value("41"), harness.evaluateJavaScript("window.answer"))

    harness.navigate(second)
    val secondState = harness.awaitState { it.title == "Second" && !it.isLoading }
    assertEquals(second, secondState.url)
    assertEquals(JavaScriptResult.Value("42"), harness.evaluateJavaScript("window.answer"))
    assertEquals(
        JavaScriptResult.Value("{\"answer\":42}"),
        harness.evaluateJavaScript("Promise.resolve({answer: window.answer})"),
    )
    assertTrue(harness.evaluateJavaScript("throw new Error('contract-boom')") is JavaScriptResult.Error)
    assertTrue(secondState.canGoBack)

    harness.goBack()
    val backState = harness.awaitState { it.title == "First" && !it.isLoading }
    assertEquals("First", backState.title)
    assertTrue(backState.canGoForward)

    harness.goForward()
    val forwardState = harness.awaitState { it.title == "Second" && !it.isLoading }
    assertEquals("Second", forwardState.title)

    harness.reload()
    val reloaded = harness.awaitState { it.title == "Second" && !it.isLoading }
    assertFalse(reloaded.isLoading)
    harness.stopLoading()
}
