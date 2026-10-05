package dev.brahmkshatriya.webview

public enum class JavaScriptMode {
    Enabled,
    Disabled,
}

public sealed interface UserAgent {
    public data object Default : UserAgent

    public data class Custom(public val value: String) : UserAgent
}

public data class WebViewConfig(
    public val initialUrl: String? = null,
    public val javaScript: JavaScriptMode = JavaScriptMode.Enabled,
    public val userAgent: UserAgent = UserAgent.Default,
    public val mediaPlaybackRequiresUserGesture: Boolean = true,
    public val debugLogging: Boolean = false,
)
