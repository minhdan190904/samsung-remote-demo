package com.example.samsung_remote_test.ui

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.samsung_remote_test.samsung.SamsungConnectionState
import com.example.samsung_remote_test.viewmodel.SamsungRemoteViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungRemoteApp(vm: SamsungRemoteViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Connect", "Remote", "IME", "Apps", "REST", "WOL", "Mirror")

    LaunchedEffect(Unit) {
        vm.startScan()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> ConnectTab(vm)
                1 -> RemoteTab(vm)
                2 -> ImeTab(vm)
                3 -> AppsTab(vm)
                4 -> RestTab(vm)
                5 -> WolTab(vm)
                6 -> MirrorTab(vm)
            }
        }
    }
}

@Composable
private fun ConnectTab(vm: SamsungRemoteViewModel) {
    val ui by vm.ui.collectAsState()
    val cfg = ui.cfg

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::startScan) { Text(if (ui.scanning) "Scanning..." else "Scan TVs") }
            Button(onClick = vm::connect) { Text("Connect") }
            Button(onClick = vm::disconnect) { Text("Disconnect") }
        }

        if (ui.discovered.isNotEmpty()) {
            Text("Discovered:")
            LazyColumn(Modifier.height(180.dp)) {
                items(ui.discovered) { tv ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tv.friendlyName ?: tv.modelName ?: tv.ip)
                            Text(tv.ip)
                        }
                        Button(onClick = { vm.pickDiscovered(tv) }) { Text("Use") }
                    }
                    Divider()
                }
            }
        }

        OutlinedTextField(
            value = cfg.host,
            onValueChange = vm::updateHost,
            label = { Text("Host/IP") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cfg.port.toString(),
            onValueChange = vm::updatePort,
            label = { Text("Port (8001/8002)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cfg.name,
            onValueChange = vm::updateName,
            label = { Text("Client name") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cfg.token,
            onValueChange = vm::updateToken,
            label = { Text("Token (auto after pairing)") },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            when (val st = ui.conn) {
                SamsungConnectionState.Disconnected -> "State: Disconnected"
                SamsungConnectionState.Connecting -> "State: Connecting"
                is SamsungConnectionState.Connected -> "State: Connected (${st.host}:${st.port})"
                is SamsungConnectionState.Unauthorized -> "State: Unauthorized (hãy Allow trên TV)"
                is SamsungConnectionState.Failed -> "State: Failed: ${st.detail}"
            }
        )

        if (!ui.tokenCaptured.isNullOrBlank()) {
            Text("Token captured: ${ui.tokenCaptured}")
        }

        Text("Pairing: lần đầu Connect -> TV hiện popup Allow. Allow xong token sẽ tự điền.")
    }
}

@Composable
private fun RemoteTab(vm: SamsungRemoteViewModel) {
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.sendKey("KEY_POWER") }) { Text("Power") }
            Button(onClick = { vm.sendKey("KEY_HOME") }) { Text("Home") }
            Button(onClick = { vm.sendKey("KEY_MENU") }) { Text("Menu") }
            Button(onClick = { vm.sendKey("KEY_SOURCE") }) { Text("Source") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.sendKey("KEY_RETURN") }) { Text("Back") }
            Button(onClick = { vm.sendKey("KEY_INFO") }) { Text("Info") }
            Button(onClick = { vm.sendKey("KEY_GUIDE") }) { Text("Guide") }
            Button(onClick = { vm.sendKey("KEY_TOOLS") }) { Text("Tools") }
        }

        Divider()

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { vm.sendKey("KEY_UP") }, modifier = Modifier.width(120.dp)) { Text("Up") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.sendKey("KEY_LEFT") }, modifier = Modifier.width(120.dp)) { Text("Left") }
                    Button(onClick = { vm.sendKey("KEY_ENTER") }, modifier = Modifier.width(120.dp)) { Text("OK") }
                    Button(onClick = { vm.sendKey("KEY_RIGHT") }, modifier = Modifier.width(120.dp)) { Text("Right") }
                }
                Button(onClick = { vm.sendKey("KEY_DOWN") }, modifier = Modifier.width(120.dp)) { Text("Down") }
            }
        }

        Divider()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.sendKey("KEY_VOLUP") }) { Text("Vol+") }
            Button(onClick = { vm.sendKey("KEY_VOLDOWN") }) { Text("Vol-") }
            Button(onClick = { vm.sendKey("KEY_MUTE") }) { Text("Mute") }
            Button(onClick = { vm.sendKey("KEY_EXIT") }) { Text("Exit") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.sendKey("KEY_CHUP") }) { Text("CH+") }
            Button(onClick = { vm.sendKey("KEY_CHDOWN") }) { Text("CH-") }
            Button(onClick = { vm.sendKey("KEY_CH_LIST") }) { Text("CH LIST") }
            Button(onClick = { vm.sendKey("KEY_PRECH") }) { Text("PRE-CH") }
            Button(onClick = { vm.sendKey("KEY_SEARCH") }) { Text("SEARCH") }
        }

        Divider()

        NumericPad(
            onDigit = { d -> vm.sendKey("KEY_$d") },
            onEnter = { vm.sendKey("KEY_ENTER") }
        )

        Divider()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.sendKey("KEY_RED") }) { Text("Red") }
            Button(onClick = { vm.sendKey("KEY_GREEN") }) { Text("Green") }
            Button(onClick = { vm.sendKey("KEY_YELLOW") }) { Text("Yellow") }
            Button(onClick = { vm.sendKey("KEY_BLUE") }) { Text("Blue") }
        }

        Divider()

        Text("Test keys (quick grid)")

        val testKeys = listOf(
            "KEY_SEARCH" to "Search",
            "KEY_PLAY" to "Play",
            "KEY_PAUSE" to "Pause",
            "KEY_PLAYPAUSE" to "Play/Pause",
            "KEY_STOP" to "Stop",
            "KEY_FF" to "FF",
            "KEY_REWIND" to "Rew",
            "KEY_REC" to "Rec",
            "KEY_SUB_TITLE" to "Subtitle",
            "KEY_CAPTION" to "Caption",
            "KEY_AD" to "AD",
            "KEY_AUDIO" to "Audio",
            "KEY_PICTURE_SIZE" to "Aspect",
            "KEY_PIP_ONOFF" to "PIP",
            "KEY_SLEEP" to "Sleep",
            "KEY_CONTENTS" to "Contents",
            "KEY_SMARTHUB" to "SmartHub",
            "KEY_APPS" to "Apps",
            "KEY_SETTINGS" to "Settings",
            "KEY_E_MANUAL" to "e-Manual",
            "KEY_TTX_MIX" to "Teletext",
            "KEY_MTS" to "MTS",
            "KEY_MORE" to "More",
            "KEY_CHG_MODE" to "Mode",
            "KEY_PMODE" to "P.Mode",
            "KEY_SMODE" to "S.Mode",
            "KEY_SOURCE" to "Source",
            "KEY_HOME" to "Home",
            "KEY_RETURN" to "Back",
            "KEY_EXIT" to "Exit",
            "KEY_CANCEL" to "Cancel",
            "KEY_ENTER" to "Enter"
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            testKeys.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (k, label) ->
                        Button(
                            onClick = { vm.sendKey(k) },
                            modifier = Modifier.weight(1f)
                        ) { Text(label) }
                    }
                    repeat(4 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Divider()

        RemoteTouchpadSamsung(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            onTap = vm::touchpadTap,
            onMove = vm::moveCursorTouchpad
        )
    }
}

@Composable
private fun NumericPad(
    onDigit: (Int) -> Unit,
    onEnter: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDigit(1) }, modifier = Modifier.weight(1f)) { Text("1") }
            Button(onClick = { onDigit(2) }, modifier = Modifier.weight(1f)) { Text("2") }
            Button(onClick = { onDigit(3) }, modifier = Modifier.weight(1f)) { Text("3") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDigit(4) }, modifier = Modifier.weight(1f)) { Text("4") }
            Button(onClick = { onDigit(5) }, modifier = Modifier.weight(1f)) { Text("5") }
            Button(onClick = { onDigit(6) }, modifier = Modifier.weight(1f)) { Text("6") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDigit(7) }, modifier = Modifier.weight(1f)) { Text("7") }
            Button(onClick = { onDigit(8) }, modifier = Modifier.weight(1f)) { Text("8") }
            Button(onClick = { onDigit(9) }, modifier = Modifier.weight(1f)) { Text("9") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            Button(onClick = { onDigit(0) }, modifier = Modifier.weight(1f)) { Text("0") }
            Button(onClick = onEnter, modifier = Modifier.weight(1f)) { Text("Enter") }
        }
    }
}

@Composable
private fun ImeTab(vm: SamsungRemoteViewModel) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = ui.imeText,
            onValueChange = vm::setImeText,
            label = { Text("IME text") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.sendIme(end = false) }) { Text("Send") }
            Button(onClick = { vm.sendIme(end = true) }) { Text("Send + End") }
            Button(onClick = vm::endIme) { Text("End") }
        }
    }
}

@Composable
private fun AppsTab(vm: SamsungRemoteViewModel) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::refreshApps) { Text("List apps") }
        }

        OutlinedTextField(
            value = ui.runAppId,
            onValueChange = vm::setRunAppId,
            label = { Text("Run appId (WS)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.runAppWs("DEEP_LINK", "") }) { Text("Run (DEEP_LINK)") }
            Button(onClick = { vm.runAppWs("NATIVE_LAUNCH", "") }) { Text("Run (NATIVE)") }
        }

        OutlinedTextField(
            value = ui.browserUrl,
            onValueChange = vm::setBrowserUrl,
            label = { Text("Open browser URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = vm::openBrowser) { Text("Open Browser") }

        Divider()

        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.apps) { app ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("AppId: ${app.appId}")
                    Text("Name: ${app.name.orEmpty()}")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { vm.setRunAppId(app.appId); vm.runAppWs("NATIVE_LAUNCH", "") }) { Text("Run") }
                    }
                }
                Divider()
            }
        }
    }
}

@Composable
private fun RestTab(vm: SamsungRemoteViewModel) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::restDeviceInfo) { Text("Device info") }
        }
        OutlinedTextField(
            value = ui.restAppId,
            onValueChange = vm::setRestAppId,
            label = { Text("AppId (REST)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::restAppStatus) { Text("Status") }
            Button(onClick = vm::restAppRun) { Text("Run") }
            Button(onClick = vm::restAppClose) { Text("Close") }
            Button(onClick = vm::restAppInstall) { Text("Install") }
        }
        Divider()
        Text(ui.restResult.take(4000))
    }
}

@Composable
private fun WolTab(vm: SamsungRemoteViewModel) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = ui.wolMac,
            onValueChange = vm::setWolMac,
            label = { Text("MAC address (WOL)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::wolSend) { Text("Send WOL") }
            Text("Result: ${ui.wolResult}")
        }
        Text("Tip: WOL chỉ bật TV nếu TV + mạng support Wake-on-LAN.")
    }
}

@Composable
private fun MirrorTab(vm: SamsungRemoteViewModel) {
    val ui by vm.ui.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        vm.onMediaProjectionResult(res.resultCode, res.data)
    }

    var showPicker by remember { androidx.compose.runtime.mutableStateOf(false) }
    var selectedIp by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(if (ui.isProjecting) "MediaProjection: ON" else "MediaProjection: OFF")
        if (!ui.projectionLastResult.isNullOrBlank()) Text("Last: ${ui.projectionLastResult}")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { showPicker = true },
                enabled = !ui.isProjecting
            ) { Text("Start mirroring") }

            Button(
                onClick = vm::stopMediaProjection,
                enabled = ui.isProjecting,
                colors = ButtonDefaults.buttonColors()
            ) { Text("Stop") }
        }

        Divider()

        Text(
            if (ui.isSmartViewConnected) {
                "SmartView/Wi-Fi display: CONNECTED (${ui.smartViewDisplayName ?: "External"})"
            } else {
                "SmartView/Wi-Fi display: NOT CONNECTED"
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::openCastSettings) { Text("Open Smart View") }
            Button(onClick = vm::openWirelessDisplaySettings) { Text("Wireless display") }
        }

        Divider()
        Text("Flow đúng: chọn TV + connect -> TV mở trang nhận stream -> hệ thống hỏi quyền capture -> bắt đầu gửi.")
    }

    if (showPicker) {
        LaunchedEffect(Unit) {
            if (ui.discovered.isEmpty()) vm.startScan()
        }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Chọn TV để mirroring") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        when (val st = ui.conn) {
                            SamsungConnectionState.Disconnected -> "State: Disconnected"
                            SamsungConnectionState.Connecting -> "State: Connecting"
                            is SamsungConnectionState.Connected -> "State: Connected (${st.host}:${st.port})"
                            is SamsungConnectionState.Unauthorized -> "State: Unauthorized (hãy Allow trên TV)"
                            is SamsungConnectionState.Failed -> "State: Failed: ${st.detail}"
                        }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = vm::startScan) { Text(if (ui.scanning) "Scanning..." else "Scan") }
                        Button(
                            onClick = {
                                val ip = selectedIp ?: return@Button
                                val tv = ui.discovered.firstOrNull { it.ip == ip } ?: return@Button
                                vm.pickDiscovered(tv)
                                vm.connect()
                            },
                            enabled = selectedIp != null
                        ) { Text("Connect") }
                    }

                    if (ui.discovered.isEmpty()) {
                        Text("Chưa tìm thấy TV")
                    } else {
                        LazyColumn(Modifier.height(220.dp)) {
                            items(ui.discovered) { tv ->
                                val ip = tv.ip
                                val name = tv.friendlyName ?: tv.modelName ?: ip
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name)
                                        Text(ip)
                                    }
                                    androidx.compose.material3.RadioButton(
                                        selected = selectedIp == ip,
                                        onClick = { selectedIp = ip }
                                    )
                                }
                                Divider()
                            }
                        }
                    }

                    Text("Sau khi Connected, bấm Tiếp theo để TV mở receiver rồi mới xin quyền capture.")
                }
            },
            confirmButton = {
                val connected = ui.conn is SamsungConnectionState.Connected
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!connected) return@Button
                            val ok = vm.prepareMirrorOnTv()
                            if (ok) {
                                showPicker = false
                                launcher.launch(vm.createMediaProjectionIntent())
                            }
                        },
                        enabled = connected
                    ) { Text("Tiếp theo") }
                }
            },
            dismissButton = {
                Button(onClick = { showPicker = false }) { Text("Hủy") }
            }
        )
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun RemoteTouchpadSamsung(
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val padColor = Color(0xFF15171A)
    val stroke = Color(0xFF2A2E34)
    val hintColor = Color(0xFFB7BDC6)

    var accX by remember { androidx.compose.runtime.mutableStateOf(0f) }
    var accY by remember { androidx.compose.runtime.mutableStateOf(0f) }
    var scrollAcc by remember { androidx.compose.runtime.mutableStateOf(0f) }

    Surface(
        modifier = modifier,
        shape = shape,
        shadowElevation = 2.dp,
        color = padColor
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(padColor)
                .border(1.dp, stroke, shape)
        ) {
            val scrollWidth = 34.dp
            val scrollPaddingEnd = 10.dp
            val rightReserved = scrollWidth + scrollPaddingEnd

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = rightReserved)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                accX = 0f
                                accY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()

                                val scale = 1.5f
                                accX += dragAmount.x * scale
                                accY += dragAmount.y * scale

                                val stepX = accX.toInt()
                                val stepY = accY.toInt()

                                if (stepX != 0 || stepY != 0) {
                                    accX -= stepX.toFloat()
                                    accY -= stepY.toFloat()
                                    onMove(stepX, stepY)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Touchpad\nTap = OK • Drag = Cursor",
                    color = hintColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = scrollPaddingEnd)
                    .width(scrollWidth)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1E2227))
                    .border(1.dp, stroke, RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { scrollAcc = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()

                                val scale = 3.0f
                                scrollAcc += dragAmount.y * scale
                                val stepY = scrollAcc.toInt()

                                if (stepY != 0) {
                                    scrollAcc -= stepY.toFloat()
                                    onMove(0, stepY)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SCROLL",
                    color = hintColor.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}