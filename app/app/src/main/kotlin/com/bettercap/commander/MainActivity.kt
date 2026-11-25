package com.bettercap.commander

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CommanderApp() }
    }
}

private const val ENGINE_HOST = "127.0.0.1"
private const val ENGINE_PORT = 8766

sealed class EngineEvent {
    data class Hello(val version: String, val message: String) : EngineEvent()
    data class Status(
        val version: String,
        val active: Boolean,
        val iface: String,
        val labSubnet: String,
        val startedAt: String,
        val clients: Int,
        val handshakes: Int,
    ) : EngineEvent()

    data class Log(val message: String, val level: String) : EngineEvent()
    data class Ap(val bssid: String, val ssid: String, val channel: Int,
                  val signal: Int, val security: String) : EngineEvent()
    data class Host(val ip: String, val mac: String, val hostname: String,
                    val vendor: String) : EngineEvent()
    data class Ble(val mac: String, val name: String, val rssi: Int) : EngineEvent()
    data class Modules(val items: List<ModuleInfo>) : EngineEvent()
    data class Result(val cmd: String, val ok: Boolean, val message: String,
                      val code: String) : EngineEvent()
    data class Handshake(val bssid: String, val client: String, val file: String,
                         val bytes: Int, val time: String, val count: Int,
                         val raw: String) : EngineEvent()
    data class Other(val text: String) : EngineEvent()
}

class EngineClient(
    private val onStatus: (String) -> Unit,
    private val onMessage: (EngineEvent) -> Unit,
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val sendLock = Any()
    private val pendingSends = java.util.ArrayDeque<String>()
    @Volatile
    private var running = false

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        while (running) {
            var current: Socket? = null
            try {
                onStatus("Connecting...")
                current = Socket()
                current.connect(InetSocketAddress(ENGINE_HOST, ENGINE_PORT), 2000)
                socket = current
                writer = BufferedWriter(
                    OutputStreamWriter(current.getOutputStream(), StandardCharsets.UTF_8)
                )
                val connectedWriter = writer ?: throw IllegalStateException("writer unavailable")
                synchronized(sendLock) {
                    while (!pendingSends.isEmpty()) {
                        connectedWriter.write(pendingSends.removeFirst())
                        connectedWriter.write("\n")
                    }
                    connectedWriter.flush()
                }
                onStatus("Connected")
                val reader = BufferedReader(
                    InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8)
                )
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        onMessage(parseEvent(JSONObject(line)))
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    current?.close()
                } catch (_: Exception) {
                }
                socket = null
                writer = null
            }
            if (running) {
                onStatus("Reconnecting...")
                delay(300)
            }
        }
        onStatus("Disconnected")
    }

    fun send(cmd: String, extras: Map<String, Any> = emptyMap()) {
        val obj = JSONObject().put("cmd", cmd)
        extras.forEach { (key, value) -> obj.put(key, value) }
        val line = obj.toString()
        synchronized(sendLock) {
            val w = writer
            if (w == null) {
                if (pendingSends.size >= 20) pendingSends.removeFirst()
                pendingSends.addLast(line)
                onStatus("Queued: $cmd")
                try { socket?.close() } catch (_: Exception) {}
                return@synchronized
            }
            try {
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                writer = null
                if (pendingSends.size >= 20) pendingSends.removeFirst()
                pendingSends.addLast(line)
                onStatus("Queued after write error: $cmd")
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun disconnect() {
        running = false
        synchronized(sendLock) { pendingSends.clear() }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        writer = null
        onStatus("Disconnected")
    }

    private fun parseEvent(obj: JSONObject): EngineEvent {
        val name = obj.optString("event", "")
        val data = obj.optJSONObject("data") ?: JSONObject()
        return when (name) {
            "hello" -> EngineEvent.Hello(
                data.optString("version", ""), data.optString("message", "")
            )
            "status" -> EngineEvent.Status(
                data.optString("version", ""),
                data.optBoolean("active", false),
                data.optString("iface", ""),
                data.optString("lab_subnet", ""),
                data.optString("started_at", ""),
                data.optInt("clients", 0),
                data.optInt("handshakes", 0),
            )
            "log" -> EngineEvent.Log(
                data.optString("message", ""), data.optString("level", "info")
            )
            "ap" -> EngineEvent.Ap(
                data.optString("bssid", ""),
                data.optString("ssid", ""),
                data.optInt("channel", 0),
                data.optInt("signal", 0),
                data.optString("security", ""),
            )
            "host" -> EngineEvent.Host(
                data.optString("ip", ""),
                data.optString("mac", ""),
                data.optString("hostname", ""),
                data.optString("vendor", ""),
            )
            "ble" -> EngineEvent.Ble(
                data.optString("mac", ""),
                data.optString("name", ""),
                data.optInt("rssi", 0),
            )
            "modules" -> {
                val items = mutableListOf<ModuleInfo>()
                val arr = obj.optJSONArray("data") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    items.add(
                        ModuleInfo(
                            m.optString("name", ""),
                            m.optString("description", ""),
                            m.optBoolean("running", false),
                        )
                    )
                }
                EngineEvent.Modules(items)
            }
            "result" -> EngineEvent.Result(
                data.optString("cmd", ""),
                data.optBoolean("ok", false),
                data.optString("message", ""),
                data.optString("code", ""),
            )
            "handshake" -> EngineEvent.Handshake(
                data.optString("bssid", ""),
                data.optString("client", "").ifBlank { data.optString("sta", "") },
                data.optString("file", ""),
                data.optInt("bytes", 0),
                data.optString("time", ""),
                data.optInt("count", 0),
                data.optString("raw", ""),
            )
            else -> EngineEvent.Other("$name ${obj.optString("data", "")}")
        }
    }
}

data class ApInfo(val bssid: String, val ssid: String, val channel: Int,
                  val signal: Int, val security: String)
data class HostInfo(val ip: String, val mac: String, val hostname: String, val vendor: String)
data class BleInfo(val mac: String, val name: String, val rssi: Int)
data class ModuleInfo(val name: String, val description: String, val running: Boolean)
data class LogInfo(val message: String, val level: String)
data class ResultInfo(val cmd: String, val ok: Boolean, val message: String, val code: String)
data class HandshakeInfo(val bssid: String, val client: String, val file: String,
                         val bytes: Int, val time: String, val count: Int,
                         val raw: String)

data class UiState(
    val status: String = "Disconnected",
    val version: String = "",
    val active: Boolean = false,
    val iface: String = "",
    val labSubnet: String = "",
    val startedAt: String = "",
    val clients: Int = 0,
    val aps: List<ApInfo> = emptyList(),
    val hosts: List<HostInfo> = emptyList(),
    val ble: List<BleInfo> = emptyList(),
    val modules: List<ModuleInfo> = emptyList(),
    val logs: List<LogInfo> = emptyList(),
    val lastResult: ResultInfo? = null,
    val handshake: HandshakeInfo? = null,
    val handshakeCount: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommanderApp() {
    var ui by remember { mutableStateOf(UiState()) }
    var tab by remember { mutableIntStateOf(0) }
    var selectedBssid by remember { mutableStateOf("") }
    var clientMac by remember { mutableStateOf("") }
    var consoleLine by remember { mutableStateOf("") }
    var consoleOutput by remember { mutableStateOf(listOf<String>()) }
    var moduleFilter by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val client = remember {
        EngineClient(
            onStatus = { s -> ui = ui.copy(status = s) },
            onMessage = { event ->
                ui = when (event) {
                    is EngineEvent.Hello -> ui.copy(version = event.version)
                    is EngineEvent.Status -> ui.copy(
                        version = event.version.ifBlank { ui.version },
                        active = event.active, iface = event.iface,
                        labSubnet = event.labSubnet, startedAt = event.startedAt,
                        clients = event.clients, handshakeCount = event.handshakes,
                    )
                    is EngineEvent.Log -> ui.copy(
                        logs = (ui.logs + LogInfo(event.message, event.level)).takeLast(120)
                    )
                    is EngineEvent.Ap -> {
                        val item = ApInfo(event.bssid, event.ssid, event.channel,
                                          event.signal, event.security)
                        val next = ui.aps.filterNot { it.bssid == item.bssid } + item
                        ui.copy(aps = next.sortedBy { it.signal })
                    }
                    is EngineEvent.Host -> {
                        val item = HostInfo(event.ip, event.mac, event.hostname, event.vendor)
                        val next = ui.hosts.filterNot { it.ip == item.ip } + item
                        ui.copy(hosts = next.sortedBy { it.ip })
                    }
                    is EngineEvent.Ble -> {
                        val item = BleInfo(event.mac, event.name, event.rssi)
                        val next = ui.ble.filterNot { it.mac == item.mac } + item
                        ui.copy(ble = next.sortedBy { it.rssi })
                    }
                    is EngineEvent.Modules -> ui.copy(modules = event.items)
                    is EngineEvent.Result -> {
                        val r = ResultInfo(event.cmd, event.ok, event.message, event.code)
                        if (event.cmd == "console") {
                            consoleOutput = (consoleOutput + "$ ${event.message}").takeLast(80)
                        }
                        ui.copy(lastResult = r)
                    }
                    is EngineEvent.Handshake -> {
                        val hs = HandshakeInfo(event.bssid, event.client, event.file,
                                               event.bytes, event.time, event.count,
                                               event.raw)
                        ui.copy(
                            handshake = hs,
                            handshakeCount = if (event.count > ui.handshakeCount)
                                event.count else ui.handshakeCount,
                            logs = (ui.logs + LogInfo(
                                "HANDSHAKE CAPTURED: ${hs.bssid.ifBlank { "capture file" }}",
                                "success",
                            )).takeLast(120),
                        )
                    }
                    is EngineEvent.Other -> ui
                }
            },
        )
    }

    LaunchedEffect(Unit) { client.connect() }
    DisposableEffect(Unit) {
        onDispose { client.disconnect() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BetterCap Commander") },
                actions = {
                    Text(
                        ui.status,
                        modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Overview", "WiFi", "LAN", "Modules", "Console")
                    .forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title) },
                        )
                    }
            }
            when (tab) {
                0 -> OverviewTab(ui, client)
                1 -> WifiTab(ui, client, selectedBssid, { selectedBssid = it },
                            clientMac, { clientMac = it })
                2 -> LanTab(ui, client)
                3 -> ModulesTab(ui, client, moduleFilter, { moduleFilter = it })
                4 -> ConsoleTab(client, consoleLine, { consoleLine = it }, consoleOutput)
            }
        }
    }
}

@Composable
fun OverviewTab(ui: UiState, client: EngineClient) {
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Button(
                onClick = {
                    try {
                        Runtime.getRuntime().exec(
                            arrayOf("sh", "-c",
                                "touch /sdcard/Download/BC_LAUNCH.trigger")
                        )
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("LAUNCH BETTERCAP") }
        }
        item { HandshakeAlert(ui) }
        item {
            CardSection("Engine") {
                Text("Status: ${ui.status}")
                Text("Engine version: ${ui.version.ifBlank { "unknown" }}")
                Text("bettercap active: ${if (ui.active) "yes" else "no"}")
                Text("Interface: ${ui.iface.ifBlank { "none" }}")
                Text("Lab subnet: ${ui.labSubnet.ifBlank { "not set" }}")
                Text("Started: ${ui.startedAt.ifBlank { "unknown" }}")
                Text("App clients: ${ui.clients}")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { client.send("status") }) { Text("Refresh") }
                    OutlinedButton(onClick = { client.send("modules") }) { Text("Refresh modules") }
                }
            }
        }
        item {
            CardSection("Last result") {
                val r = ui.lastResult
                if (r == null) Text("No result yet.")
                else {
                    Text("${r.cmd}: ${if (r.ok) "ok" else "failed"}", fontWeight = FontWeight.Bold)
                    Text(r.message)
                    if (r.code.isNotBlank()) Text("code: ${r.code}")
                }
            }
        }
        item {
            CardSection("Engine log") {
                if (ui.logs.isEmpty()) Text("No log results yet.")
                else ui.logs.takeLast(60).forEach {
                    Text("${it.level.uppercase()}: ${it.message}")
                }
            }
        }
    }
}

@Composable
fun WifiTab(
    ui: UiState,
    client: EngineClient,
    selectedBssid: String,
    onSelectBssid: (String) -> Unit,
    clientMac: String,
    onClientMac: (String) -> Unit,
) {
    val selected = ui.aps.find { it.bssid == selectedBssid }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { HandshakeAlert(ui) }
        item {
            CardSection("Wi-Fi recon") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        client.send("set", mapOf("key" to "wifi.interface", "value" to "fluxwl0"))
                        client.send("recon", mapOf("name" to "wifi.recon", "action" to "on"))
                    }) { Text("Recon On") }
                    OutlinedButton(onClick = {
                        client.send("recon", mapOf("name" to "wifi.recon", "action" to "off"))
                    }) { Text("Recon Off") }
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = { client.send("force", mapOf("mode" to "all")) }) {
                    Text("FORCE ALL HANDSHAKES")
                }
                Text("Requires the monitor adapter fluxwl0. Force deauths every visible AP.",
                     style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            CardSection("Targets") {
                Text("APs: ${ui.aps.size}")
                if (selected == null) Text("Tap an AP below to select it.")
                else {
                    Text("Selected: ${selected.ssid} (${selected.bssid})",
                         fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = clientMac,
                        onValueChange = onClientMac,
                        label = { Text("Client MAC (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = {
                        if (isValidTargetMac(selected.bssid) &&
                            (clientMac.isBlank() || isValidTargetMac(clientMac))
                        ) {
                            val extras = mutableMapOf<String, Any>("bssid" to selected.bssid)
                            if (clientMac.isNotBlank()) extras["client"] = clientMac.trim()
                            client.send("deauth", extras)
                        }
                    }) { Text("DEAUTH") }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = {
                        client.send("capture", mapOf("bssid" to selected.bssid))
                    }) { Text("CAPTURE HANDSHAKE") }
                    Text("Capture locks the AP channel, then deauths it. Broadcast, multicast and zero MACs are rejected.",
                         style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { Text("Access points", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("▮ best", color = SignalGreen, style = MaterialTheme.typography.labelSmall)
                Text("▮ good", color = SignalYellowGreen, style = MaterialTheme.typography.labelSmall)
                Text("▮ fair", color = SignalYellow, style = MaterialTheme.typography.labelSmall)
                Text("▮ weak", color = SignalRed, style = MaterialTheme.typography.labelSmall)
            }
        }
        items(ui.aps, key = { it.bssid }) { ap ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = apCardColor(ap.signal)),
                onClick = { onSelectBssid(ap.bssid) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(ap.ssid.ifBlank { "(hidden)" }, color = InkDark,
                         fontWeight = FontWeight.Bold)
                    Text("${signalBars(ap.signal)}  ${ap.signal} dBm",
                         color = signalColor(ap.signal), fontWeight = FontWeight.Bold)
                    Text("${ap.bssid}  ch ${ap.channel}", color = InkDark)
                    if (ap.security.isNotBlank()) {
                        Text(ap.security, color = securityColor(ap.security))
                    }
                }
            }
        }
    }
}

@Composable
fun LanTab(ui: UiState, client: EngineClient) {
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            CardSection("LAN recon") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        client.send("recon", mapOf("name" to "net.recon", "action" to "on"))
                    }) { Text("Recon On") }
                    OutlinedButton(onClick = {
                        client.send("recon", mapOf("name" to "net.recon", "action" to "off"))
                    }) { Text("Recon Off") }
                }
            }
        }
        item { Text("Hosts (${ui.hosts.size})", style = MaterialTheme.typography.titleMedium) }
        items(ui.hosts, key = { it.ip }) { host ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(host.ip, fontWeight = FontWeight.Bold)
                    if (host.hostname.isNotBlank()) Text("name: ${host.hostname}")
                    if (host.mac.isNotBlank()) Text("mac: ${host.mac}")
                    if (host.vendor.isNotBlank()) Text("vendor: ${host.vendor}")
                }
            }
        }
        item { Text("Bluetooth LE (${ui.ble.size})", style = MaterialTheme.typography.titleMedium) }
        items(ui.ble, key = { it.mac }) { dev ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(dev.name.ifBlank { dev.mac }, fontWeight = FontWeight.Bold)
                    Text("${dev.mac}  ${dev.rssi} dBm")
                }
            }
        }
    }
}

@Composable
fun ModulesTab(
    ui: UiState,
    client: EngineClient,
    filter: String,
    onFilter: (String) -> Unit,
) {
    val items = ui.modules.filter {
        filter.isBlank() || it.name.contains(filter, ignoreCase = true) ||
                it.description.contains(filter, ignoreCase = true)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            CardSection("Modules (${ui.modules.size})") {
                OutlinedTextField(
                    value = filter,
                    onValueChange = onFilter,
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { client.send("modules") }) { Text("Refresh") }
            }
        }
        items(items, key = { it.name }) { mod ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(mod.name, fontWeight = FontWeight.Bold)
                        Text(if (mod.running) "running" else "stopped",
                             style = MaterialTheme.typography.labelSmall)
                        if (mod.description.isNotBlank()) {
                            Text(mod.description.take(120),
                                 style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Button(onClick = {
                        client.send("module", mapOf(
                            "name" to mod.name,
                            "action" to if (mod.running) "off" else "on",
                        ))
                    }) { Text(if (mod.running) "Stop" else "Start") }
                }
            }
        }
    }
}

@Composable
fun ConsoleTab(
    client: EngineClient,
    line: String,
    onLine: (String) -> Unit,
    output: List<String>,
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        CardSection("bettercap console") {
            OutlinedTextField(
                value = line,
                onValueChange = onLine,
                label = { Text("Command (e.g. help wifi)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (line.isNotBlank()) {
                        client.send("console", mapOf("line" to line.trim()))
                        onLine("")
                    }
                }) { Text("Run") }
                OutlinedButton(onClick = { client.send("events.clear") }) {
                    Text("Clear events")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(output) { Text(it) }
        }
    }
}

@Composable
fun HandshakeAlert(ui: UiState) {
    val hs = ui.handshake
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFC9F2DF)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "HANDSHAKE CAPTURED",
                color = SignalStrongInk,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Captures this session: ${ui.handshakeCount}",
                color = SignalStrongInk,
                style = MaterialTheme.typography.labelSmall,
            )
            if (hs == null) {
                Text("Waiting for capture...", color = InkDark,
                     style = MaterialTheme.typography.labelSmall)
            } else {
                Text("AP: ${hs.bssid.ifBlank { "unknown" }}", color = InkDark)
                if (hs.client.isNotBlank()) Text("Client: ${hs.client}", color = InkDark)
                if (hs.file.isNotBlank()) Text("File: ${hs.file}", color = InkDark)
                if (hs.bytes > 0) Text("Capture size: ${hs.bytes} bytes", color = InkDark)
                if (hs.raw.isNotBlank()) Text(hs.raw.take(180), color = InkDark,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun CardSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// Same four-step signal scale used by the bettercap web UI.
private val SignalGreen = Color(0xFF00C853)
private val SignalYellowGreen = Color(0xFFAEEA00)
private val SignalYellow = Color(0xFFFFD600)
private val SignalRed = Color(0xFFFF5252)

// Darker versions for text on the lighter AP cards.
private val InkDark = Color(0xFF0B1F2A)
private val SignalStrongInk = Color(0xFF00796B)
private val SignalGoodInk = Color(0xFF5D8A00)
private val SignalFairInk = Color(0xFF8A6D00)
private val SignalWeakInk = Color(0xFFC62828)
private val SecureInk = Color(0xFF00796B)
private val OpenInk = Color(0xFFC62828)

private fun signalColor(signal: Int): Color = when {
    signal >= -67 -> SignalStrongInk
    signal >= -70 -> SignalGoodInk
    signal >= -80 -> SignalFairInk
    else -> SignalWeakInk
}

private fun apCardColor(signal: Int): Color = when {
    signal >= -67 -> Color(0xFFC9F2DF)
    signal >= -70 -> Color(0xFFE4F5B0)
    signal >= -80 -> Color(0xFFFFF0AE)
    else -> Color(0xFFFFC9C9)
}

private fun signalBars(signal: Int): String = when {
    signal >= -67 -> "▮▮▮▮"
    signal >= -70 -> "▮▮▮▯"
    signal >= -80 -> "▮▮▯▯"
    else -> "▮▯▯▯"
}

private fun securityColor(security: String): Color {
    val value = security.lowercase()
    return when {
        "wpa" in value || "wep" in value -> SecureInk
        "open" in value || "none" in value -> OpenInk
        else -> Color(0xFF52606D)
    }
}

private fun isValidTargetMac(mac: String): Boolean {
    val value = mac.trim().lowercase()
    if (!Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$").matches(value)) return false
    val first = value.substring(0, 2).toInt(16)
    if (first and 0x01 != 0) return false
    if (value == "00:00:00:00:00:00") return false
    return true
}
