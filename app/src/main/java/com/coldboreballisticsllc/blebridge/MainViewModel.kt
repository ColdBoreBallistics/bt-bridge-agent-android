// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.blebridge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppState(
    val serverHost:      String  = "192.168.1.1",
    val serverPort:      String  = "9876",
    val serverConnected: Boolean = false,
    val bleDevice:       String? = null,
    val log:             List<String> = emptyList(),
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
            } catch (e: Exception) {
                addLog("Connection failed: ${e.message}")
            }
        }
    }

    fun disconnectServer() {
        viewModelScope.launch { tcpClient.disconnect() }
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
            is BleCommand.Ping         -> tcpClient.send(buildPong())
            is BleCommand.Unknown      -> addLog("Unknown command: ${cmd.raw}")
        }
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
