package com.example.samsung_remote_test.samsung

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicLong

private const val TAG_WS = "SamsungWs"

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

    private val sendSeq = AtomicLong(0)
    private val lastLaunch = MutableStateFlow<String?>(null)

    private data class ConnCfg(val host: String, val port: Int, val name: String, val token: String?)

    private var lastCfg: ConnCfg? = null
    private var wantConnected: Boolean = false
    private var reconnectJob: Job? = null
    private var handshakeTimeoutJob: Job? = null

    fun connect(host: String, port: Int, name: String, token: String?) {
        wantConnected = true
        lastCfg = ConnCfg(host, port, name, token?.trim()?.ifBlank { null })

        reconnectJob?.cancel()
        reconnectJob = null

        closeInternal()

        this.host = host
        this.port = port
        this.clientName = name
        _token.value = token?.trim()?.ifBlank { null }
        _state.value = SamsungConnectionState.Connecting
        handshakeDone.set(false)
        firstTextSent.set(false)

        val url = buildWsUrl(host, port, name, _token.value)
        val req = Request.Builder().url(url).build()
        Log.d(TAG_WS, "CONNECT url=$url")

        webSocket = okHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG_WS, "WS onOpen code=${response.code} msg=${response.message}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG_WS, "RECV $text")
                handleFrame(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                runCatching { bytes.utf8() }.getOrNull()?.let {
                    Log.d(TAG_WS, "RECV(bin) $it")
                    handleFrame(it)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG_WS, "WS onClosing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG_WS, "WS onClosed code=$code reason=$reason")
                if (_state.value !is SamsungConnectionState.Unauthorized) {
                    _state.value = SamsungConnectionState.Disconnected
                    scheduleReconnect("closed code=$code reason=$reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = response?.message ?: (t.message ?: "WebSocket failure")
                Log.e(TAG_WS, "WS onFailure msg=$msg", t)
                if (_state.value !is SamsungConnectionState.Unauthorized) {
                    _state.value = SamsungConnectionState.Disconnected
                    scheduleReconnect("failure: $msg")
                }
            }
        })

        val waitMs = if (_token.value.isNullOrBlank()) 45000L else 15000L
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(waitMs)
            if (!handshakeDone.get() && _state.value == SamsungConnectionState.Connecting) {
                Log.e(TAG_WS, "Handshake timeout host=$host port=$port tokenEmpty=${_token.value.isNullOrBlank()}")
                _state.value = SamsungConnectionState.Disconnected
                closeInternal()
                scheduleReconnect("handshake-timeout")
            }
        }
    }

    fun close() {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
        closeInternal()
    }

    private fun closeInternal() {
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

    private fun scheduleReconnect(reason: String) {
        if (!wantConnected) return
        if (reconnectJob?.isActive == true) return
        val cfg = lastCfg ?: return

        reconnectJob = scope.launch {
            var d = 1000L
            repeat(12) { attempt ->
                if (!wantConnected) return@launch
                Log.e(TAG_WS, "RECONNECT attempt=${attempt + 1} reason=$reason delayMs=$d")
                delay(d)
                val tok = _token.value ?: cfg.token
                connect(cfg.host, cfg.port, cfg.name, tok)
                delay(1800)
                if (_state.value is SamsungConnectionState.Connected) return@launch
                d = (d * 2).coerceAtMost(30000L)
            }
        }
    }

    fun isAlive(): Boolean = webSocket != null && _state.value is SamsungConnectionState.Connected

    private fun send(label: String, payload: String): Boolean {
        val ws = webSocket
        val id = sendSeq.incrementAndGet()
        if (ws == null) {
            Log.e(TAG_WS, "SEND#$id $label failed: ws=null state=${_state.value}")
            return false
        }
        val ok = ws.send(payload)
        Log.d(TAG_WS, "SEND#$id $label ok=$ok payload=$payload")
        if (!ok) {
            Log.e(TAG_WS, "SEND#$id $label sendReturnedFalse state=${_state.value}")
            _state.value = SamsungConnectionState.Disconnected
            scheduleReconnect("send-false label=$label")
        }
        return ok
    }

    fun sendKey(key: String, cmd: String = "Click", times: Int = 1) {
        if (_state.value !is SamsungConnectionState.Connected) {
            Log.e(TAG_WS, "sendKey ignored (not connected) key=$key cmd=$cmd state=${_state.value}")
            return
        }
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
            send("key:$key/$cmd", payload)
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
        if (_state.value !is SamsungConnectionState.Connected) {
            Log.e(TAG_WS, "moveCursor ignored (not connected) state=${_state.value}")
            return
        }
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
        send("mouse:move", payload)
    }

    fun sendText(text: String, end: Boolean = false) {
        if (_state.value !is SamsungConnectionState.Connected) {
            Log.e(TAG_WS, "sendText ignored (not connected) state=${_state.value}")
            return
        }
        if (text.isBlank()) return

        if (firstTextSent.compareAndSet(false, true)) {
            val b = buildJson(
                method = "ms.channel.emit",
                params = mapOf(
                    "event" to JsonPrimitive("custom.remote.textReceived"),
                    "to" to JsonPrimitive("broadcast")
                )
            )
            send("ime:broadcast", b)
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
        send("ime:text", payload)

        if (end) endText()
    }

    fun endText() {
        if (_state.value !is SamsungConnectionState.Connected) {
            Log.e(TAG_WS, "endText ignored (not connected) state=${_state.value}")
            return
        }
        val payload = buildJson(
            method = "ms.remote.control",
            params = mapOf(
                "TypeOfRemote" to JsonPrimitive("SendInputEnd")
            )
        )
        send("ime:end", payload)
        firstTextSent.set(false)
    }

    fun runApp(appId: String, appType: String = "DEEP_LINK", metaTag: String = "") {
        if (_state.value !is SamsungConnectionState.Connected) {
            Log.e(TAG_WS, "runApp ignored (not connected) appId=$appId type=$appType state=${_state.value}")
            return
        }
        lastLaunch.value = "appId=$appId type=$appType meta=$metaTag"
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
        send("app:launch", payload)
    }

    fun runAppNative(appId: String, metaTag: String = "") {
        runApp(appId, "NATIVE_LAUNCH", metaTag)
    }

    fun runAppDeepLink(appId: String, metaTag: String) {
        runApp(appId, "DEEP_LINK", metaTag)
    }

    fun openBrowser(url: String) {
        if (_state.value !is SamsungConnectionState.Connected) {
            Log.e(TAG_WS, "openBrowser ignored (not connected) url=$url state=${_state.value}")
            return
        }
        runApp("org.tizen.browser", "NATIVE_LAUNCH", url)
    }

    suspend fun appList(timeoutMs: Long = 12000): List<SamsungAppInfo>? {
        if (_state.value !is SamsungConnectionState.Connected) {
            Log.e(TAG_WS, "appList ignored (not connected) state=${_state.value}")
            return null
        }
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
        send("app:list", payload)

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
                closeInternal()
                return
            }

            if (event == "ms.channel.connect") {
                handshakeDone.set(true)
                val data = obj["data"]?.jsonObject
                val tok = data?.get("token")?.jsonPrimitive?.contentOrNull
                if (!tok.isNullOrBlank()) _token.value = tok
                _state.value = SamsungConnectionState.Connected(host.orEmpty(), port)
                Log.d(TAG_WS, "HANDSHAKE OK host=${host.orEmpty()} port=$port token=${tok.orEmpty()}")
                return
            }

            return
        }

        if (event == "ms.error") {
            val msg = obj["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            Log.e(TAG_WS, "MS_ERROR msg=${msg.orEmpty()} lastLaunch=${lastLaunch.value.orEmpty()} frame=$obj")
            return
        }

        if (event == "d2d_service_message") {
            Log.d(TAG_WS, "D2D $obj")
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