package dev.brahmkshatriya.webview

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class LinuxBrowserBackendsTest {
    @Test
    fun registersAppViewBrowserBackends() {
        val ids = platformBackendProviders().map(WebViewBackendProvider::id)
        assertContains(ids, WebViewBackendId.Chromium)
        assertContains(ids, WebViewBackendId.Firefox)
        assertContains(ids, WebViewBackendId.System)
        assertTrue(WebViewCapability.CustomRequestHeaders in ChromiumWebViewBackend.capabilities)
        assertTrue(WebViewCapability.CustomRequestHeaders in FirefoxWebViewBackend.capabilities)
        assertTrue(WebViewCapability.CustomRequestHeaders in SystemWebViewBackend.capabilities)
    }
}
