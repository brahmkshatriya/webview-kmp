@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.webview

import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.posix.X_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.system
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserPageSessionIntegrationTest {
    @Test
    fun chromiumControlsNavigationStateAndJavaScript(): Unit = runBlocking {
        val executable = findTestExecutable(listOf("google-chrome-stable", "chromium")) ?: return@runBlocking
        withHeadlessBrowser(LinuxTestBrowser.Chromium, executable) { session ->
            verifyPageSession(session)
        }
    }

    @Test
    fun firefoxControlsNavigationStateAndJavaScript(): Unit = runBlocking {
        val executable = findTestExecutable(listOf("firefox", "firefox-developer-edition")) ?: return@runBlocking
        withHeadlessBrowser(LinuxTestBrowser.Firefox, executable) { session ->
            verifyPageSession(session)
        }
    }
}

private enum class LinuxTestBrowser {
    Chromium,
    Firefox,
}

private suspend fun verifyPageSession(session: BrowserPageSession) {
    val harness =
        object : WebViewControllerTestHarness {
            override suspend fun navigate(url: String, headers: Map<String, String>): Unit =
                session.navigate(url, headers)

            override suspend fun reload(): Unit = session.reload()

            override suspend fun stopLoading(): Unit = session.stopLoading()

            override suspend fun goBack(): Unit = session.goBack()

            override suspend fun goForward(): Unit = session.goForward()

            override suspend fun evaluateJavaScript(script: String): JavaScriptResult =
                session.evaluateJavaScript(script)

            override suspend fun awaitState(predicate: (WebViewState) -> Boolean): WebViewState {
                val snapshot = awaitPage(session) { page -> predicate(page.toWebViewState()) }
                return snapshot.toWebViewState()
            }
        }

    verifyWebViewControllerContract(harness)
    withHeaderEchoServer { baseUrl ->
        verifyCustomRequestHeadersContract(harness, baseUrl)
    }
    withCookieEchoServer { baseUrl ->
        verifyCookieStore(session, baseUrl)
    }
    verifyUserScripts(session)
}

private suspend fun verifyUserScripts(session: BrowserPageSession) {
    session.installUserScripts(
        listOf(
            WebViewUserScript(
                source = "window.webviewKmpStart = 'start-ok';",
                injectionTime = WebViewUserScriptInjectionTime.DocumentStart,
            ),
            WebViewUserScript(
                source = "document.body.dataset.webviewKmpEnd = 'end-ok';",
                injectionTime = WebViewUserScriptInjectionTime.DocumentEnd,
            ),
        ),
    )
    val url =
        "data:text/html,<title>Scripts</title><body>body<script>" +
            "document.body.dataset.webviewKmpStart=window.webviewKmpStart||'missing'</script>"
    session.navigate(url)
    awaitPage(session) { it.url == url && !it.isLoading }
    assertEquals(
        JavaScriptResult.Value("\"start-ok\""),
        session.evaluateJavaScript("document.body.dataset.webviewKmpStart"),
    )
    assertEquals(
        JavaScriptResult.Value("\"end-ok\""),
        session.evaluateJavaScript("document.body.dataset.webviewKmpEnd"),
    )
}

private suspend fun verifyCookieStore(session: BrowserPageSession, baseUrl: String) {
    val pageUrl = "$baseUrl/cookie"
    val cookie =
        WebViewCookie(
            name = "webview_kmp_session",
            value = "cookie-ok",
            path = "/",
            httpOnly = true,
            sameSite = WebViewCookieSameSite.Lax,
        )

    session.clearCookies()
    assertTrue(session.getCookies(pageUrl).isEmpty())

    session.setCookie(baseUrl, cookie)
    val stored = session.getCookies(pageUrl).single { it.name == cookie.name }
    assertEquals(cookie.value, stored.value)
    assertEquals(true, stored.httpOnly)

    session.navigate(pageUrl)
    awaitPage(session) { it.url == pageUrl && !it.isLoading }
    assertEquals(
        JavaScriptResult.Value("\"webview_kmp_session=cookie-ok\""),
        session.evaluateJavaScript("document.body.textContent"),
    )
    val documentCookie = session.evaluateJavaScript("document.cookie")
    assertTrue(documentCookie is JavaScriptResult.Value)
    assertFalse(documentCookie.json.orEmpty().contains(cookie.name))

    session.deleteCookie(baseUrl, stored)
    assertTrue(session.getCookies(pageUrl).none { it.name == cookie.name })

    session.setCookie(baseUrl, cookie.copy(name = "first"))
    session.setCookie(baseUrl, cookie.copy(name = "second"))
    assertTrue(session.getCookies(pageUrl).size >= 2)
    session.clearCookies()
    assertTrue(session.getCookies(pageUrl).isEmpty())
}

private fun BrowserPageSnapshot.toWebViewState(): WebViewState =
    WebViewState(
        url = url,
        title = title,
        isLoading = isLoading,
        progress = progress,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
    )

private suspend fun awaitPage(
    session: BrowserPageSession,
    predicate: (BrowserPageSnapshot) -> Boolean,
): BrowserPageSnapshot {
    var last = session.snapshot()
    repeat(100) {
        if (predicate(last)) return last
        delay(50)
        last = session.snapshot()
    }
    error("Timed out waiting for page state; last=$last")
}

private suspend fun withHeadlessBrowser(
    browser: LinuxTestBrowser,
    executable: String,
    block: suspend (BrowserPageSession) -> Unit,
) {
    val port = allocateLoopbackPort()
    val root = "/tmp/webview-kmp-page-test-${browser.name.lowercase()}-${getpid()}-$port"
    val profile = "$root/profile"
    val pidFile = "$root/pid"
    val logFile = "$root/browser.log"
    check(system("mkdir -p -- ${testShellQuote(profile)}") == 0)
    val command =
        when (browser) {
            LinuxTestBrowser.Chromium ->
                buildString {
                    append(testShellQuote(executable))
                    append(" --headless=new --no-sandbox --disable-gpu")
                    append(" --user-data-dir=${testShellQuote(profile)}")
                    append(" --remote-debugging-address=127.0.0.1")
                    append(" --remote-debugging-port=$port --remote-allow-origins=*")
                    append(" about:blank")
                }
            LinuxTestBrowser.Firefox ->
                buildString {
                    append("MOZ_ENABLE_WAYLAND=1 ")
                    append(testShellQuote(executable))
                    append(" --headless --no-remote --new-instance")
                    append(" --profile ${testShellQuote(profile)}")
                    append(" --remote-debugging-port $port about:blank")
                }
        }
    check(
        system("($command >${testShellQuote(logFile)} 2>&1 & echo $! >${testShellQuote(pidFile)})") == 0,
    ) { "Could not launch $browser test browser" }

    var session: BrowserPageSession? = null
    try {
        session =
            when (browser) {
                LinuxTestBrowser.Chromium -> connectChromiumPageSession(port)
                LinuxTestBrowser.Firefox -> connectFirefoxPageSession(port)
            }
        block(session)
    } finally {
        session?.close()
        system("kill $(cat ${testShellQuote(pidFile)}) 2>/dev/null || true")
        system("rm -rf -- ${testShellQuote(root)}")
    }
}

private fun findTestExecutable(names: List<String>): String? {
    val path = getenv("PATH")?.toKString().orEmpty()
    for (name in names) {
        for (directory in path.split(':')) {
            if (directory.isBlank()) continue
            val candidate = "$directory/$name"
            if (access(candidate, X_OK) == 0) return candidate
        }
    }
    return null
}

private fun testShellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
private suspend fun withHeaderEchoServer(block: suspend (String) -> Unit) {
    val python = requireNotNull(findTestExecutable(listOf("python3"))) {
        "python3 is required for the Linux custom-header integration test"
    }
    val port = allocateLoopbackPort()
    val root = "/tmp/webview-kmp-header-test-${getpid()}-$port"
    val script = "$root/server.py"
    val pidFile = "$root/pid"
    val logFile = "$root/server.log"
    check(system("mkdir -p -- ${testShellQuote(root)}") == 0)
    writeTestTextFile(
        script,
        """
from http.server import BaseHTTPRequestHandler, HTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        value = self.headers.get("X-WebView-Test", "MISSING")
        body = ("<title>Header Echo</title><body>" + value + "</body>").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

HTTPServer(("127.0.0.1", $port), Handler).serve_forever()
""".trimIndent(),
    )
    check(
        system(
            "(${testShellQuote(python)} ${testShellQuote(script)} >${testShellQuote(logFile)} 2>&1 & " +
                "echo $! >${testShellQuote(pidFile)})",
        ) == 0,
    ) { "Could not launch header echo server" }
    delay(150)
    try {
        block("http://127.0.0.1:$port")
    } finally {
        system("kill $(cat ${testShellQuote(pidFile)}) 2>/dev/null || true")
        system("rm -rf -- ${testShellQuote(root)}")
    }
}

private suspend fun withCookieEchoServer(block: suspend (String) -> Unit) {
    val python = requireNotNull(findTestExecutable(listOf("python3"))) {
        "python3 is required for the Linux cookie integration test"
    }
    val port = allocateLoopbackPort()
    val root = "/tmp/webview-kmp-cookie-test-${getpid()}-$port"
    val script = "$root/server.py"
    val pidFile = "$root/pid"
    val logFile = "$root/server.log"
    check(system("mkdir -p -- ${testShellQuote(root)}") == 0)
    writeTestTextFile(
        script,
        """
from http.server import BaseHTTPRequestHandler, HTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        value = self.headers.get("Cookie", "")
        body = ("<title>Cookie Echo</title><body>" + value + "</body>").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

HTTPServer(("127.0.0.1", $port), Handler).serve_forever()
""".trimIndent(),
    )
    check(
        system(
            "(${testShellQuote(python)} ${testShellQuote(script)} >${testShellQuote(logFile)} 2>&1 & " +
                "echo $! >${testShellQuote(pidFile)})",
        ) == 0,
    ) { "Could not launch cookie echo server" }
    delay(150)
    try {
        block("http://localhost:$port")
    } finally {
        system("kill $(cat ${testShellQuote(pidFile)}) 2>/dev/null || true")
        system("rm -rf -- ${testShellQuote(root)}")
    }
}

private fun writeTestTextFile(path: String, value: String) {
    val file = checkNotNull(fopen(path, "wb")) { "Could not open $path for writing" }
    try {
        check(fputs(value, file) >= 0) { "Could not write $path" }
    } finally {
        fclose(file)
    }
}
