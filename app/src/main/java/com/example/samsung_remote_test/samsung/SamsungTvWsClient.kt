package com.example.samsung_remote_test.samsung

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SamsungConnectionState {
    data object Disconnected : SamsungConnectionState
    data object Connecting : SamsungConnectionState
    data class Connected(val host: String, val port: Int) : SamsungConnectionState
    data class Unauthorized(val detail: String?) : SamsungConnectionState
    data class Failed(val detail: String) : SamsungConnectionState
}

data class SamsungAppInfo(
    val appId: String,
    val name: String?,
    val raw: JsonObject
)

class SamsungTvWsClient(
    private val okHttp: OkHttpClient = samsungUnsafeOkHttp()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<SamsungConnectionState>(SamsungConnectionState.Disconnected)
    val state: StateFlow<SamsungConnectionState> = _state.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private var webSocket: WebSocket? = null
    private var host: String? = null
    private var port: Int = 8002
    private var clientName: String = "SamsungTvRemote"
    private var pendingAppList: CompletableDeferred<List<SamsungAppInfo>?>? = null
    private val firstTextSent = AtomicBoolean(false)
    private val handshakeDone = AtomicBoolean(false)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val ignoreAtStartup = setOf(
        "ed.edenTV.update",
        "ms.voiceApp.hide",
        "ms.channel.clientConnect",
        "ms.channel.clientDisconnect",
        "ms.channel.ready"
    )

    fun connect(host: String, port: Int, name: String, token: String?) {
        close()
        this.host = host
        this.port = port
        this.clientName = name
        _token.value = token?.trim()?.ifBlank { null }
        _state.value = SamsungConnectionState.Connecting
        handshakeDone.set(false)
        firstTextSent.set(false)

        val url = buildWsUrl(host, port, name, _token.value)
        val req = Request.Builder().url(url).build()

        webSocket = okHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {}

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                runCatching { bytes.utf8() }.getOrNull()?.let { handleFrame(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_state.value !is SamsungConnectionState.Unauthorized) {
                    _state.value = SamsungConnectionState.Disconnected
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = response?.message ?: (t.message ?: "WebSocket failure")
                if (_state.value !is SamsungConnectionState.Unauthorized) {
                    _state.value = SamsungConnectionState.Failed(msg)
                }
            }
        })

        val waitMs = if (_token.value.isNullOrBlank()) 45000L else 8000L
        scope.launch {
            delay(waitMs)
            if (!handshakeDone.get() && _state.value == SamsungConnectionState.Connecting) {
                _state.value = SamsungConnectionState.Failed("Handshake timeout")
                close()
            }
        }
    }

    fun close() {
        pendingAppList?.complete(null)
        pendingAppList = null
        webSocket?.close(1000, "bye")
        webSocket = null
        handshakeDone.set(false)
        firstTextSent.set(false)
        if (_state.value !is SamsungConnectionState.Unauthorized) {
            _state.value = SamsungConnectionState.Disconnected
        }
    }

    fun isAlive(): Boolean = webSocket != null && _state.value is SamsungConnectionState.Connected

    fun sendKey(key: String, cmd: String = "Click", times: Int = 1) {
        val ws = webSocket ?: return
        repeat(times.coerceAtLeast(1)) {
            val payload = buildJson(
                method = "ms.remote.control",
                params = mapOf(
                    "Cmd" to JsonPrimitive(cmd),
                    "DataOfCmd" to JsonPrimitive(key),
                    "Option" to JsonPrimitive("false"),
                    "TypeOfRemote" to JsonPrimitive("SendRemoteKey")
                )
            )
            ws.send(payload)
        }
    }

    fun holdKey(key: String, seconds: Double) {
        scope.launch {
            sendKey(key, cmd = "Press", times = 1)
            delay((seconds * 1000.0).toLong().coerceAtLeast(0))
            sendKey(key, cmd = "Release", times = 1)
        }
    }

    fun moveCursor(x: Int, y: Int, durationMs: Int = 0) {
        val ws = webSocket ?: return
        val payload = buildJson(
            method = "ms.remote.control",
            params = mapOf(
                "Cmd" to JsonPrimitive("Move"),
                "Position" to JsonObject(
                    mapOf(
                        "x" to JsonPrimitive(x),
                        "y" to JsonPrimitive(y),
                        "Time" to JsonPrimitive(durationMs.toString())
                    )
                ),
                "TypeOfRemote" to JsonPrimitive("ProcessMouseDevice")
            )
        )
        ws.send(payload)
    }

    fun sendText(text: String, end: Boolean = false) {
        val ws = webSocket ?: return
        if (text.isBlank()) return

        if (firstTextSent.compareAndSet(false, true)) {
            val b = buildJson(
                method = "ms.channel.emit",
                params = mapOf(
                    "event" to JsonPrimitive("custom.remote.textReceived"),
                    "to" to JsonPrimitive("broadcast")
                )
            )
            ws.send(b)
        }

        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val payload = buildJson(
            method = "ms.remote.control",
            params = mapOf(
                "Cmd" to JsonPrimitive(encoded),
                "DataOfCmd" to JsonPrimitive("base64"),
                "TypeOfRemote" to JsonPrimitive("SendInputString")
            )
        )
        ws.send(payload)

        if (end) endText()
    }

    fun endText() {
        val ws = webSocket ?: return
        val payload = buildJson(
            method = "ms.remote.control",
            params = mapOf(
                "TypeOfRemote" to JsonPrimitive("SendInputEnd")
            )
        )
        ws.send(payload)
        firstTextSent.set(false)
    }

    fun runApp(appId: String, appType: String = "DEEP_LINK", metaTag: String = "") {
        val ws = webSocket ?: return
        val payload = buildJson(
            method = "ms.channel.emit",
            params = mapOf(
                "event" to JsonPrimitive("ed.apps.launch"),
                "to" to JsonPrimitive("host"),
                "data" to JsonObject(
                    mapOf(
                        "action_type" to JsonPrimitive(appType),
                        "appId" to JsonPrimitive(appId),
                        "metaTag" to JsonPrimitive(metaTag)
                    )
                )
            )
        )
        ws.send(payload)
    }

    fun openBrowser(url: String) {
        runApp("org.tizen.browser", "NATIVE_LAUNCH", url)
    }

    suspend fun appList(timeoutMs: Long = 4000): List<SamsungAppInfo>? {
        val ws = webSocket ?: return null
        val def = CompletableDeferred<List<SamsungAppInfo>?>()
        pendingAppList?.complete(null)
        pendingAppList = def

        val payload = buildJson(
            method = "ms.channel.emit",
            params = mapOf(
                "event" to JsonPrimitive("ed.installedApp.get"),
                "to" to JsonPrimitive("host")
            )
        )
        ws.send(payload)

        return try {
            withTimeout(timeoutMs) { def.await() }
        } catch (_: TimeoutCancellationException) {
            pendingAppList = null
            null
        }
    }

    private fun handleFrame(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: "*"

        if (!handshakeDone.get()) {
            if (event in ignoreAtStartup) return

            if (event == "ms.channel.unauthorized") {
                handshakeDone.set(true)
                _state.value = SamsungConnectionState.Unauthorized(obj.toString())
                close()
                return
            }

            if (event == "ms.channel.connect") {
                handshakeDone.set(true)
                val data = obj["data"]?.jsonObject
                val tok = data?.get("token")?.jsonPrimitive?.contentOrNull
                if (!tok.isNullOrBlank()) _token.value = tok
                _state.value = SamsungConnectionState.Connected(host.orEmpty(), port)
                return
            }

            return
        }

        if (event == "ms.remote.imeStart" || event == "ms.remote.imeEnd") {
            firstTextSent.set(false)
        }

        if (event == "ed.installedApp.get") {
            val apps = parseInstalledApps(obj)
            pendingAppList?.complete(apps)
            pendingAppList = null
        }
    }

    private fun parseInstalledApps(frame: JsonObject): List<SamsungAppInfo>? {
        val data = frame["data"]?.jsonObject ?: return null
        val arr = data["data"]
        val list = (arr as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val appId = o["appId"]?.jsonPrimitive?.contentOrNull
                ?: o["id"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull
            SamsungAppInfo(appId = appId, name = name, raw = o)
        } ?: return null
        return list
    }

    private fun buildWsUrl(host: String, port: Int, name: String, token: String?): String {
        val encNameRaw = Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val encName = URLEncoder.encode(encNameRaw, "UTF-8")
        val scheme = if (port == 8002) "wss" else "ws"
        val base = "$scheme://$host:$port/api/v2/channels/samsung.remote.control?name=$encName"
        val t = token?.trim()?.ifBlank { null }
        return if (!t.isNullOrBlank()) {
            base + "&token=" + URLEncoder.encode(t, "UTF-8")
        } else {
            base
        }
    }

    private fun buildJson(method: String, params: Map<String, JsonElement>): String {
        val obj = JsonObject(
            mapOf(
                "method" to JsonPrimitive(method),
                "params" to JsonObject(params)
            )
        )
        return obj.toString()
    }
}