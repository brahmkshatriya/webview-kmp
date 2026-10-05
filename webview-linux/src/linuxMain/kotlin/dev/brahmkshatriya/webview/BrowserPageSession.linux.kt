@file:Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package dev.brahmkshatriya.webview

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.AF_INET
import platform.posix.IPPROTO_TCP
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.htons
import platform.posix.getenv
import platform.posix.rand
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

internal data class BrowserPageSnapshot(
    val url: String?,
    val title: String?,
    val isLoading: Boolean,
    val progress: Float,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
)

internal interface BrowserPageSession {
    suspend fun initialize(url: String) {
        if (snapshot().url != url) navigate(url)
    }

    suspend fun navigate(url: String, headers: Map<String, String> = emptyMap())

    suspend fun reload()

    suspend fun stopLoading()

    suspend fun goBack()

    suspend fun goForward()

    suspend fun evaluateJavaScript(script: String): JavaScriptResult

    suspend fun snapshot(): BrowserPageSnapshot

    fun close()
}

internal fun allocateLoopbackPort(): Int {
    val descriptor = socket(AF_INET, SOCK_STREAM, 0)
    check(descriptor >= 0) { "Could not allocate TCP socket" }
    try {
        return memScoped {
            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.convert()
            address.sin_port = htons(0u)
            // 127.0.0.1 stored in network byte order on the supported little-endian Linux targets.
            address.sin_addr.s_addr = 0x0100007fu
            check(
                bind(
                    descriptor,
                    address.ptr.reinterpret<sockaddr>(),
                    sizeOf<sockaddr_in>().convert(),
                ) == 0,
            ) { "Could not bind loopback socket" }

            val length = alloc<socklen_tVar>()
            length.value = sizeOf<sockaddr_in>().convert()
            check(
                getsockname(
                    descriptor,
                    address.ptr.reinterpret<sockaddr>(),
                    length.ptr,
                ) == 0,
            ) { "Could not read loopback socket address" }
            networkToHostShort(address.sin_port).toInt()
        }
    } finally {
        close(descriptor)
    }
}

internal suspend fun connectChromiumPageSession(
    port: Int,
    expectedUrl: String? = null,
): BrowserPageSession {
    var lastFailure: Throwable? = null
    repeat(160) {
        try {
            val targets = browserJson.parseToJsonElement(httpGet(port, "/json/list")).jsonArray
            val pages =
                targets
                    .asSequence()
                    .map(JsonElement::jsonObject)
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "page" }
                    .toList()
            val page =
                pages.firstOrNull { it["url"]?.jsonPrimitive?.contentOrNull == expectedUrl }
                    ?: pages.firstOrNull()
            val webSocketUrl = page?.get("webSocketDebuggerUrl")?.jsonPrimitive?.contentOrNull
            if (webSocketUrl != null) {
                val path = webSocketUrl.substringAfter("127.0.0.1:$port", missingDelimiterValue = "")
                check(path.startsWith('/')) { "Unexpected Chrome debugger WebSocket URL: $webSocketUrl" }
                val rpc = WebSocketRpc.connect(port, path)
                rpc.call("Page.enable")
                rpc.call("Runtime.enable")
                return ChromiumPageSession(rpc)
            }
        } catch (failure: Throwable) {
            lastFailure = failure
        }
        delay(50)
    }
    error("Chrome DevTools endpoint did not become ready: ${lastFailure?.message ?: "unknown error"}")
}

internal suspend fun connectFirefoxPageSession(
    port: Int,
    expectedUrl: String? = null,
): BrowserPageSession {
    var lastFailure: Throwable? = null
    repeat(160) {
        try {
            val rpc = WebSocketRpc.connect(port, "/session")
            rpc.call(
                "session.new",
                buildJsonObject {
                    put("capabilities", buildJsonObject { put("alwaysMatch", buildJsonObject {}) })
                },
            )
            val tree = rpc.call("browsingContext.getTree")
            val contexts =
                tree["result"]
                    ?.jsonObject
                    ?.get("contexts")
                    ?.jsonArray
                    ?.map(JsonElement::jsonObject)
                    .orEmpty()
            val selected =
                contexts.firstOrNull { it["url"]?.jsonPrimitive?.contentOrNull == expectedUrl }
                    ?: contexts.firstOrNull { it["url"]?.jsonPrimitive?.contentOrNull != "about:blank" }
                    ?: contexts.firstOrNull()
            val context = selected
                    ?.get("context")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: error("Firefox BiDi did not expose a top-level browsing context")
            rpc.call(
                "session.subscribe",
                buildJsonObject {
                    put("events", JsonArray(listOf(JsonPrimitive("network.beforeRequestSent"))))
                    put("contexts", JsonArray(listOf(JsonPrimitive(context))))
                },
            )
            return FirefoxPageSession(rpc, context)
        } catch (failure: Throwable) {
            lastFailure = failure
        }
        delay(50)
    }
    error("Firefox WebDriver BiDi endpoint did not become ready: ${lastFailure?.message ?: "unknown error"}")
}

private class ChromiumPageSession(
    private val rpc: WebSocketRpc,
) : BrowserPageSession {
    override suspend fun navigate(url: String, headers: Map<String, String>): Unit {
        if (headers.isEmpty()) {
            rpc.call("Page.navigate", buildJsonObject { put("url", url) })
            return
        }

        validateRequestHeaders(headers)
        rpc.call(
            "Fetch.enable",
            buildJsonObject {
                put(
                    "patterns",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("urlPattern", url)
                                put("resourceType", "Document")
                                put("requestStage", "Request")
                            },
                        ),
                    ),
                )
            },
        )
        try {
            rpc.call(
                "Page.navigate",
                buildJsonObject { put("url", url) },
            ) { event, responder ->
                if (event["method"]?.jsonPrimitive?.contentOrNull != "Fetch.requestPaused") return@call
                val params = event["params"]?.jsonObject ?: return@call
                val request = params["request"]?.jsonObject ?: return@call
                if (request["url"]?.jsonPrimitive?.contentOrNull != url) return@call
                val requestId = params["requestId"]?.jsonPrimitive?.contentOrNull ?: return@call
                val merged = mergeChromiumHeaders(request["headers"]?.jsonObject, headers)
                responder.send(
                    "Fetch.continueRequest",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("headers", merged)
                    },
                )
            }
        } finally {
            runCatching { rpc.call("Fetch.disable") }
        }
    }

    override suspend fun reload(): Unit {
        rpc.call("Page.reload")
    }

    override suspend fun stopLoading(): Unit {
        rpc.call("Page.stopLoading")
    }

    override suspend fun goBack(): Unit = navigateHistory(-1)

    override suspend fun goForward(): Unit = navigateHistory(1)

    override suspend fun evaluateJavaScript(script: String): JavaScriptResult {
        val response =
            rpc.call(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", script)
                    put("awaitPromise", true)
                    put("returnByValue", true)
                },
            )
        val result = response["result"]?.jsonObject ?: return JavaScriptResult.Error("Missing CDP result")
        result["exceptionDetails"]?.let { details ->
            return JavaScriptResult.Error(chromiumExceptionMessage(details.jsonObject))
        }
        val remote = result["result"]?.jsonObject ?: return JavaScriptResult.Error("Missing CDP remote value")
        if (remote["type"]?.jsonPrimitive?.contentOrNull == "undefined") {
            return JavaScriptResult.Value(null)
        }
        return JavaScriptResult.Value(remote["value"]?.toString())
    }

    override suspend fun snapshot(): BrowserPageSnapshot {
        val payload =
            evaluateString(
                "JSON.stringify({url:location.href,title:document.title,readyState:document.readyState})",
            )
        val state = browserJson.parseToJsonElement(payload).jsonObject
        val history = rpc.call("Page.getNavigationHistory")["result"]?.jsonObject
        val index = history?.get("currentIndex")?.jsonPrimitive?.intOrNull ?: 0
        val entries = history?.get("entries")?.jsonArray ?: JsonArray(emptyList())
        val loading = state["readyState"]?.jsonPrimitive?.contentOrNull != "complete"
        return BrowserPageSnapshot(
            url = state["url"]?.jsonPrimitive?.contentOrNull,
            title = state["title"]?.jsonPrimitive?.contentOrNull,
            isLoading = loading,
            progress = if (loading) 0.5f else 1f,
            canGoBack = index > 0,
            canGoForward = index >= 0 && index < entries.lastIndex,
        )
    }

    override fun close(): Unit = rpc.close()

    private suspend fun navigateHistory(delta: Int) {
        val history = rpc.call("Page.getNavigationHistory")["result"]?.jsonObject ?: return
        val index = history["currentIndex"]?.jsonPrimitive?.intOrNull ?: return
        val entries = history["entries"]?.jsonArray ?: return
        val target = entries.getOrNull(index + delta)?.jsonObject ?: return
        val entryId = target["id"]?.jsonPrimitive?.intOrNull ?: return
        rpc.call("Page.navigateToHistoryEntry", buildJsonObject { put("entryId", entryId) })
    }

    private suspend fun evaluateString(expression: String): String {
        val response =
            rpc.call(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("awaitPromise", true)
                    put("returnByValue", true)
                },
            )
        val result = response["result"]?.jsonObject ?: error("Missing CDP result")
        result["exceptionDetails"]?.let { error(chromiumExceptionMessage(it.jsonObject)) }
        return result["result"]
            ?.jsonObject
            ?.get("value")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: error("CDP expression did not return a string")
    }
}

private class FirefoxPageSession(
    private val rpc: WebSocketRpc,
    private val context: String,
) : BrowserPageSession {
    private var historyIndex: Int = 0
    private var maxHistoryIndex: Int = 0

    override suspend fun initialize(url: String) {
        val current = snapshot().url
        if (current != url) {
            rpc.call(
                "browsingContext.navigate",
                buildJsonObject {
                    put("context", context)
                    put("url", url)
                    put("wait", "complete")
                },
            )
        }
        // Firefox's remote agent always exposes an internal about:blank entry before the
        // requested page. Keep that implementation detail outside the public WebView history.
        historyIndex = 0
        maxHistoryIndex = 0
    }

    override suspend fun navigate(url: String, headers: Map<String, String>): Unit {
        if (headers.isEmpty()) {
            rpc.call(
                "browsingContext.navigate",
                buildJsonObject {
                    put("context", context)
                    put("url", url)
                    put("wait", "none")
                },
            )
        } else {
            validateRequestHeaders(headers)
            val intercept =
                rpc.call(
                    "network.addIntercept",
                    buildJsonObject {
                        put("phases", JsonArray(listOf(JsonPrimitive("beforeRequestSent"))))
                        put("contexts", JsonArray(listOf(JsonPrimitive(context))))
                        put(
                            "urlPatterns",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "string")
                                        put("pattern", url)
                                    },
                                ),
                            ),
                        )
                    },
                )["result"]?.jsonObject?.get("intercept")?.jsonPrimitive?.contentOrNull
                    ?: error("Firefox BiDi did not return an intercept id")
            try {
                rpc.call(
                    "browsingContext.navigate",
                    buildJsonObject {
                        put("context", context)
                        put("url", url)
                        put("wait", "none")
                    },
                ) { event, responder ->
                    if (event["method"]?.jsonPrimitive?.contentOrNull != "network.beforeRequestSent") return@call
                    val params = event["params"]?.jsonObject ?: return@call
                    if (params["isBlocked"]?.jsonPrimitive?.contentOrNull != "true") return@call
                    val request = params["request"]?.jsonObject ?: return@call
                    if (request["url"]?.jsonPrimitive?.contentOrNull != url) return@call
                    val requestId = request["request"]?.jsonPrimitive?.contentOrNull ?: return@call
                    val merged = mergeFirefoxHeaders(request["headers"]?.jsonArray, headers)
                    responder.send(
                        "network.continueRequest",
                        buildJsonObject {
                            put("request", requestId)
                            put("headers", merged)
                        },
                    )
                }
            } finally {
                runCatching {
                    rpc.call(
                        "network.removeIntercept",
                        buildJsonObject { put("intercept", intercept) },
                    )
                }
            }
        }
        historyIndex += 1
        maxHistoryIndex = historyIndex
    }

    override suspend fun reload(): Unit {
        rpc.call(
            "browsingContext.reload",
            buildJsonObject {
                put("context", context)
                put("wait", "none")
            },
        )
    }

    override suspend fun stopLoading(): Unit {
        evaluateExpression("window.stop(); null")
    }

    override suspend fun goBack(): Unit {
        if (historyIndex <= 0) return
        rpc.call(
            "browsingContext.traverseHistory",
            buildJsonObject {
                put("context", context)
                put("delta", -1)
            },
        )
        historyIndex -= 1
    }

    override suspend fun goForward(): Unit {
        if (historyIndex >= maxHistoryIndex) return
        rpc.call(
            "browsingContext.traverseHistory",
            buildJsonObject {
                put("context", context)
                put("delta", 1)
            },
        )
        historyIndex += 1
    }

    override suspend fun evaluateJavaScript(script: String): JavaScriptResult {
        val quoted = JsonPrimitive(script).toString()
        val response =
            evaluateExpression(
                "(async()=>{const __v=await (0,eval)($quoted);return JSON.stringify(__v===undefined?null:__v)})()",
            )
        if (response["type"]?.jsonPrimitive?.contentOrNull == "exception") {
            val text =
                response["exceptionDetails"]
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: "JavaScript evaluation failed"
            return JavaScriptResult.Error(text)
        }
        val value = response["result"]?.jsonObject
        if (value?.get("type")?.jsonPrimitive?.contentOrNull != "string") {
            return JavaScriptResult.Error("Firefox BiDi did not return a JSON string result")
        }
        return JavaScriptResult.Value(value["value"]?.jsonPrimitive?.contentOrNull)
    }

    override suspend fun snapshot(): BrowserPageSnapshot {
        val result =
            evaluateExpression(
                "JSON.stringify({url:location.href,title:document.title,readyState:document.readyState})",
            )
        val value =
            result["result"]
                ?.jsonObject
                ?.get("value")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: error("Firefox BiDi snapshot did not return a string")
        val state = browserJson.parseToJsonElement(value).jsonObject
        val loading = state["readyState"]?.jsonPrimitive?.contentOrNull != "complete"
        return BrowserPageSnapshot(
            url = state["url"]?.jsonPrimitive?.contentOrNull,
            title = state["title"]?.jsonPrimitive?.contentOrNull,
            isLoading = loading,
            progress = if (loading) 0.5f else 1f,
            canGoBack = historyIndex > 0,
            canGoForward = historyIndex < maxHistoryIndex,
        )
    }

    override fun close(): Unit = rpc.close()

    private suspend fun evaluateExpression(expression: String): JsonObject =
        rpc.call(
            "script.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("target", buildJsonObject { put("context", context) })
                put("awaitPromise", true)
            },
        )["result"]?.jsonObject ?: error("Missing Firefox BiDi result")
}

private fun chromiumExceptionMessage(details: JsonObject): String =
    details["exception"]
        ?.jsonObject
        ?.get("description")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: details["text"]?.jsonPrimitive?.contentOrNull
        ?: "JavaScript evaluation failed"

private fun validateRequestHeaders(headers: Map<String, String>) {
    headers.forEach { (name, value) ->
        require(name.isNotBlank()) { "HTTP header name must not be blank" }
        require(name.none { it == ':' || it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7f }) {
            "Invalid HTTP header name: $name"
        }
        require(value.none { it == '\r' || it == '\n' }) {
            "HTTP header values must not contain CR or LF"
        }
    }
}

private fun mergeChromiumHeaders(
    existing: JsonObject?,
    additions: Map<String, String>,
): JsonArray {
    val replacedNames = additions.keys.mapTo(mutableSetOf()) { it.lowercase() }
    val result = mutableListOf<JsonElement>()
    existing.orEmpty().forEach { (name, value) ->
        if (name.lowercase() !in replacedNames) {
            result +=
                buildJsonObject {
                    put("name", name)
                    put("value", value.jsonPrimitive.contentOrNull ?: value.toString())
                }
        }
    }
    additions.forEach { (name, value) ->
        result +=
            buildJsonObject {
                put("name", name)
                put("value", value)
            }
    }
    return JsonArray(result)
}

private fun mergeFirefoxHeaders(
    existing: JsonArray?,
    additions: Map<String, String>,
): JsonArray {
    val replacedNames = additions.keys.mapTo(mutableSetOf()) { it.lowercase() }
    val result = mutableListOf<JsonElement>()
    existing.orEmpty().forEach { header ->
        val name = header.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        if (name == null || name.lowercase() !in replacedNames) result += header
    }
    additions.forEach { (name, value) ->
        result +=
            buildJsonObject {
                put("name", name)
                put(
                    "value",
                    buildJsonObject {
                        put("type", "string")
                        put("value", value)
                    },
                )
            }
    }
    return JsonArray(result)
}

private val browserJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val browserProtocolTrace: Boolean = getenv("WEBVIEW_KMP_DEBUG_PROTOCOL") != null

private fun traceBrowserProtocol(message: String) {
    if (browserProtocolTrace) println("WEBVIEW_PROTOCOL $message")
}

private class WebSocketRpc private constructor(
    private val webSocket: SimpleWebSocket,
) {
    private val mutex = Mutex()
    private var nextId: Int = 1

    suspend fun call(
        method: String,
        params: JsonObject = buildJsonObject {},
        onEvent: ((JsonObject, EventResponder) -> Unit)? = null,
    ): JsonObject =
        mutex.withLock {
            val id = nextId++
            send(id, method, params)
            traceBrowserProtocol("-> #$id $method")
            val responder = EventResponder(this)
            while (true) {
                val raw = webSocket.readText()
                traceBrowserProtocol("<- $raw")
                val response = browserJson.parseToJsonElement(raw).jsonObject
                if (response["id"]?.jsonPrimitive?.intOrNull != id) {
                    if (response["method"] != null) onEvent?.invoke(response, responder)
                    continue
                }
                if (response["type"]?.jsonPrimitive?.contentOrNull == "error") {
                    val message = response["message"]?.jsonPrimitive?.contentOrNull
                        ?: response["error"]?.jsonPrimitive?.contentOrNull
                        ?: "$method failed"
                    error(message)
                }
                response["error"]?.let { error(it.toString()) }
                return@withLock response
            }
            error("unreachable")
        }

    private fun send(id: Int, method: String, params: JsonObject) {
        webSocket.sendText(
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString(),
        )
    }

    class EventResponder internal constructor(
        private val rpc: WebSocketRpc,
    ) {
        fun send(method: String, params: JsonObject = buildJsonObject {}) {
            val id = rpc.nextId++
            rpc.send(id, method, params)
            traceBrowserProtocol("-> #$id $method (event response)")
        }
    }

    fun close(): Unit = webSocket.close()

    companion object {
        fun connect(port: Int, path: String): WebSocketRpc =
            WebSocketRpc(
                SimpleWebSocket.connect(port, path).also {
                    traceBrowserProtocol("WS connected $port$path")
                },
            )
    }
}

private class SimpleWebSocket private constructor(
    private val socket: BufferedTcpSocket,
) {
    fun sendText(text: String): Unit = sendFrame(0x1, text.encodeToByteArray())

    fun readText(): String {
        val fragments = mutableListOf<ByteArray>()
        var totalSize = 0
        var receivingText = false
        while (true) {
            val first = socket.readExact(2)
            val finalFrame = first[0].toInt() and 0x80 != 0
            val opcode = first[0].toInt() and 0x0f
            val masked = first[1].toInt() and 0x80 != 0
            var length = (first[1].toInt() and 0x7f).toLong()
            if (length == 126L) {
                val bytes = socket.readExact(2)
                length = ((bytes[0].toInt() and 0xff) shl 8 or (bytes[1].toInt() and 0xff)).toLong()
            } else if (length == 127L) {
                val bytes = socket.readExact(8)
                length = 0
                for (byte in bytes) length = (length shl 8) or (byte.toInt() and 0xff).toLong()
            }
            check(length <= 16 * 1024 * 1024) { "WebSocket frame is too large: $length bytes" }
            val mask = if (masked) socket.readExact(4) else null
            val payload = socket.readExact(length.toInt())
            if (mask != null) {
                for (index in payload.indices) {
                    payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }

            when (opcode) {
                0x0 -> check(receivingText) { "Unexpected WebSocket continuation frame" }
                0x1 -> receivingText = true
                0x8 -> error("Browser debugger WebSocket closed")
                0x9 -> {
                    sendFrame(0xA, payload)
                    continue
                }
                0xA -> continue
                else -> continue
            }
            fragments += payload
            totalSize += payload.size
            if (finalFrame) {
                val combined = ByteArray(totalSize)
                var offset = 0
                for (fragment in fragments) {
                    fragment.copyInto(combined, offset)
                    offset += fragment.size
                }
                return combined.decodeToString()
            }
        }
    }

    fun close() {
        runCatching { sendFrame(0x8, ByteArray(0)) }
        socket.close()
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val lengthBytes =
            when {
                payload.size < 126 -> 0
                payload.size <= 0xffff -> 2
                else -> 8
            }
        val header = ByteArray(2 + lengthBytes + 4)
        header[0] = (0x80 or opcode).toByte()
        when (lengthBytes) {
            0 -> header[1] = (0x80 or payload.size).toByte()
            2 -> {
                header[1] = (0x80 or 126).toByte()
                header[2] = (payload.size ushr 8).toByte()
                header[3] = payload.size.toByte()
            }
            else -> {
                header[1] = (0x80 or 127).toByte()
                val size = payload.size.toLong()
                for (index in 0 until 8) {
                    header[2 + index] = (size ushr (56 - index * 8)).toByte()
                }
            }
        }
        val maskOffset = 2 + lengthBytes
        for (index in 0 until 4) header[maskOffset + index] = (rand() and 0xff).toByte()
        val maskedPayload = payload.copyOf()
        for (index in maskedPayload.indices) {
            maskedPayload[index] =
                (maskedPayload[index].toInt() xor header[maskOffset + index % 4].toInt()).toByte()
        }
        socket.write(header)
        socket.write(maskedPayload)
    }

    companion object {
        fun connect(port: Int, path: String): SimpleWebSocket {
            val socket = BufferedTcpSocket(connectTcp(port))
            socket.write(
                buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("Upgrade: WebSocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }.encodeToByteArray(),
            )
            val header = socket.readUntil("\r\n\r\n".encodeToByteArray()).decodeToString()
            check(header.startsWith("HTTP/1.1 101") || header.startsWith("HTTP/1.0 101")) {
                "WebSocket upgrade failed: ${header.lineSequence().firstOrNull().orEmpty()}"
            }
            return SimpleWebSocket(socket)
        }
    }
}

private class BufferedTcpSocket(
    private val descriptor: Int,
) {
    private var buffered: ByteArray = ByteArray(0)

    fun write(bytes: ByteArray) {
        var offset = 0
        bytes.usePinned { pinned ->
            while (offset < bytes.size) {
                val written = send(
                    descriptor,
                    pinned.addressOf(offset),
                    (bytes.size - offset).convert(),
                    0,
                ).toInt()
                check(written > 0) { "TCP write failed" }
                offset += written
            }
        }
    }

    fun readExact(size: Int): ByteArray {
        while (buffered.size < size) fill()
        val result = buffered.copyOfRange(0, size)
        buffered = buffered.copyOfRange(size, buffered.size)
        return result
    }

    fun readUntil(delimiter: ByteArray): ByteArray {
        while (true) {
            val index = buffered.indexOfSubsequence(delimiter)
            if (index >= 0) {
                val end = index + delimiter.size
                val result = buffered.copyOfRange(0, end)
                buffered = buffered.copyOfRange(end, buffered.size)
                return result
            }
            fill()
        }
    }

    fun readToEnd(): ByteArray {
        val chunks = mutableListOf(buffered)
        var total = buffered.size
        buffered = ByteArray(0)
        while (true) {
            val next = receiveChunk() ?: break
            chunks += next
            total += next.size
        }
        val result = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    fun close(): Unit {
        close(descriptor)
    }

    private fun fill() {
        val next = receiveChunk() ?: error("TCP connection closed")
        buffered += next
    }

    private fun receiveChunk(): ByteArray? {
        val bytes = ByteArray(8192)
        val received = bytes.usePinned { pinned ->
            recv(descriptor, pinned.addressOf(0), bytes.size.convert(), 0).toInt()
        }
        check(received >= 0) { "TCP read failed" }
        if (received == 0) return null
        return bytes.copyOf(received)
    }
}

private fun connectTcp(port: Int): Int = memScoped {
    val hints = cValue<addrinfo> {
        ai_family = AF_INET
        ai_socktype = SOCK_STREAM
        ai_protocol = IPPROTO_TCP
    }
    val result = alloc<CPointerVar<addrinfo>>()
    check(getaddrinfo("127.0.0.1", port.toString(), hints, result.ptr) == 0) {
        "Could not resolve localhost"
    }
    try {
        var current = result.value
        while (current != null) {
            val info = current.pointed
            val descriptor = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
            if (descriptor >= 0) {
                if (connect(descriptor, info.ai_addr, info.ai_addrlen) == 0) return@memScoped descriptor
                close(descriptor)
            }
            current = info.ai_next
        }
        error("Could not connect to browser debugger on 127.0.0.1:$port")
    } finally {
        freeaddrinfo(result.value)
    }
}

private fun httpGet(port: Int, path: String): String {
    val socket = BufferedTcpSocket(connectTcp(port))
    return try {
        socket.write(
            buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.encodeToByteArray(),
        )
        val header = socket.readUntil("\r\n\r\n".encodeToByteArray()).decodeToString()
        check(header.startsWith("HTTP/1.1 200") || header.startsWith("HTTP/1.0 200")) {
            "HTTP request failed: ${header.lineSequence().firstOrNull().orEmpty()}"
        }
        val contentLength =
            header.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: error("HTTP response did not provide Content-Length")
        socket.readExact(contentLength).decodeToString()
    } finally {
        socket.close()
    }
}

private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    outer@ for (start in 0..size - needle.size) {
        for (index in needle.indices) {
            if (this[start + index] != needle[index]) continue@outer
        }
        return start
    }
    return -1
}

private fun networkToHostShort(value: UShort): UShort =
    ((value.toInt() and 0xff) shl 8 or ((value.toInt() ushr 8) and 0xff)).toUShort()
