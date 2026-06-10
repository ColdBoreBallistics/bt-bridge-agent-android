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
import kotlinx.serialization.json.jsonPrimitive

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
    val serverPort:       String               = "9876",
    val serverConnected:  Boolean              = false,
    val bleDevice:        String?              = null,
    val isScanning:       Boolean              = false,
    val scanResults:      List<ScanDevice>     = emptyList(),
    val weatherFlow:      WeatherFlowReading?  = null,
    val log:              List<String>         = emptyList(),
    val ipHistory:        List<String>         = emptyList(),
    val pendingQuestions: List<ServerQuestion> = emptyList(),
)

private const val MAX_LOG = 500

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val tcpClient = TcpClient(viewModelScope)

    private val bleManager = BleManager(
        context = app,
        scope   = viewModelScope,
        onEvent = { msg ->
            addLog("←BLE $msg")
            tcpClient.send(msg)
            tryParseWeatherFlow(msg)
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
                    val port = _state.value.serverPort.trim().toIntOrNull() ?: 9876
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
        val port = _state.value.serverPort.trim().toIntOrNull() ?: 9876
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
            // Template-protocol commands are wired up in Task 6; no-op for now.
            is BleCommand.PushTemplates -> addLog("push_templates (${cmd.manifest.size}) — not yet handled")
            is BleCommand.TemplateData  -> addLog("template_data ${cmd.id}@${cmd.version} — not yet handled")
            is BleCommand.ApplyTemplate -> addLog("apply_template ${cmd.deviceTemplateId}@${cmd.version} — not yet handled")
            is BleCommand.SetView       -> addLog("set_view ${cmd.view} — not yet handled")
            is BleCommand.Unknown      -> addLog("Unknown command: ${cmd.raw}")
        }
    }

    // ------------------------------------------------------------------
    // WeatherFlow live parsing
    // ------------------------------------------------------------------

    private fun tryParseWeatherFlow(msg: String) {
        try {
            val obj = BridgeJson.decodeFromString<JsonObject>(msg)
            if (obj["event"]?.jsonPrimitive?.content != "notification") return
            val char = obj["char"]?.jsonPrimitive?.content ?: return
            if (!char.startsWith("961f0005")) return
            val hex = obj["value"]?.jsonPrimitive?.content ?: return
            val reading = parseWeatherFlowFrame(hex.hexToBytes()) ?: return
            _state.update { it.copy(weatherFlow = reading) }
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
            _state.update { it.copy(bleDevice = null, weatherFlow = null) }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
