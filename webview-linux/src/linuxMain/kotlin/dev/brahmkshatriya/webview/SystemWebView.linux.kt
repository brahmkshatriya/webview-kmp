@file:Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.brahmkshatriya.wayland.appview.WaylandAppView as EmbeddedWaylandAppView
import dev.brahmkshatriya.wayland.appview.WaylandAppViewController
import dev.brahmkshatriya.wayland.appview.WaylandAppViewStatus
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.X_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.system

internal enum class LinuxBrowserFamily {
    Chromium,
    Firefox,
}

internal data class LinuxBrowser(
    val family: LinuxBrowserFamily,
    val executable: String,
)

private val chromiumExecutableNames =
    listOf(
        "google-chrome-stable",
        "google-chrome",
        "chromium",
        "chromium-browser",
        "brave-browser",
        "brave-browser-stable",
        "microsoft-edge-stable",
        "microsoft-edge",
        "vivaldi-stable",
        "vivaldi",
        "ungoogled-chromium",
        "thorium-browser",
        "chromium-freeworld",
        "opera",
    )

private val firefoxExecutableNames =
    listOf(
        "firefox",
        "firefox-developer-edition",
        "firefox-nightly",
        "librewolf",
        "floorp",
        "waterfox",
        "zen-browser",
        "zen",
        "mullvad-browser",
        "icecat",
    )

/** A host-installed Chromium-family browser embedded through Wayland AppView. */
public object ChromiumWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.Chromium
    override val displayName: String = "Chromium browser (Wayland AppView)"
    override val capabilities: WebViewCapabilities =
        WebViewCapabilities.of(
            WebViewCapability.CustomRequestHeaders,
            WebViewCapability.CustomUserAgent,
            WebViewCapability.JavaScriptEvaluation,
            WebViewCapability.CrossOriginJavaScriptEvaluation,
            WebViewCapability.NavigationState,
            WebViewCapability.LoadingProgress,
            WebViewCapability.MediaPlaybackPolicy,
        )

    override fun availability(): WebViewBackendAvailability =
        browserAvailability(findBrowser(LinuxBrowserFamily.Chromium), "Chromium-family")

    override fun create(config: WebViewConfig): WebViewController =
        BrowserAppViewController(
            backendId = id,
            browser = requireNotNull(findBrowser(LinuxBrowserFamily.Chromium)) {
                "No supported Chromium-family browser executable was found on PATH"
            },
            config = config.also {
                require(it.javaScript == JavaScriptMode.Enabled) {
                    "JavaScript-disabled mode is not supported by the Chromium AppView backend"
                }
            },
        )
}

/** A host-installed Firefox-family browser embedded through Wayland AppView. */
public object FirefoxWebViewBackend : WebViewBackendProvider {
    override val id: WebViewBackendId = WebViewBackendId.Firefox
    override val displayName: String = "Firefox browser (Wayland AppView)"
    override val capabilities: WebViewCapabilities =
        WebViewCapabilities.of(
            WebViewCapability.CustomRequestHeaders,
            WebViewCapability.CustomUserAgent,
            WebViewCapability.JavaScriptEvaluation,
            WebViewCapability.CrossOriginJavaScriptEvaluation,
            WebViewCapability.NavigationState,
            WebViewCapability.LoadingProgress,
            WebViewCapability.JavaScriptControl,
            WebViewCapability.MediaPlaybackPolicy,
        )

    override fun availability(): WebViewBackendAvailability =
        browserAvailability(findBrowser(LinuxBrowserFamily.Firefox), "Firefox-family")

    override fun create(config: WebViewConfig): WebViewController =
        BrowserAppViewController(
            backendId = id,
            browser = requireNotNull(findBrowser(LinuxBrowserFamily.Firefox)) {
                "No supported Firefox-family browser executable was found on PATH"
            },
            config = config,
        )
}

internal class BrowserAppViewController(
    override val backendId: WebViewBackendId,
    private val browser: LinuxBrowser,
    private val config: WebViewConfig,
) : WebViewController {
    private val appView = WaylandAppViewController()
    private val profileDirectory = nextProfileDirectory(browser.family)
    private val remoteDebuggingPort = allocateLoopbackPort()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionReady = CompletableDeferred<BrowserPageSession>()
    private val mutableStates = MutableStateFlow(WebViewState())
    private var pageSession: BrowserPageSession? = null
    private var closed: Boolean = false

    override val capabilities: WebViewCapabilities =
        when (browser.family) {
            LinuxBrowserFamily.Chromium -> ChromiumWebViewBackend.capabilities
            LinuxBrowserFamily.Firefox -> FirefoxWebViewBackend.capabilities
        }
    override val states: StateFlow<WebViewState> = mutableStates
    override val state: WebViewState
        get() = mutableStates.value

    internal val embeddedController: WaylandAppViewController
        get() = appView

    init {
        check(system("mkdir -p -- ${shellQuote(profileDirectory)}") == 0) {
            "Could not create browser profile directory $profileDirectory"
        }
        configureProfile()
        val initialUrl = config.initialUrl ?: "about:blank"
        mutableStates.value = WebViewState(url = initialUrl, isLoading = true)
        if (!appView.launch(browserCommand(initialUrl))) {
            val failure = IllegalStateException(appView.error ?: "Failed to launch ${browser.executable}")
            mutableStates.value = mutableStates.value.copy(isLoading = false, error = failure.message)
            sessionReady.completeExceptionally(failure)
        } else {
            scope.launch {
                try {
                    val session =
                        when (browser.family) {
                            LinuxBrowserFamily.Chromium ->
                                connectChromiumPageSession(remoteDebuggingPort, initialUrl)
                            LinuxBrowserFamily.Firefox ->
                                connectFirefoxPageSession(remoteDebuggingPort, initialUrl)
                        }
                    session.initialize(initialUrl)
                    pageSession = session
                    sessionReady.complete(session)
                    refreshFromSession(session)
                    while (isActive) {
                        delay(100)
                        runCatching { refreshFromSession(session) }
                    }
                } catch (failure: Throwable) {
                    if (!sessionReady.isCompleted) sessionReady.completeExceptionally(failure)
                    if (!closed) {
                        mutableStates.value =
                            mutableStates.value.copy(
                                isLoading = false,
                                error = failure.message ?: "Browser page controller failed",
                            )
                    }
                }
            }
        }
    }

    override fun loadUrl(url: String, headers: Map<String, String>): Unit {
        checkOpen()
        require(url.isNotBlank()) { "URL must not be blank" }
        mutableStates.value = mutableStates.value.copy(url = url, isLoading = true, progress = 0f, error = null)
        submit { navigate(url, headers) }
    }

    override fun loadHtml(html: String, baseUrl: String?): Unit {
        checkOpen()
        val page = "$profileDirectory/webview-kmp-page.html"
        val base = baseUrl?.let { "<base href=\"${escapeHtmlAttribute(it)}\">" }.orEmpty()
        writeTextFile(page, base + html)
        loadUrl("file://$page")
    }

    override fun reload(): Unit {
        checkOpen()
        mutableStates.value = mutableStates.value.copy(isLoading = true, progress = 0f, error = null)
        submit { reload() }
    }

    override fun stopLoading(): Unit {
        checkOpen()
        submit { stopLoading() }
    }

    override fun goBack(): Unit {
        checkOpen()
        mutableStates.value = mutableStates.value.copy(isLoading = true, progress = 0f, error = null)
        submit { goBack() }
    }

    override fun goForward(): Unit {
        checkOpen()
        mutableStates.value = mutableStates.value.copy(isLoading = true, progress = 0f, error = null)
        submit { goForward() }
    }

    override fun evaluateJavaScript(
        script: String,
        callback: (JavaScriptResult) -> Unit,
    ): Unit {
        checkOpen()
        scope.launch {
            val result =
                try {
                    sessionReady.await().evaluateJavaScript(script)
                } catch (failure: Throwable) {
                    JavaScriptResult.Error(failure.message ?: "JavaScript evaluation failed")
                }
            callback(result)
        }
    }

    override fun close(): Unit {
        if (closed) return
        closed = true
        scope.cancel()
        pageSession?.close()
        pageSession = null
        appView.close()
        system("rm -rf -- ${shellQuote(profileDirectory)}")
    }

    internal fun updateStatus(status: WaylandAppViewStatus): Unit {
        if (closed) return
        val previous = mutableStates.value
        val next = if (pageSession == null) {
            previous.copy(
                title = status.title.takeIf(String::isNotBlank) ?: previous.title,
                isLoading = !status.isMapped && status.processId != null,
                progress = if (status.isMapped) 0.5f else 0f,
                error = status.error ?: previous.error,
            )
        } else {
            previous.copy(error = status.error ?: previous.error)
        }
        if (next != previous) mutableStates.value = next
    }

    private fun submit(operation: suspend BrowserPageSession.() -> Unit) {
        scope.launch {
            try {
                val session = sessionReady.await()
                session.operation()
                refreshFromSession(session)
            } catch (failure: Throwable) {
                if (!closed) {
                    mutableStates.value =
                        mutableStates.value.copy(
                            isLoading = false,
                            error = failure.message ?: "Browser page command failed",
                        )
                }
            }
        }
    }

    private suspend fun refreshFromSession(session: BrowserPageSession) {
        val snapshot = session.snapshot()
        val previous = mutableStates.value
        val next =
            previous.copy(
                url = snapshot.url ?: previous.url,
                title = snapshot.title ?: previous.title,
                isLoading = snapshot.isLoading,
                progress = snapshot.progress,
                canGoBack = snapshot.canGoBack,
                canGoForward = snapshot.canGoForward,
                error = null,
            )
        if (next != previous) mutableStates.value = next
    }

    private fun browserCommand(url: String): String =
        when (browser.family) {
            LinuxBrowserFamily.Chromium ->
                buildList {
                    add(shellQuote(browser.executable))
                    add("--ozone-platform=wayland")
                    add("--no-first-run")
                    add("--no-default-browser-check")
                    add("--disable-session-crashed-bubble")
                    add("--disable-features=UsePortalFilePicker")
                    add("--user-data-dir=${shellQuote(profileDirectory)}")
                    add("--remote-debugging-address=127.0.0.1")
                    add("--remote-debugging-port=$remoteDebuggingPort")
                    add("--remote-allow-origins=*")
                    if (!config.mediaPlaybackRequiresUserGesture) {
                        add("--autoplay-policy=no-user-gesture-required")
                    }
                    if (config.debugLogging) {
                        add("--enable-logging=stderr")
                        add("--v=1")
                    }
                    when (val userAgent = config.userAgent) {
                        UserAgent.Default -> Unit
                        is UserAgent.Custom -> add("--user-agent=${shellQuote(userAgent.value)}")
                    }
                    add("--app=${shellQuote(url)}")
                }.joinToString(" ")

            LinuxBrowserFamily.Firefox ->
                buildList {
                    add("MOZ_ENABLE_WAYLAND=1")
                    add(shellQuote(browser.executable))
                    add("--no-remote")
                    add("--new-instance")
                    add("--profile")
                    add(shellQuote(profileDirectory))
                    add("--remote-debugging-port")
                    add(remoteDebuggingPort.toString())
                    add(shellQuote(url))
                }.joinToString(" ")
        }

    private fun configureProfile(): Unit {
        if (browser.family != LinuxBrowserFamily.Firefox) return

        val prefs =
            buildString {
                appendLine("user_pref(\"browser.shell.checkDefaultBrowser\", false);")
                appendLine("user_pref(\"browser.sessionstore.resume_from_crash\", false);")
                appendLine("user_pref(\"toolkit.legacyUserProfileCustomizations.stylesheets\", true);")
                appendLine("user_pref(\"javascript.enabled\", ${config.javaScript == JavaScriptMode.Enabled});")
                if (!config.mediaPlaybackRequiresUserGesture) {
                    appendLine("user_pref(\"media.autoplay.default\", 0);")
                    appendLine("user_pref(\"media.autoplay.blocking_policy\", 0);")
                }
                val userAgent = config.userAgent
                if (userAgent is UserAgent.Custom) {
                    appendLine(
                        "user_pref(\"general.useragent.override\", \"${escapeJavaScriptString(userAgent.value)}\");",
                    )
                }
            }
        writeTextFile("$profileDirectory/user.js", prefs)
        check(system("mkdir -p -- ${shellQuote("$profileDirectory/chrome")}") == 0) {
            "Could not create Firefox chrome profile directory"
        }
        writeTextFile(
            "$profileDirectory/chrome/userChrome.css",
            """
                #navigator-toolbox,
                #titlebar,
                #TabsToolbar {
                    visibility: collapse !important;
                    min-height: 0 !important;
                    max-height: 0 !important;
                }
            """.trimIndent(),
        )
    }

    private fun checkOpen(): Unit = check(!closed) { "WebViewController is closed" }
}

private fun browserAvailability(
    browser: LinuxBrowser?,
    family: String,
): WebViewBackendAvailability =
    if (browser != null) {
        WebViewBackendAvailability.Available
    } else {
        WebViewBackendAvailability.Unavailable("No supported $family browser executable was found on PATH")
    }

private fun findBrowser(family: LinuxBrowserFamily): LinuxBrowser? {
    val override =
        when (family) {
            LinuxBrowserFamily.Chromium -> getenv("WEBVIEW_KMP_CHROMIUM_EXECUTABLE")?.toKString()
            LinuxBrowserFamily.Firefox -> getenv("WEBVIEW_KMP_FIREFOX_EXECUTABLE")?.toKString()
        }
    if (!override.isNullOrBlank()) {
        val executable = findExecutable(override)
        if (executable != null) return LinuxBrowser(family, executable)
    }

    val names =
        when (family) {
            LinuxBrowserFamily.Chromium -> chromiumExecutableNames
            LinuxBrowserFamily.Firefox -> firefoxExecutableNames
        }
    for (name in names) {
        val executable = findExecutable(name) ?: continue
        return LinuxBrowser(family, executable)
    }
    return null
}

private fun findExecutable(name: String): String? {
    if ('/' in name) return name.takeIf { access(it, X_OK) == 0 }
    val path = getenv("PATH")?.toKString().orEmpty()
    for (directory in path.split(':')) {
        if (directory.isBlank()) continue
        val candidate = "$directory/$name"
        if (access(candidate, X_OK) == 0) return candidate
    }
    return null
}

private var browserProfileSerial: Int = 0

private fun nextProfileDirectory(family: LinuxBrowserFamily): String {
    val serial = browserProfileSerial++
    return "/tmp/webview-kmp-${family.name.lowercase()}-${getpid()}-$serial"
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

private fun writeTextFile(path: String, value: String): Unit {
    val file = checkNotNull(fopen(path, "wb")) { "Could not open $path for writing" }
    try {
        check(fputs(value, file) >= 0) { "Could not write $path" }
    } finally {
        fclose(file)
    }
}

private fun escapeHtmlAttribute(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private fun escapeJavaScriptString(value: String): String =
    buildString(value.length) {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '\"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

@Composable
internal fun BrowserPlatformWebView(
    controller: BrowserAppViewController,
    modifier: Modifier,
): Unit {
    EmbeddedWaylandAppView(
        controller = controller.embeddedController,
        modifier = modifier,
        onFullscreenRequest = {},
        onStatusChange = controller::updateStatus,
    )
}
