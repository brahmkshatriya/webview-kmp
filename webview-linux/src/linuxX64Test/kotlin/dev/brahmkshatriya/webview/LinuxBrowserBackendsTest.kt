package dev.brahmkshatriya.webview

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxBrowserBackendsTest {
    @Test
    fun registersSystemWebKitAndBrowserAlternatives() {
        val providers = platformBackendProviders()
        assertEquals(WebViewBackendId.System, providers.first().id)
        assertEquals("WPE WebKit", SystemWebViewBackend.displayName)

        val ids = providers.map(WebViewBackendProvider::id)
        assertContains(ids, WebViewBackendId.Chromium)
        assertContains(ids, WebViewBackendId.Firefox)
        assertContains(ids, WebViewBackendId.System)
        assertTrue(WebViewCapability.CustomRequestHeaders in ChromiumWebViewBackend.capabilities)
        assertTrue(WebViewCapability.CustomRequestHeaders in FirefoxWebViewBackend.capabilities)
        assertTrue(WebViewCapability.CustomRequestHeaders in SystemWebViewBackend.capabilities)
        assertTrue(WebViewCapability.Cookies in ChromiumWebViewBackend.capabilities)
        assertTrue(WebViewCapability.Cookies in FirefoxWebViewBackend.capabilities)
        assertTrue(WebViewCapability.Cookies in SystemWebViewBackend.capabilities)
    }
}
