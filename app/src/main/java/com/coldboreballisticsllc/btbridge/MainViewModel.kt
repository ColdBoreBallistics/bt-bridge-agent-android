// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import com.coldboreballisticsllc.btbridge.template.RenderedFrame
import com.coldboreballisticsllc.btbridge.template.TemplateStore

private const val SCAN_EXPIRY_MS = 15_000L
private const val SCAN_PRUNE_INTERVAL_MS = 2_000L

data class ScanDevice(
    val address:    String,
    val name:       String,
    val rssi:       Int,
    val lastSeenMs: Long = System.currentTimeMillis(),
)

data class AppState(
    val serverHost:       String               = "192.168.1.1",
    val serverPort:       String               = "2653",
    val serverConnected:  Boolean              = false,
    val bleDevice:        String?              = null,
    val isScanning:       Boolean              = false,
    val scanResults:      List<ScanDevice>     = emptyList(),
    val renderedFrame:    RenderedFrame?       = null,
    val gattAnalyserMode: Boolean              = false,
    val activeView:       String               = "raw",
    val availableViews:   List<String>         = listOf("raw"),
    val templateWarnings: List<String>         = emptyList(),
    val log:              List<String>         = emptyList(),
    val ipHistory:        List<String>         = emptyList(),
    val pendingQuestions: List<ServerQuestion> = emptyList(),
)

private const val MAX_LOG = 500

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val tcpClient = TcpClient(viewModelScope)

    private val templateStore = TemplateStore(app)
    @Volatile private var activeRenderer: com.coldboreballisticsllc.btbridge.template.TemplateRenderer? = null
    private val activeViewPerDevice = mutableMapOf<String, String>()

    private val bleManager = BleManager(
        context = app,
        scope   = viewModelScope,
        onEvent = { msg ->
            addLog("←BLE $msg")
            forwardToServer(msg)
            tryParseNotification(msg)
            tryParseScanResult(msg)
            tryParseConnected(msg)
            tryParseDisconnected(msg)
            tryParseServicesDiscovered(msg)
        },
    )

    init {
        viewModelScope.launch {
            tcpClient.connectionState.collect { connected ->
                _state.update { it.copy(serverConnected = connected) }
                addLog(if (connected) "TCP connected to server" else "TCP disconnected")
                if (connected) {
                    tcpClient.send(buildHello(
                        platform = "android",
                        capabilities = listOf("push_templates", "apply_template", "set_view"),
                        bleEnabled = true,
                    ))
                }
            }
        }
        viewModelScope.launch {
            tcpClient.commands.collect { cmd -> handleCommand(cmd) }
        }
        viewModelScope.launch {
            val history = Prefs.loadHosts(app)
            if (history.isNotEmpty()) {
                _state.update { it.copy(ipHistory = history, serverHost = history.first()) }
                autoConnect()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(SCAN_PRUNE_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - SCAN_EXPIRY_MS
                _state.update { s ->
                    val pruned = s.scanResults.filter { it.lastSeenMs >= cutoff }
                    if (pruned.size == s.scanResults.size) s else s.copy(scanResults = pruned)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Auto-connect
    // ------------------------------------------------------------------

    private fun autoConnect() {
        viewModelScope.launch(Dispatchers.IO) {
            while (!tcpClient.isConnected) {
                try {
                    val host = _state.value.serverHost.trim()
                    val port = _state.value.serverPort.trim().toIntOrNull() ?: 2653
                    addLog("Auto-connecting to $host:$port …")
                    tcpClient.connect(host, port)
                    return@launch
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(3_000)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // UI actions
    // ------------------------------------------------------------------

    fun updateHost(v: String) = _state.update { it.copy(serverHost = v) }
    fun updatePort(v: String) = _state.update { it.copy(serverPort = v) }

    fun connectServer() {
        val host = _state.value.serverHost.trim()
        val port = _state.value.serverPort.trim().toIntOrNull() ?: 2653
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("Connecting to $host:$port …")
                tcpClient.connect(host, port)
                Prefs.saveHost(getApplication(), host)
                val history = Prefs.loadHosts(getApplication())
                _state.update { it.copy(ipHistory = history) }
            } catch (e: Exception) {
                addLog("Connection failed: ${e.message}")
            }
        }
    }

    fun disconnectServer() {
        viewModelScope.launch { tcpClient.disconnect() }
    }

    fun updateHostFromHistory(host: String) {
        _state.update { it.copy(serverHost = host) }
        if (!tcpClient.isConnected) autoConnect()
    }

    fun disconnectBle() {
        val address = _state.value.bleDevice ?: return
        bleManager.disconnect(address)
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    fun startLocalScan() {
        _state.update { it.copy(isScanning = true, scanResults = emptyList()) }
        bleManager.startScan(timeoutMs = 15_000, filter = null)
        viewModelScope.launch {
            kotlinx.coroutines.delay(15_000)
            if (_state.value.isScanning) stopLocalScan()
        }
    }

    fun stopLocalScan() {
        bleManager.stopScan()
        _state.update { it.copy(isScanning = false) }
    }

    fun connectDevice(device: ScanDevice) {
        stopLocalScan()
        _state.update { it.copy(bleDevice = device.address, scanResults = emptyList()) }
        bleManager.connect(device.address)
    }

    fun answerQuestion(reqId: String, value: Boolean) {
        tcpClient.send(buildAnswer(reqId, value))
        _state.update { it.copy(pendingQuestions = it.pendingQuestions.filter { q -> q.reqId != reqId }) }
    }

    fun dismissQuestion(reqId: String) {
        tcpClient.send(buildDismiss(reqId))
        _state.update { it.copy(pendingQuestions = it.pendingQuestions.filter { q -> q.reqId != reqId }) }
    }

    // ------------------------------------------------------------------
    // Command dispatch
    // ------------------------------------------------------------------

    private fun handleCommand(cmd: BleCommand) {
        addLog("→SRV ${cmd::class.simpleName}")
        when (cmd) {
            is BleCommand.ScanStart    -> bleManager.startScan(cmd.timeoutMs, cmd.nameFilter)
            is BleCommand.ScanStop     -> bleManager.stopScan()
            is BleCommand.Connect      -> { _state.update { it.copy(bleDevice = cmd.address) }; bleManager.connect(cmd.address) }
            is BleCommand.Disconnect   -> { bleManager.disconnect(cmd.address); _state.update { it.copy(bleDevice = null) } }
            is BleCommand.Discover     -> bleManager.discoverServices(cmd.address)
            is BleCommand.Subscribe    -> bleManager.subscribe(cmd.address, cmd.char)
            is BleCommand.Unsubscribe  -> bleManager.unsubscribe(cmd.address, cmd.char)
            is BleCommand.Read         -> bleManager.read(cmd.address, cmd.char, cmd.reqId)
            is BleCommand.Write        -> bleManager.write(cmd.address, cmd.char, cmd.value, cmd.rsp, cmd.reqId)
            is BleCommand.AskQuestion     -> _state.update { it.copy(
                pendingQuestions = it.pendingQuestions + cmd.question
            )}
            is BleCommand.DismissQuestion -> _state.update { it.copy(pendingQuestions = emptyList()) }
            is BleCommand.Ping         -> tcpClient.send(buildPong())
            is BleCommand.PushTemplates -> handlePushTemplates(cmd)
            is BleCommand.TemplateData  -> handleTemplateData(cmd)
            is BleCommand.ApplyTemplate -> handleApplyTemplate(cmd)
            is BleCommand.SetView       -> handleSetView(cmd)
            is BleCommand.Unknown      -> addLog("Unknown command: ${cmd.raw}")
        }
    }

    // ------------------------------------------------------------------
    // Template-protocol handlers
    // ------------------------------------------------------------------

    private fun handlePushTemplates(cmd: BleCommand.PushTemplates) {
        // Request any template we don't already have cached (by id+version).
        val local = templateStore.localVersions().map { it.id to it.version }.toSet()
        val toRequest = cmd.manifest.filter { (it.id to it.version) !in local }
        if (toRequest.isNotEmpty()) {
            tcpClient.send(buildTemplateRequest(toRequest))
            addLog("→SRV template_request: ${toRequest.size} template(s)")
        }
    }

    private fun handleTemplateData(cmd: BleCommand.TemplateData) {
        try {
            val obj = org.json.JSONObject(cmd.content)
            templateStore.save(obj)
            addLog("←SRV template_data ${cmd.id}@${cmd.version} saved")
        } catch (e: Exception) {
            addLog("template_data parse error: ${e.message}")
        }
    }

    private fun handleApplyTemplate(cmd: BleCommand.ApplyTemplate) {
        val deviceTemplate = templateStore.get(cmd.deviceTemplateId, cmd.version)
        if (deviceTemplate == null) {
            addLog("apply_template: ${cmd.deviceTemplateId}@${cmd.version} not cached — GATT analyser")
            _state.update { it.copy(gattAnalyserMode = true,
                templateWarnings = listOf("No device template — showing raw GATT data")) }
            return
        }
        // Resolve the referenced display template (device template references a display).
        // Spec'd field is references.display (catalog builtins use it); display_template is
        // a legacy fallback. Primary first, then fallback.
        val displayRef = deviceTemplate.optJSONObject("references")?.optString("display")
            ?: deviceTemplate.optString("display_template").takeIf { it.isNotEmpty() }
        val displayTemplate = displayRef?.let { ref ->
            val (id, ver) = parseTemplateRef(ref)
            // Resolve version: exact if given, else the highest local version of that id.
            // NOTE: lexicographic version selection — correct for single-version builtins.
            // The broker does authoritative semver resolution; revisit if multi-version
            // display templates ship (e.g. "1.10.0" vs "1.9.0" would mis-order here).
            val resolvedVer = ver ?: templateStore.localVersions()
                .filter { it.id == id }
                .maxByOrNull { it.version }?.version
            if (resolvedVer != null) templateStore.get(id, resolvedVer) else null
        }
        if (displayTemplate == null) {
            addLog("apply_template: display template not found for ${cmd.deviceTemplateId} — GATT analyser")
            _state.update { it.copy(gattAnalyserMode = true,
                templateWarnings = listOf("No display template — showing raw GATT data")) }
            return
        }
        activeRenderer = com.coldboreballisticsllc.btbridge.template.TemplateRenderer(displayTemplate)
        val defaultView = displayTemplate.optString("default_view", "raw")
        val views = extractViews(displayTemplate)
        val device = cmd.address
        val view = activeViewPerDevice[device] ?: defaultView
        activeViewPerDevice[device] = view
        _state.update { it.copy(
            gattAnalyserMode = false,
            activeView = view,
            availableViews = views,
            templateWarnings = emptyList(),
        )}
        tcpClient.send(buildTemplateApplied(cmd.address, cmd.deviceTemplateId, cmd.version, cmd.variantId))
        addLog("Template applied: ${cmd.deviceTemplateId}@${cmd.version} variant=${cmd.variantId}")
    }

    private fun handleSetView(cmd: BleCommand.SetView) {
        activeViewPerDevice[cmd.address] = cmd.view
        _state.update { it.copy(activeView = cmd.view) }
        addLog("View set to ${cmd.view} for ${cmd.address}")
    }

    fun selectView(address: String, view: String) {
        activeViewPerDevice[address] = view
        _state.update { it.copy(activeView = view) }
        tcpClient.send(buildViewChanged(address, view))
    }

    private fun parseTemplateRef(ref: String): Pair<String, String?> {
        // Format: "builtin.foo@^1.0.0" or "builtin.foo@1.0.0" or "builtin.foo"
        val at = ref.indexOf('@')
        return if (at >= 0) {
            val id = ref.substring(0, at)
            val verSpec = ref.substring(at + 1).trimStart('^', '~', '>', '=', '<', ' ')
            id to verSpec.takeIf { it.isNotEmpty() }
        } else ref to null
    }

    private fun extractViews(displayTemplate: org.json.JSONObject): List<String> {
        val notifs = displayTemplate.optJSONArray("notifications") ?: return listOf("raw")
        if (notifs.length() == 0) return listOf("raw")
        val views = notifs.getJSONObject(0).optJSONObject("views") ?: return listOf("raw")
        return views.keys().asSequence().toList()
    }

    // ------------------------------------------------------------------
    // Notification rendering (template-aware)
    // ------------------------------------------------------------------

    private fun tryParseNotification(msg: String) {
        try {
            val obj = BridgeJson.decodeFromString<JsonObject>(msg)
            if (obj["event"]?.jsonPrimitive?.content != "notification") return
            val char = obj["char"]?.jsonPrimitive?.content ?: return
            val hex = obj["value"]?.jsonPrimitive?.content ?: return
            val bytes = hex.hexToBytes()
            val address = obj["address"]?.jsonPrimitive?.content ?: ""
            // Render + shared-state access on Main to avoid racing the command handlers
            // (this method is invoked on the BLE callback thread).
            viewModelScope.launch {
                val view = activeViewPerDevice[address] ?: _state.value.activeView
                val renderer = activeRenderer
                val frame = if (_state.value.gattAnalyserMode || renderer == null) {
                    com.coldboreballisticsllc.btbridge.template.GattAnalyser.render(char, bytes)
                } else {
                    renderer.render(char, bytes, view)
                        ?: com.coldboreballisticsllc.btbridge.template.GattAnalyser.render(char, bytes)
                }
                _state.update { it.copy(renderedFrame = frame) }
            }
        } catch (_: Exception) {}
    }

    private fun tryParseScanResult(msg: String) {
        try {
            val obj = BridgeJson.decodeFromString<JsonObject>(msg)
            if (obj["event"]?.jsonPrimitive?.content != "scan_result") return
            val address = obj["address"]?.jsonPrimitive?.content ?: return
            val name    = obj["name"]?.jsonPrimitive?.content ?: return
            val rssi    = obj["rssi"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val device  = ScanDevice(address, name, rssi, System.currentTimeMillis())
            _state.update { s ->
                val existing = s.scanResults.indexOfFirst { it.address == address }
                val updated  = if (existing >= 0) s.scanResults.toMutableList().also { it[existing] = device }
                               else s.scanResults + device
                s.copy(scanResults = updated.sortedBy { it.name.lowercase() })
            }
        } catch (_: Exception) {}
    }

    private fun tryParseConnected(msg: String) {
        try {
            val obj = BridgeJson.decodeFromString<JsonObject>(msg)
            if (obj["event"]?.jsonPrimitive?.content != "connected") return
            val address = obj["address"]?.jsonPrimitive?.content ?: return
            _state.update { it.copy(bleDevice = address) }
            // Kick off service discovery so we can auto-subscribe to WeatherFlow notify char
            bleManager.discoverServices(address)
        } catch (_: Exception) {}
    }

    private fun tryParseServicesDiscovered(msg: String) {
        try {
            val obj = BridgeJson.decodeFromString<JsonObject>(msg)
            if (obj["event"]?.jsonPrimitive?.content != "services_discovered") return
            val address = obj["address"]?.jsonPrimitive?.content ?: return
            // If the WeatherFlow notify char is present, subscribe automatically
            val services = obj["services"]?.let {
                BridgeJson.decodeFromString<List<ServiceDescriptor>>(it.toString())
            } ?: return
            val hasWf = services.any { svc -> svc.chars.any { ch -> ch.uuid.startsWith("961f0005") } }
            if (hasWf) bleManager.subscribe(address, WF_NOTIFY_CHAR)
        } catch (_: Exception) {}
    }

    private fun tryParseDisconnected(msg: String) {
        try {
            val obj = BridgeJson.decodeFromString<JsonObject>(msg)
            if (obj["event"]?.jsonPrimitive?.content != "disconnected") return
            // Clear renderer + per-device view state so a subsequent (possibly untemplated)
            // device cannot render through the previous device's stale template. Single-device
            // agent (one bleDevice in AppState), so clearing the whole map is correct.
            activeRenderer = null
            activeViewPerDevice.clear()
            _state.update { it.copy(
                bleDevice = null,
                renderedFrame = null,
                gattAnalyserMode = false,
                activeView = "raw",
                availableViews = listOf("raw"),
                templateWarnings = emptyList(),
            ) }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun forwardToServer(msg: String) {
        // Enrich services_discovered with the advertised device name so the broker can
        // match device templates whose signature requires a name_prefix.
        val enriched = try {
            val obj = BridgeJson.decodeFromString<JsonObject>(msg)
            if (obj["event"]?.jsonPrimitive?.content == "services_discovered"
                && obj["name"] == null) {
                val address = obj["address"]?.jsonPrimitive?.content
                val name = _state.value.scanResults.firstOrNull { it.address == address }?.name
                if (name != null) {
                    // Inject "name":"<name>" into the JSON object.
                    msg.replaceFirst("{", """{"name":${JsonPrimitive(name)},""")
                } else msg
            } else msg
        } catch (_: Exception) { msg }
        tcpClient.send(enriched)
    }

    private fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        _state.update { s ->
            val newLog = (s.log + "[$ts] $msg").takeLast(MAX_LOG)
            s.copy(log = newLog)
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.close()
        viewModelScope.launch { tcpClient.disconnect() }
    }
}
