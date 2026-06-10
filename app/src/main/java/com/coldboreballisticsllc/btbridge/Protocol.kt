// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ── Serialization config ──────────────────────────────────────────────────────

val BridgeJson = Json {
    ignoreUnknownKeys   = true
    encodeDefaults      = false
    isLenient           = true
}

// ── Helper ────────────────────────────────────────────────────────────────────

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "")
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
fun nowMs(): Long = System.currentTimeMillis()

// ── Inbound commands (Server → Mobile) ───────────────────────────────────────

data class TemplateManifestEntry(val id: String, val version: String)

sealed class BleCommand {
    data class ScanStart(val timeoutMs: Int = 10_000, val nameFilter: String? = null) : BleCommand()
    data object ScanStop : BleCommand()
    data class Connect(val address: String) : BleCommand()
    data class Disconnect(val address: String) : BleCommand()
    data class Discover(val address: String) : BleCommand()
    data class Subscribe(val address: String, val char: String) : BleCommand()
    data class Unsubscribe(val address: String, val char: String) : BleCommand()
    data class Read(val address: String, val char: String, val reqId: String) : BleCommand()
    data class Write(val address: String, val char: String, val value: ByteArray, val rsp: Boolean, val reqId: String) : BleCommand()
    data class AskQuestion(val question: ServerQuestion) : BleCommand()
    data object DismissQuestion : BleCommand()
    data object Ping : BleCommand()
    data class PushTemplates(val manifest: List<TemplateManifestEntry>) : BleCommand()
    data class TemplateData(val id: String, val version: String, val content: String) : BleCommand()
    data class ApplyTemplate(val address: String, val deviceTemplateId: String, val version: String, val variantId: String?) : BleCommand()
    data class SetView(val address: String, val view: String) : BleCommand()
    data class Unknown(val raw: String) : BleCommand()
}

data class ServerQuestion(
    val reqId:    String,
    val question: String,
)

fun parseBleCommand(line: String): BleCommand {
    return try {
        val obj = BridgeJson.decodeFromString<JsonObject>(line)
        when (val cmd = obj["cmd"]?.jsonPrimitive?.content) {
            "scan_start"  -> BleCommand.ScanStart(
                timeoutMs  = obj["timeout_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10_000,
                nameFilter = obj["name_filter"]?.jsonPrimitive?.content,
            )
            "scan_stop"   -> BleCommand.ScanStop
            "connect"     -> BleCommand.Connect(obj["address"]!!.jsonPrimitive.content)
            "disconnect"  -> BleCommand.Disconnect(obj["address"]!!.jsonPrimitive.content)
            "discover"    -> BleCommand.Discover(obj["address"]!!.jsonPrimitive.content)
            "subscribe"   -> BleCommand.Subscribe(
                address = obj["address"]!!.jsonPrimitive.content,
                char    = obj["char"]!!.jsonPrimitive.content,
            )
            "unsubscribe" -> BleCommand.Unsubscribe(
                address = obj["address"]!!.jsonPrimitive.content,
                char    = obj["char"]!!.jsonPrimitive.content,
            )
            "read"        -> BleCommand.Read(
                address = obj["address"]!!.jsonPrimitive.content,
                char    = obj["char"]!!.jsonPrimitive.content,
                reqId   = obj["req_id"]!!.jsonPrimitive.content,
            )
            "write"       -> BleCommand.Write(
                address = obj["address"]!!.jsonPrimitive.content,
                char    = obj["char"]!!.jsonPrimitive.content,
                value   = obj["value"]!!.jsonPrimitive.content.hexToBytes(),
                rsp     = obj["rsp"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                reqId   = obj["req_id"]!!.jsonPrimitive.content,
            )
            "ping"        -> BleCommand.Ping
            "ask"          -> BleCommand.AskQuestion(
                ServerQuestion(
                    reqId    = obj["req_id"]!!.jsonPrimitive.content,
                    question = obj["question"]!!.jsonPrimitive.content,
                )
            )
            "dismiss_all"  -> BleCommand.DismissQuestion
            "push_templates" -> {
                val entries = buildList {
                    obj["manifest"]?.jsonArray?.forEach { el ->
                        val o = el.jsonObject
                        add(TemplateManifestEntry(
                            id      = o["id"]?.jsonPrimitive?.content ?: "",
                            version = o["version"]?.jsonPrimitive?.content ?: "",
                        ))
                    }
                }
                BleCommand.PushTemplates(entries)
            }
            "template_data" -> BleCommand.TemplateData(
                id      = obj["id"]?.jsonPrimitive?.content ?: "",
                version = obj["version"]?.jsonPrimitive?.content ?: "",
                // Broker sends `content` as a JSON object (full template); the agent needs it
                // as a string to store/parse downstream with org.json. Serialize it back.
                content = obj["content"]?.jsonObject?.toString()
                    ?: obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: "",
            )
            "apply_template" -> BleCommand.ApplyTemplate(
                address          = obj["address"]?.jsonPrimitive?.content ?: "",
                deviceTemplateId = obj["device_template_id"]?.jsonPrimitive?.content ?: "",
                version          = obj["version"]?.jsonPrimitive?.content ?: "",
                variantId        = obj["variant_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
            )
            "set_view" -> BleCommand.SetView(
                address = obj["address"]?.jsonPrimitive?.content ?: "",
                view    = obj["view"]?.jsonPrimitive?.content ?: "",
            )
            else          -> BleCommand.Unknown(line)
        }
    } catch (e: Exception) {
        BleCommand.Unknown(line)
    }
}

// ── Outbound events (Mobile → Server) ────────────────────────────────────────

@Serializable
data class CharDescriptor(
    val uuid:  String,
    val props: List<String>,
)

@Serializable
data class ServiceDescriptor(
    val uuid:  String,
    val chars: List<CharDescriptor>,
)

fun buildScanResult(address: String, name: String, rssi: Int): String =
    """{"event":"scan_result","address":"$address","name":"$name","rssi":$rssi,"ts":${nowMs()}}"""

fun buildConnected(address: String): String =
    """{"event":"connected","address":"$address","ts":${nowMs()}}"""

fun buildDisconnected(address: String, code: Int): String =
    """{"event":"disconnected","address":"$address","code":$code,"ts":${nowMs()}}"""

fun buildServicesDiscovered(address: String, services: List<ServiceDescriptor>): String {
    val svcJson = services.joinToString(",") { svc ->
        val charsJson = svc.chars.joinToString(",") { ch ->
            val propsJson = ch.props.joinToString(",") { "\"$it\"" }
            """{"uuid":"${ch.uuid}","props":[$propsJson]}"""
        }
        """{"uuid":"${svc.uuid}","chars":[$charsJson]}"""
    }
    return """{"event":"services_discovered","address":"$address","services":[$svcJson],"ts":${nowMs()}}"""
}

fun buildNotification(address: String, char: String, value: ByteArray): String =
    """{"event":"notification","address":"$address","char":"$char","value":"${value.toHex()}","ts":${nowMs()}}"""

fun buildReadResult(address: String, char: String, value: ByteArray, status: Int, reqId: String): String =
    """{"event":"read_result","address":"$address","char":"$char","value":"${value.toHex()}","status":$status,"req_id":"$reqId","ts":${nowMs()}}"""

fun buildWriteResult(address: String, char: String, status: Int, reqId: String): String =
    """{"event":"write_result","address":"$address","char":"$char","status":$status,"req_id":"$reqId","ts":${nowMs()}}"""

fun buildError(code: String, message: String): String =
    """{"event":"error","code":"$code","message":${BridgeJson.encodeToString(kotlinx.serialization.json.JsonPrimitive(message))},"ts":${nowMs()}}"""

fun buildPong(): String =
    """{"event":"pong","ts":${nowMs()}}"""

fun buildLog(level: String, message: String): String =
    """{"event":"log","level":"$level","message":${BridgeJson.encodeToString(kotlinx.serialization.json.JsonPrimitive(message))},"ts":${nowMs()}}"""

fun buildAnswer(reqId: String, value: Boolean): String =
    """{"event":"answer","req_id":"$reqId","value":$value,"ts":${nowMs()}}"""

fun buildDismiss(reqId: String): String =
    """{"event":"dismiss","req_id":"$reqId","ts":${nowMs()}}"""

// ── Template-protocol events (Mobile → Server) ───────────────────────────────

fun buildTemplateRequest(ids: List<TemplateManifestEntry>): String {
    val idsJson = ids.joinToString(",") { """{"id":"${it.id}","version":"${it.version}"}""" }
    return """{"event":"template_request","ids":[$idsJson],"ts":${nowMs()}}"""
}

fun buildTemplateApplied(address: String, deviceTemplateId: String, version: String, variantId: String?): String {
    val variantPart = if (variantId != null) ""","variant_id":"$variantId"""" else ""","variant_id":null"""
    return """{"event":"template_applied","address":"$address","device_template_id":"$deviceTemplateId","version":"$version"$variantPart,"ts":${nowMs()}}"""
}

fun buildViewChanged(address: String, view: String): String =
    """{"event":"view_changed","address":"$address","view":"$view","ts":${nowMs()}}"""

fun buildHello(platform: String, capabilities: List<String>, bleEnabled: Boolean): String {
    val caps = capabilities.joinToString(",") { "\"$it\"" }
    return """{"event":"hello","platform":"$platform","capabilities":[$caps],"ble_enabled":$bleEnabled,"ts":${nowMs()}}"""
}

// ── WeatherFlow Tactical live-data parser ─────────────────────────────────────
// Notify char 961f0005 — 16-byte LE frame, ~1 Hz
// off0  u16  wind speed raw  (/ 1024 = mph)
// off8  s16  temperature     (× 0.1 °C)
// off10 u8   humidity        (%)
// off12 u16  pressure        (× 0.1 hPa)

const val WF_NOTIFY_CHAR = "961f0005-d2d6-43e3-a417-3bb8217e0e01"

data class WeatherFlowReading(
    val windSpeedMph: Float,
    val tempC:        Float,
    val humidityPct:  Int,
    val pressureHpa:  Float,
)

fun parseWeatherFlowFrame(bytes: ByteArray): WeatherFlowReading? {
    if (bytes.size < 14) return null
    fun u16(off: Int) = ((bytes[off + 1].toInt() and 0xFF) shl 8) or (bytes[off].toInt() and 0xFF)
    fun s16(off: Int) = u16(off).let { if (it >= 0x8000) it - 0x10000 else it }
    fun u8(off: Int)  = bytes[off].toInt() and 0xFF
    return WeatherFlowReading(
        windSpeedMph = u16(0) / 1024f,
        tempC        = s16(8) * 0.1f,
        humidityPct  = u8(10),
        pressureHpa  = u16(12) * 0.1f,
    )
}
