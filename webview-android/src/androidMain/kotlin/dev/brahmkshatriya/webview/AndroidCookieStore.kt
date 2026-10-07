package dev.brahmkshatriya.webview

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ValueCallback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class AndroidCookieStore(
    private val managerProvider: () -> CookieManager? = { CookieManager.getInstance() },
) : WebViewCookieStore {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun manager(): CookieManager =
        requireNotNull(managerProvider()) {
            "The Android WebView profile is not attached yet; render the WebView before accessing its cookies"
        }

    override suspend fun get(url: String): List<WebViewCookie> = onMain {
        manager().getCookie(url)
            ?.split(';')
            ?.mapNotNull { raw ->
                val part = raw.trim()
                if (part.isEmpty()) return@mapNotNull null
                val separator = part.indexOf('=')
                if (separator < 0) WebViewCookie(name = part, value = "")
                else WebViewCookie(
                    name = part.substring(0, separator).trim(),
                    value = part.substring(separator + 1).trim(),
                )
            }
            .orEmpty()
    }

    override suspend fun set(url: String, cookie: WebViewCookie): Unit =
        setRaw(url, cookie.toSetCookieValue())

    override suspend fun delete(url: String, cookie: WebViewCookie): Unit =
        setRaw(
            url,
            cookie.copy(value = "", expiresAtMillis = 0L).toSetCookieValue(maxAgeSeconds = 0),
        )

    override suspend fun clear(): Unit = suspendCoroutine { continuation ->
        runOnMain {
            val manager = manager()
            manager.removeAllCookies {
                manager.flush()
                continuation.resume(Unit)
            }
        }
    }

    private suspend fun setRaw(url: String, value: String): Unit = suspendCoroutine { continuation ->
        runOnMain {
            val manager = manager()
            manager.setCookie(url, value, ValueCallback<Boolean> {
                manager.flush()
                continuation.resume(Unit)
            })
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = suspendCoroutine { continuation ->
        runOnMain { continuation.resume(block()) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

private fun WebViewCookie.toSetCookieValue(maxAgeSeconds: Long? = null): String = buildString {
    append(name)
    append('=')
    append(value)
    domain?.takeIf(String::isNotBlank)?.let { append("; Domain=").append(it) }
    append("; Path=").append(path?.takeIf(String::isNotBlank) ?: "/")
    expiresAtMillis?.let { append("; Expires=").append(httpDate(it)) }
    maxAgeSeconds?.let { append("; Max-Age=").append(it) }
    if (secure == true) append("; Secure")
    if (httpOnly == true) append("; HttpOnly")
    sameSite?.let { append("; SameSite=").append(it.name) }
}

private fun httpDate(timestampMillis: Long): String =
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }.format(Date(timestampMillis))
