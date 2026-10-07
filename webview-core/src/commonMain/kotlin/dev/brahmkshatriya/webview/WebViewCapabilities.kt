package dev.brahmkshatriya.webview

/** Optional behavior that varies between WebView engines and host platforms. */
public enum class WebViewCapability {
    CustomRequestHeaders,
    CustomUserAgent,
    JavaScriptEvaluation,
    CrossOriginJavaScriptEvaluation,
    NavigationState,
    LoadingProgress,
    JavaScriptControl,
    MediaPlaybackPolicy,
    /** Native browser-cookie access, including cookies unavailable to page JavaScript. */
    Cookies,
    /** Ability to allow/cancel top-level and subresource navigation before it commits. */
    NavigationInterception,
    /** Automatic page-script injection configured through [WebViewConfig.userScripts]. */
    UserScripts,
    /** Isolated or named WebView profiles beyond the platform default profile. */
    Profiles,
    /** Bidirectional page/app messaging through the webview-kmp bridge. */
    WebMessaging,
}

/** Immutable feature set reported by a backend or controller. */
public class WebViewCapabilities private constructor(
    supported: Set<WebViewCapability>,
) {
    public val supported: Set<WebViewCapability> = supported.toSet()

    public operator fun contains(capability: WebViewCapability): Boolean = capability in supported

    public fun supportsAll(capabilities: Collection<WebViewCapability>): Boolean =
        supported.containsAll(capabilities)

    public companion object {
        public val None: WebViewCapabilities = WebViewCapabilities(emptySet())

        public fun of(vararg capabilities: WebViewCapability): WebViewCapabilities =
            WebViewCapabilities(capabilities.toSet())
    }
}
