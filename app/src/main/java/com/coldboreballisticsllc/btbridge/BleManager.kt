// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val scope:   CoroutineScope,
    private val onEvent: (String) -> Unit,
) {
    private val btManager  = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter  = btManager.adapter
    private val bleScanner = btAdapter.bluetoothLeScanner

    private var gatt:        BluetoothGatt? = null
    private var nameFilter:  String?        = null
    private var scanTimeout: Job?           = null

    // Queue for serialising GATT operations (BLE is single-operation-at-a-time)
    private val opQueue: MutableSharedFlow<suspend () -> Unit> = MutableSharedFlow(extraBufferCapacity = 32)
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        scope.launch { opQueue.collect { it() } }
    }

    // ------------------------------------------------------------------
    // Scan
    // ------------------------------------------------------------------

    fun startScan(timeoutMs: Int, filter: String?) {
        nameFilter = filter
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanner.startScan(null, settings, scanCallback)
        emit(buildLog("info", "Scan started (filter=${filter ?: "none"}, timeout=${timeoutMs}ms)"))
        if (timeoutMs > 0) {
            scanTimeout?.cancel()
            scanTimeout = scope.launch {
                delay(timeoutMs.toLong())
                stopScan()
            }
        }
    }

    fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        try { bleScanner.stopScan(scanCallback) } catch (_: Exception) {}
        emit(buildLog("info", "Scan stopped"))
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name    = result.device.name ?: return
            if (nameFilter != null && !name.startsWith(nameFilter!!)) return
            emit(buildScanResult(address, name, result.rssi))
        }
        override fun onScanFailed(errorCode: Int) {
            emit(buildError("scan_failed", "Scanner error code $errorCode"))
        }
    }

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    fun connect(address: String) {
        val device = btAdapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        emit(buildLog("info", "Connecting to $address"))
    }

    fun disconnect(address: String) {
        gatt?.disconnect()
        emit(buildLog("info", "Disconnect requested for $address"))
    }

    fun discoverServices(address: String) {
        val g = gatt ?: run {
            emit(buildError("gatt_error", "No active GATT connection"))
            return
        }
        g.discoverServices()
    }

    // ------------------------------------------------------------------
    // Subscribe / unsubscribe
    // ------------------------------------------------------------------

    fun subscribe(address: String, charUuid: String) {
        scope.launch { opQueue.emit { doSubscribe(address, charUuid, enable = true) } }
    }

    fun unsubscribe(address: String, charUuid: String) {
        scope.launch { opQueue.emit { doSubscribe(address, charUuid, enable = false) } }
    }

    private fun doSubscribe(address: String, charUuid: String, enable: Boolean) {
        val g    = gatt ?: return
        val char = findChar(g, charUuid) ?: run {
            emit(buildError("gatt_error", "Characteristic $charUuid not found"))
            return
        }
        g.setCharacteristicNotification(char, enable)
        val cccd = char.getDescriptor(CCCD_UUID) ?: return
        val value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        emit(buildLog("info", "${if (enable) "Subscribed" else "Unsubscribed"} to $charUuid"))
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    fun read(address: String, charUuid: String, reqId: String) {
        scope.launch {
            opQueue.emit {
                val g    = gatt ?: return@emit
                val char = findChar(g, charUuid) ?: run {
                    emit(buildError("gatt_error", "Characteristic $charUuid not found"))
                    return@emit
                }
                pendingReadReqId = reqId
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.readCharacteristic(char)
                } else {
                    @Suppress("DEPRECATION")
                    g.readCharacteristic(char)
                }
            }
        }
    }

    private var pendingReadReqId: String = ""

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    fun write(address: String, charUuid: String, value: ByteArray, withResponse: Boolean, reqId: String) {
        scope.launch {
            opQueue.emit {
                val g    = gatt ?: return@emit
                val char = findChar(g, charUuid) ?: run {
                    emit(buildError("gatt_error", "Characteristic $charUuid not found"))
                    return@emit
                }
                pendingWriteReqId = reqId
                val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                else              BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(char, value, writeType)
                } else {
                    @Suppress("DEPRECATION")
                    char.writeType = writeType
                    @Suppress("DEPRECATION")
                    char.value = value
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(char)
                }
            }
        }
    }

    private var pendingWriteReqId: String = ""

    // ------------------------------------------------------------------
    // GATT callback
    // ------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    emit(buildConnected(gatt.device.address))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    emit(buildDisconnected(gatt.device.address, status))
                    gatt.close()
                    this@BleManager.gatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit(buildError("gatt_error", "Service discovery failed status=$status"))
                return
            }
            val services = gatt.services.map { svc ->
                ServiceDescriptor(
                    uuid  = svc.uuid.toString(),
                    chars = svc.characteristics.map { ch ->
                        CharDescriptor(
                            uuid  = ch.uuid.toString(),
                            props = buildCharProps(ch.properties),
                        )
                    },
                )
            }
            emit(buildServicesDiscovered(gatt.device.address, services))
        }

        override fun onCharacteristicChanged(
            gatt:           BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value:          ByteArray,
        ) {
            emit(buildNotification(gatt.device.address, characteristic.uuid.toString(), value))
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt:           BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (android.os.Build.VERSION.SDK_INT < 33) {
                emit(buildNotification(gatt.device.address, characteristic.uuid.toString(), characteristic.value ?: byteArrayOf()))
            }
        }

        override fun onCharacteristicRead(
            gatt:           BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value:          ByteArray,
            status:         Int,
        ) {
            emit(buildReadResult(gatt.device.address, characteristic.uuid.toString(), value, status, pendingReadReqId))
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt:           BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status:         Int,
        ) {
            if (android.os.Build.VERSION.SDK_INT < 33) {
                emit(buildReadResult(gatt.device.address, characteristic.uuid.toString(), characteristic.value ?: byteArrayOf(), status, pendingReadReqId))
            }
        }

        override fun onCharacteristicWrite(
            gatt:           BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status:         Int,
        ) {
            emit(buildWriteResult(gatt.device.address, characteristic.uuid.toString(), status, pendingWriteReqId))
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun findChar(gatt: BluetoothGatt, uuidStr: String): BluetoothGattCharacteristic? {
        val uuid = UUID.fromString(uuidStr)
        return gatt.services.flatMap { it.characteristics }.firstOrNull { it.uuid == uuid }
    }

    private fun buildCharProps(props: Int): List<String> = buildList {
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0)             add("read")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)            add("write")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write_no_response")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)           add("notify")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)         add("indicate")
    }

    private fun emit(msg: String) {
        onEvent(msg)
    }

    fun close() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
