package com.example.samsung_remote_test.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.samsung_remote_test.samsung.SamsungAppInfo
import com.example.samsung_remote_test.samsung.SamsungConnectionState
import com.example.samsung_remote_test.samsung.SamsungDiscoveredTv
import com.example.samsung_remote_test.samsung.SamsungSsdpDiscoverer
import com.example.samsung_remote_test.samsung.SamsungTvRestClient
import com.example.samsung_remote_test.samsung.SamsungTvWsClient
import com.example.samsung_remote_test.samsung.SamsungWol
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val runAppId: String = "org.tizen.browser",
    val restAppId: String = "org.tizen.browser",
    val restResult: String = "",
    val apps: List<SamsungAppInfo> = emptyList(),
    val wolMac: String = "",
    val wolResult: String = "",
    val scanning: Boolean = false,
    val discovered: List<SamsungDiscoveredTv> = emptyList(),
    val isMirroring: Boolean = false,
    val mirrorDisplayName: String? = null
)

class SamsungRemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val http = com.example.samsung_remote_test.samsung.samsungUnsafeOkHttp()
    private val ws = SamsungTvWsClient(http)
    private val rest = SamsungTvRestClient(http)
    private val ssdp = SamsungSsdpDiscoverer(app.applicationContext, http)

    private val _ui = MutableStateFlow(SamsungUiState())
    val ui: StateFlow<SamsungUiState> = _ui.asStateFlow()

    private var scanJob: Job? = null

    private val displayManager =
        app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshMirroringState()
        }

        override fun onDisplayRemoved(displayId: Int) {
            refreshMirroringState()
        }

        override fun onDisplayChanged(displayId: Int) {
            refreshMirroringState()
        }
    }

    init {
        displayManager.registerDisplayListener(
            displayListener,
            Handler(Looper.getMainLooper())
        )
        refreshMirroringState()

        viewModelScope.launch {
            ws.state.collect { st ->
                _ui.value = _ui.value.copy(conn = st)
            }
        }
        viewModelScope.launch {
            ws.token.collect { t ->
                if (!t.isNullOrBlank()) {
                    _ui.value = _ui.value.copy(
                        tokenCaptured = t,
                        cfg = _ui.value.cfg.copy(token = t)
                    )
                }
            }
        }
    }

    override fun onCleared() {
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        super.onCleared()
    }

    private fun refreshMirroringState() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val d = displays.firstOrNull()
        _ui.value = _ui.value.copy(
            isMirroring = d != null,
            mirrorDisplayName = d?.name
        )
    }

    fun openCastSettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure {
            openWirelessDisplaySettings()
        }
    }

    fun openWirelessDisplaySettings() {
        val ctx = getApplication<Application>()
        val intent = Intent("android.settings.WIFI_DISPLAY_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun updateHost(v: String) {
        _ui.value = _ui.value.copy(cfg = _ui.value.cfg.copy(host = v.trim()))
    }

    fun updatePort(v: String) {
        _ui.value = _ui.value.copy(cfg = _ui.value.cfg.copy(port = v.toIntOrNull() ?: 8002))
    }

    fun updateName(v: String) {
        _ui.value = _ui.value.copy(cfg = _ui.value.cfg.copy(name = v))
    }

    fun updateToken(v: String) {
        _ui.value = _ui.value.copy(cfg = _ui.value.cfg.copy(token = v.trim()))
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
        _ui.value = _ui.value.copy(
            cfg = _ui.value.cfg.copy(host = tv.ip, port = 8002),
        )
    }

    fun connect() {
        val c = _ui.value.cfg
        val tok = c.token.trim().ifBlank { null }
        if (c.host.isBlank()) return
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

    fun openBrowser() {
        ws.openBrowser(_ui.value.browserUrl)
    }

    fun setRunAppId(v: String) {
        _ui.value = _ui.value.copy(runAppId = v)
    }

    fun runAppWs(appType: String = "DEEP_LINK", meta: String = "") {
        ws.runApp(_ui.value.runAppId, appType, meta)
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