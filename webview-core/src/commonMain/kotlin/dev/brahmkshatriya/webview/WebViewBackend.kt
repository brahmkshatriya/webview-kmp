package dev.brahmkshatriya.webview

public data class WebViewBackendId(public val value: String) {
    init {
        require(value.isNotBlank()) { "Backend id must not be blank" }
    }

    override fun toString(): String = value

    public companion object {
        public val System: WebViewBackendId = WebViewBackendId("system")
        public val Chromium: WebViewBackendId = WebViewBackendId("chromium")
        public val Firefox: WebViewBackendId = WebViewBackendId("firefox")
    }
}

public sealed interface WebViewBackendAvailability {
    public data object Available : WebViewBackendAvailability

    public data class Unavailable(public val reason: String) : WebViewBackendAvailability
}

public interface WebViewBackendProvider {
    public val id: WebViewBackendId
    public val displayName: String
    public val capabilities: WebViewCapabilities

    public fun availability(): WebViewBackendAvailability

    public fun create(config: WebViewConfig = WebViewConfig()): WebViewController
}

public data class WebViewBackendAttempt(
    public val id: WebViewBackendId,
    public val reason: String,
)

public class NoWebViewBackendException(
    public val attempts: List<WebViewBackendAttempt>,
) : IllegalStateException(
    buildString {
        append("No requested WebView backend is available")
        if (attempts.isNotEmpty()) {
            append(": ")
            append(attempts.joinToString { "${it.id} (${it.reason})" })
        }
    },
)

public class WebViewBackendSelector(
    providers: List<WebViewBackendProvider>,
) {
    private val providersById: Map<WebViewBackendId, WebViewBackendProvider> =
        providers.associateBy(WebViewBackendProvider::id)

    init {
        require(providersById.size == providers.size) { "Backend provider ids must be unique" }
    }

    public val providers: List<WebViewBackendProvider> = providers.toList()

    public fun create(
        order: List<WebViewBackendId> = providers.map(WebViewBackendProvider::id),
        config: WebViewConfig = WebViewConfig(),
        requiredCapabilities: Set<WebViewCapability> = emptySet(),
    ): WebViewController {
        val attempts = mutableListOf<WebViewBackendAttempt>()
        for (id in order.distinct()) {
            val provider = providersById[id]
            if (provider == null) {
                attempts += WebViewBackendAttempt(id, "provider is not registered")
                continue
            }
            when (val availability = provider.availability()) {
                WebViewBackendAvailability.Available -> {
                    val missing =
                        requiredCapabilities.filterNot { capability ->
                            capability in provider.capabilities
                        }
                    if (missing.isNotEmpty()) {
                        attempts +=
                            WebViewBackendAttempt(
                                id,
                                "missing capabilities: ${missing.joinToString()}",
                            )
                        continue
                    }
                    try {
                        return provider.create(config)
                    } catch (t: Throwable) {
                        attempts +=
                            WebViewBackendAttempt(
                                id,
                                t.message ?: t::class.simpleName ?: "creation failed",
                            )
                    }
                }

                is WebViewBackendAvailability.Unavailable -> {
                    attempts += WebViewBackendAttempt(id, availability.reason)
                }
            }
        }
        throw NoWebViewBackendException(attempts)
    }
}
