@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.webview

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieExpires
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebsiteDataStore

internal class AppleCookieStore(
    private val store: WKHTTPCookieStore = WKWebsiteDataStore.defaultDataStore().httpCookieStore,
) : WebViewCookieStore {

    override suspend fun get(url: String): List<WebViewCookie> {
        val target = NSURL.URLWithString(url) ?: error("Invalid URL: $url")
        return allNativeCookies().filter { it.matches(target) }.map(NSHTTPCookie::toWebViewCookie)
    }

    override suspend fun set(url: String, cookie: WebViewCookie) {
        val target = NSURL.URLWithString(url) ?: error("Invalid URL: $url")
        val native = cookie.toNativeCookie(target)
        suspendCoroutine<Unit> { continuation ->
            store.setCookie(native) { continuation.resume(Unit) }
        }
    }

    override suspend fun delete(url: String, cookie: WebViewCookie) {
        val target = NSURL.URLWithString(url) ?: error("Invalid URL: $url")
        val native = allNativeCookies().firstOrNull { existing ->
            existing.name == cookie.name &&
                existing.matches(target) &&
                (cookie.domain == null || existing.domain == cookie.domain) &&
                (cookie.path == null || existing.path == cookie.path)
        } ?: return
        deleteNative(native)
    }

    override suspend fun clear() {
        allNativeCookies().forEach { deleteNative(it) }
    }

    private suspend fun allNativeCookies(): List<NSHTTPCookie> =
        suspendCoroutine { continuation ->
            store.getAllCookies { cookies ->
                continuation.resume(cookies.orEmpty().filterIsInstance<NSHTTPCookie>())
            }
        }

    private suspend fun deleteNative(cookie: NSHTTPCookie): Unit =
        suspendCoroutine { continuation ->
            store.deleteCookie(cookie) { continuation.resume(Unit) }
        }
}

private fun NSHTTPCookie.toWebViewCookie(): WebViewCookie =
    WebViewCookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expiresAtMillis = expiresDate?.let { (it.timeIntervalSince1970 * 1000.0).toLong() },
        secure = isSecure(),
        httpOnly = isHTTPOnly(),
    )

private fun NSHTTPCookie.matches(url: NSURL): Boolean {
    val host = url.host?.lowercase() ?: return false
    val normalizedDomain = domain.trimStart('.').lowercase()
    if (host != normalizedDomain && !host.endsWith(".$normalizedDomain")) return false
    if (isSecure() && url.scheme?.lowercase() != "https") return false
    val requestPath = url.path?.takeIf(String::isNotEmpty) ?: "/"
    return requestPath.startsWith(path.ifEmpty { "/" })
}

private fun WebViewCookie.toNativeCookie(url: NSURL): NSHTTPCookie {
    val properties = mutableMapOf<Any?, Any?>(
        NSHTTPCookieName to name,
        NSHTTPCookieValue to value,
        NSHTTPCookieDomain to (domain ?: url.host ?: error("Cookie URL has no host")),
        NSHTTPCookiePath to (path ?: "/"),
    )
    expiresAtMillis?.let {
        properties[NSHTTPCookieExpires] = NSDate.dateWithTimeIntervalSince1970(it / 1000.0)
    }
    if (secure == true) properties[NSHTTPCookieSecure] = "TRUE"
    if (httpOnly == true) properties["HttpOnly"] = "TRUE"
    sameSite?.let { properties["SameSite"] = it.name }
    return NSHTTPCookie.cookieWithProperties(properties) ?: error("Apple WebKit rejected cookie $name")
}
