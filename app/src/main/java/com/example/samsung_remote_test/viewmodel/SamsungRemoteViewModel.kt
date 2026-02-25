package com.example.samsung_remote_test.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.samsung_remote_test.mirror.IpUtils
import com.example.samsung_remote_test.mirror.MirrorService
import com.example.samsung_remote_test.samsung.SamsungAppInfo
import com.example.samsung_remote_test.samsung.SamsungConnectionState
import com.example.samsung_remote_test.samsung.SamsungDiscoveredTv
import com.example.samsung_remote_test.samsung.SamsungSsdpDiscoverer
import com.example.samsung_remote_test.samsung.SamsungTvRestClient
import com.example.samsung_remote_test.samsung.SamsungTvWsClient
import com.example.samsung_remote_test.samsung.SamsungWol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "SamsungRemoteVM"

data class UiConfig(
    val host: String = "",
    val port: Int = 8002,
    val name: String = "SamsungTvRemote",
    val token: String = ""
)

data class SamsungUiState(
    val cfg: UiConfig = UiConfig(),
    val conn: SamsungConnectionState = SamsungConnectionState.Disconnected,
    val tokenCaptured: String? = null,
    val imeText: String = "",
    val browserUrl: String = "https://www.youtube.com/",
    val runAppId: String = "3201606009684",
    val restAppId: String = "3201606009684",
    val restResult: String = "",
    val apps: List<SamsungAppInfo> = emptyList(),
    val wolMac: String = "",
    val wolResult: String = "",
    val scanning: Boolean = false,
    val discovered: List<SamsungDiscoveredTv> = emptyList(),
    val isSmartViewConnected: Boolean = false,
    val smartViewDisplayName: String? = null,
    val isProjecting: Boolean = false,
    val projectionLastResult: String? = null
)

class SamsungRemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val http = com.example.samsung_remote_test.samsung.samsungUnsafeOkHttp()
    private val ws = SamsungTvWsClient(http)
    private val rest = SamsungTvRestClient(http)
    private val ssdp = SamsungSsdpDiscoverer(app.applicationContext, http)

    private val prefs: SharedPreferences =
        app.getSharedPreferences("samsung_remote_prefs", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(SamsungUiState())
    val ui: StateFlow<SamsungUiState> = _ui.asStateFlow()

    private var scanJob: Job? = null

    private val mirrorHttpPort = 8899
    private val mirrorFps = 12

    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshSmartViewState()
        override fun onDisplayRemoved(displayId: Int) = refreshSmartViewState()
        override fun onDisplayChanged(displayId: Int) = refreshSmartViewState()
    }

    init {
        val savedCfg = loadCfgFromPrefs()
        _ui.value = _ui.value.copy(cfg = savedCfg)

        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        refreshSmartViewState()

        viewModelScope.launch {
            ws.state.collect { st ->
                _ui.value = _ui.value.copy(conn = st)
                if (st is SamsungConnectionState.Connected) {
                    saveCfgToPrefs(_ui.value.cfg)
                }
            }
        }

        viewModelScope.launch {
            ws.token.collect { t ->
                if (!t.isNullOrBlank()) {
                    val nextCfg = _ui.value.cfg.copy(token = t)
                    _ui.value = _ui.value.copy(
                        tokenCaptured = t,
                        cfg = nextCfg
                    )
                    saveTokenToPrefs(t)
                }
            }
        }

        if (savedCfg.host.isNotBlank()) {
            ws.connect(
                savedCfg.host,
                savedCfg.port,
                savedCfg.name,
                savedCfg.token.trim().ifBlank { null }
            )
        }
    }

    override fun onCleared() {
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { ws.close() }
        super.onCleared()
    }

    private fun loadCfgFromPrefs(): UiConfig {
        val host = prefs.getString("host", "") ?: ""
        val port = prefs.getInt("port", 8002)
        val name = prefs.getString("name", "SamsungTvRemote") ?: "SamsungTvRemote"
        val token = prefs.getString("token", "") ?: ""
        return UiConfig(host = host, port = port, name = name, token = token)
    }

    private fun saveCfgToPrefs(cfg: UiConfig) {
        prefs.edit()
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("name", cfg.name)
            .putString("token", cfg.token)
            .apply()
    }

    private fun saveTokenToPrefs(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    private fun refreshSmartViewState() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val d = displays.firstOrNull()
        _ui.value = _ui.value.copy(
            isSmartViewConnected = d != null,
            smartViewDisplayName = d?.name
        )
        Log.d(TAG, "SmartView displays=${displays.size}, active=${d?.name}")
    }

    fun createMediaProjectionIntent(): Intent {
        val ctx = getApplication<Application>()
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    fun prepareMirrorOnTv(): Boolean {
        val ctx = getApplication<Application>()
        val st = _ui.value.conn
        if (st !is SamsungConnectionState.Connected) {
            _ui.value = _ui.value.copy(projectionLastResult = "Chưa connect TV")
            Log.e(TAG, "prepareMirrorOnTv: not connected")
            return false
        }

        val ip = IpUtils.getLocalIpv4(ctx)
        if (ip.isNullOrBlank()) {
            _ui.value = _ui.value.copy(projectionLastResult = "Không lấy được IP Wi-Fi của điện thoại")
            Log.e(TAG, "prepareMirrorOnTv: ip null")
            return false
        }

        val ok = com.example.samsung_remote_test.mirror.MirrorStreamHub.start(mirrorHttpPort, mirrorFps)
        if (!ok) {
            _ui.value = _ui.value.copy(projectionLastResult = "Không start được HTTP server")
            return false
        }

        com.example.samsung_remote_test.mirror.MirrorStreamHub.setFrameJpeg(
            com.example.samsung_remote_test.mirror.JpegFactory.black(1280, 720)
        )

        val url = "http://$ip:$mirrorHttpPort/?t=${System.currentTimeMillis()}"
        Log.d(TAG, "prepareMirrorOnTv: openBrowser url=$url")
        ws.openBrowser(url)
        _ui.value = _ui.value.copy(projectionLastResult = "TV mở: $url")
        return true
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        val ctx = getApplication<Application>()
        if (resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(ctx, MirrorService::class.java).apply {
                action = MirrorService.ACTION_START
                putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
                putExtra(MirrorService.EXTRA_DATA_INTENT, data)
            }
            ContextCompat.startForegroundService(ctx, i)
            _ui.value = _ui.value.copy(isProjecting = true, projectionLastResult = "Projection started")
        } else {
            com.example.samsung_remote_test.mirror.MirrorStreamHub.stop()
            _ui.value = _ui.value.copy(isProjecting = false, projectionLastResult = "User cancelled / denied")
        }
    }

    fun stopMediaProjection() {
        val ctx = getApplication<Application>()
        val i = Intent(ctx, MirrorService::class.java).apply {
            action = MirrorService.ACTION_STOP
        }
        runCatching { ctx.startService(i) }
        _ui.value = _ui.value.copy(isProjecting = false, projectionLastResult = "Stopped")
    }

    fun openCastSettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure { openWirelessDisplaySettings() }
    }

    fun openWirelessDisplaySettings() {
        val ctx = getApplication<Application>()
        val intent = Intent("android.settings.WIFI_DISPLAY_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun updateHost(v: String) {
        val next = _ui.value.cfg.copy(host = v.trim())
        _ui.value = _ui.value.copy(cfg = next)
    }

    fun updatePort(v: String) {
        val next = _ui.value.cfg.copy(port = v.toIntOrNull() ?: 8002)
        _ui.value = _ui.value.copy(cfg = next)
    }

    fun updateName(v: String) {
        val next = _ui.value.cfg.copy(name = v)
        _ui.value = _ui.value.copy(cfg = next)
    }

    fun updateToken(v: String) {
        val next = _ui.value.cfg.copy(token = v.trim())
        _ui.value = _ui.value.copy(cfg = next)
        saveTokenToPrefs(next.token)
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        _ui.value = _ui.value.copy(scanning = true, discovered = emptyList())
        scanJob = viewModelScope.launch {
            val list = ArrayList<SamsungDiscoveredTv>()
            ssdp.scan(2600L).collect { tv ->
                if (list.none { it.ip == tv.ip }) {
                    list.add(tv)
                    _ui.value = _ui.value.copy(discovered = list.toList())
                }
            }
            _ui.value = _ui.value.copy(scanning = false)
        }
    }

    fun pickDiscovered(tv: SamsungDiscoveredTv) {
        val next = _ui.value.cfg.copy(host = tv.ip, port = 8002)
        _ui.value = _ui.value.copy(cfg = next)
        saveCfgToPrefs(next)
    }

    fun connect() {
        val c = _ui.value.cfg
        val tok = c.token.trim().ifBlank { null }
        if (c.host.isBlank()) return
        saveCfgToPrefs(c)
        ws.connect(c.host, c.port, c.name, tok)
    }

    fun disconnect() {
        ws.close()
    }

    fun sendKey(key: String) {
        ws.sendKey(key)
    }

    fun sendKeyPress(key: String) {
        ws.sendKey(key, cmd = "Press")
    }

    fun sendKeyRelease(key: String) {
        ws.sendKey(key, cmd = "Release")
    }

    fun holdKey(key: String, seconds: Double) {
        ws.holdKey(key, seconds)
    }

    fun moveCursor(dx: Int, dy: Int) {
        ws.moveCursor(dx, dy, 0)
    }

    fun setImeText(v: String) {
        _ui.value = _ui.value.copy(imeText = v)
    }

    fun sendIme(end: Boolean) {
        ws.sendText(_ui.value.imeText, end = end)
    }

    fun endIme() {
        ws.endText()
    }

    fun setBrowserUrl(v: String) {
        _ui.value = _ui.value.copy(browserUrl = v)
    }

    private var pendingMoveDx = 0
    private var pendingMoveDy = 0
    private var moveBatchJob: Job? = null

    fun touchpadTap() {
        ws.sendKey("KEY_ENTER")
    }

    fun moveCursorTouchpad(dx: Int, dy: Int) {
        pendingMoveDx += dx
        pendingMoveDy += dy

        if (moveBatchJob?.isActive == true) return

        moveBatchJob = viewModelScope.launch {
            delay(16)
            val x = pendingMoveDx
            val y = pendingMoveDy
            pendingMoveDx = 0
            pendingMoveDy = 0
            if (x != 0 || y != 0) ws.moveCursor(x, y, 0)
        }
    }

    fun openBrowser() {
        val c = _ui.value.cfg
        val url = _ui.value.browserUrl
        if (_ui.value.conn !is SamsungConnectionState.Connected) {
            _ui.value = _ui.value.copy(restResult = "Not connected")
            return
        }

        ws.openBrowser(url)

        viewModelScope.launch {
            delay(700)
            val st = rest.appStatus(c.host, c.port, "org.tizen.browser")
            _ui.value = _ui.value.copy(restResult = st?.take(4000) ?: "Browser status: null")
        }
    }

    fun setRunAppId(v: String) {
        _ui.value = _ui.value.copy(runAppId = v)
    }

    fun runAppWs(appType: String = "NATIVE_LAUNCH", meta: String = "") {
        val c = _ui.value.cfg
        val appId = _ui.value.runAppId
        if (_ui.value.conn !is SamsungConnectionState.Connected) {
            _ui.value = _ui.value.copy(restResult = "Not connected")
            return
        }

        if (appType == "DEEP_LINK") {
            ws.runApp(appId, "DEEP_LINK", meta)
        } else {
            ws.runApp(appId, "NATIVE_LAUNCH", meta)
        }

        viewModelScope.launch {
            delay(700)
            val st = rest.appStatus(c.host, c.port, appId)
            if (st.isNullOrBlank()) {
                val r = rest.appRun(c.host, c.port, appId)
                _ui.value = _ui.value.copy(restResult = "WS sent. REST fallback=${r.orEmpty().take(2000)}")
            } else {
                _ui.value = _ui.value.copy(restResult = st.take(4000))
            }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            val list = ws.appList() ?: emptyList()
            _ui.value = _ui.value.copy(apps = list)
        }
    }

    fun setRestAppId(v: String) {
        _ui.value = _ui.value.copy(restAppId = v)
    }

    fun restDeviceInfo() {
        val c = _ui.value.cfg
        val r = rest.deviceInfo(c.host, c.port) ?: ""
        _ui.value = _ui.value.copy(restResult = r)
    }

    fun restAppStatus() {
        val c = _ui.value.cfg
        val r = rest.appStatus(c.host, c.port, _ui.value.restAppId) ?: ""
        _ui.value = _ui.value.copy(restResult = r)
    }

    fun restAppRun() {
        val c = _ui.value.cfg
        val r = rest.appRun(c.host, c.port, _ui.value.restAppId) ?: ""
        _ui.value = _ui.value.copy(restResult = r)
    }

    fun restAppClose() {
        val c = _ui.value.cfg
        val r = rest.appClose(c.host, c.port, _ui.value.restAppId) ?: ""
        _ui.value = _ui.value.copy(restResult = r)
    }

    fun restAppInstall() {
        val c = _ui.value.cfg
        val r = rest.appInstall(c.host, c.port, _ui.value.restAppId) ?: ""
        _ui.value = _ui.value.copy(restResult = r)
    }

    fun setWolMac(v: String) {
        _ui.value = _ui.value.copy(wolMac = v)
    }

    fun wolSend() {
        val ok = SamsungWol.send(_ui.value.wolMac)
        _ui.value = _ui.value.copy(wolResult = if (ok) "OK" else "Failed")
    }
}