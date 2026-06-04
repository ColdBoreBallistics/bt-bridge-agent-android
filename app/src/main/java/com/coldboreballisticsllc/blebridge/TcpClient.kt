// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.blebridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class TcpClient(private val scope: CoroutineScope) {

    private val _commands = MutableSharedFlow<BleCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<BleCommand> = _commands.asSharedFlow()

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
    val connectionState: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    private var socket: Socket?      = null
    private var writer: PrintWriter? = null
    private var readJob: Job?        = null

    suspend fun connect(host: String, port: Int) {
        disconnect()
        withContext(Dispatchers.IO) {
            val sock = Socket(host, port)
            socket = sock
            writer = PrintWriter(sock.getOutputStream(), true)
            _connectionState.emit(true)
            readJob = scope.launch(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                try {
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        val cmd = parseBleCommand(line)
                        _commands.emit(cmd)
                    }
                } catch (_: Exception) {
                } finally {
                    _connectionState.emit(false)
                }
            }
        }
    }

    suspend fun disconnect() {
        readJob?.cancelAndJoin()
        readJob = null
        withContext(Dispatchers.IO) {
            try { writer?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
        writer = null
        socket = null
        _connectionState.emit(false)
    }

    fun send(message: String) {
        writer?.println(message)
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false
}
