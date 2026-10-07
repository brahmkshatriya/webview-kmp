package dev.brahmkshatriya.webview

/** SameSite policy reported by a browser cookie store. */
public enum class WebViewCookieSameSite {
    Strict,
    Lax,
    None,
}

/**
 * A cookie stored by the browser engine.
 *
 * Some engines cannot report every attribute for an existing cookie. Such attributes are nullable
 * rather than being guessed by webview-kmp.
 */
public data class WebViewCookie(
    public val name: String,
    public val value: String,
    public val domain: String? = null,
    public val path: String? = null,
    public val expiresAtMillis: Long? = null,
    public val secure: Boolean? = null,
    public val httpOnly: Boolean? = null,
    public val sameSite: WebViewCookieSameSite? = null,
)

/** Browser cookie store associated with a [WebViewController]. */
public interface WebViewCookieStore {
    /** Returns cookies that would be considered for [url]. */
    public suspend fun get(url: String): List<WebViewCookie>

    /** Adds or replaces [cookie] for [url]. */
    public suspend fun set(url: String, cookie: WebViewCookie)

    /** Deletes the matching cookie. Supplying domain/path makes deletion unambiguous. */
    public suspend fun delete(url: String, cookie: WebViewCookie)

    /** Removes every cookie in this WebView profile/session. */
    public suspend fun clear()
}

/** Cookie access for a backend which does not expose its browser cookie jar. */
public object UnsupportedWebViewCookieStore : WebViewCookieStore {
    override suspend fun get(url: String): List<WebViewCookie> = unsupported()

    override suspend fun set(url: String, cookie: WebViewCookie): Unit = unsupported()

    override suspend fun delete(url: String, cookie: WebViewCookie): Unit = unsupported()

    override suspend fun clear(): Unit = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("This WebView backend does not expose cookie access")
}
