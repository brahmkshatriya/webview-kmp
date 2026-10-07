package dev.brahmkshatriya.webview

public enum class JavaScriptMode {
    Enabled,
    Disabled,
}

public sealed interface UserAgent {
    public data object Default : UserAgent

    public data class Custom(public val value: String) : UserAgent
}

public sealed interface WebViewProfile {
    public data object Default : WebViewProfile

    public data object Ephemeral : WebViewProfile

    public data class Persistent(public val name: String) : WebViewProfile {
        init {
            require(name.isNotBlank()) { "Persistent profile name must not be blank" }
        }
    }
}

public enum class WebViewUserScriptInjectionTime {
    DocumentStart,
    DocumentEnd,
}

public data class WebViewUserScript(
    public val source: String,
    public val injectionTime: WebViewUserScriptInjectionTime = WebViewUserScriptInjectionTime.DocumentEnd,
    public val mainFrameOnly: Boolean = true,
) {
    init {
        require(source.isNotBlank()) { "User script must not be blank" }
    }
}

public data class WebViewNavigationRequest(
    public val url: String,
    public val method: String = "GET",
    public val isMainFrame: Boolean = true,
    public val isRedirect: Boolean = false,
    public val hasUserGesture: Boolean = false,
)

public enum class WebViewNavigationDecision {
    Allow,
    Cancel,
    OpenExternally,
}

public fun interface WebViewNavigationHandler {
    public fun decide(request: WebViewNavigationRequest): WebViewNavigationDecision
}

public data class WebViewConfig(
    public val initialUrl: String? = null,
    public val javaScript: JavaScriptMode = JavaScriptMode.Enabled,
    public val userAgent: UserAgent = UserAgent.Default,
    public val mediaPlaybackRequiresUserGesture: Boolean = true,
    public val debugLogging: Boolean = false,
    public val profile: WebViewProfile = WebViewProfile.Default,
    public val navigationHandler: WebViewNavigationHandler? = null,
    public val userScripts: List<WebViewUserScript> = emptyList(),
)
