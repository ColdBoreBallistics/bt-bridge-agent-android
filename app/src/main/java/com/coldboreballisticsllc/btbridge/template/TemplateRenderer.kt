// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge.template

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/**
 * Renders a raw BLE notification byte array into a [RenderedFrame] using a display template.
 *
 * The template must be a display-type template JSON object as defined in PROTOCOL.md v1.2
 * and the template system design spec. One renderer instance per display template.
 */
class TemplateRenderer(private val template: JSONObject) {

    /**
     * Render [bytes] for characteristic [charUuid] using [view].
     * Returns null if no notification entry matches [charUuid].
     */
    fun render(charUuid: String, bytes: ByteArray, view: String): RenderedFrame? {
        val notifications = template.optJSONArray("notifications") ?: return null
        for (i in 0 until notifications.length()) {
            val notif = notifications.getJSONObject(i)
            if (!charUuid.startsWith(notif.optString("char"), ignoreCase = true)) continue
            // Check match block if present
            val match = notif.optJSONObject("match")
            if (match != null && !matchesFrame(bytes, match)) continue

            val views = notif.optJSONObject("views") ?: continue
            val viewDef = views.optJSONObject(view) ?: views.optJSONObject("raw") ?: continue
            return renderView(charUuid, view, bytes, viewDef)
        }
        return null
    }

    private fun matchesFrame(bytes: ByteArray, match: JSONObject): Boolean {
        val cmdOffset = match.optInt("cmd_byte_offset", -1)
        val cmdValue = match.optString("cmd_byte_value", "")
        if (cmdOffset >= 0 && cmdOffset < bytes.size && cmdValue.isNotEmpty()) {
            val expected = cmdValue.removePrefix("0x").toIntOrNull(16) ?: return false
            return bytes[cmdOffset].toInt() and 0xFF == expected
        }
        return true
    }

    private fun renderView(
        charUuid: String,
        view: String,
        bytes: ByteArray,
        viewDef: JSONObject,
    ): RenderedFrame {
        val fieldsArray = viewDef.optJSONArray("fields") ?: JSONArray()
        val warnings = mutableListOf<FieldWarning>()

        // First pass: parse all fields (including display=false for expr inputs).
        // parsedValues holds the raw extracted numeric value (used by raw/bitmask/enum).
        // exprValues holds the semantic value that expr/formula inputs consume — for a
        // scale_offset field that is raw * scale + offset_value, so downstream expressions
        // operate on real-world units (e.g. temp_c in °C) rather than the raw register.
        val parsedValues = mutableMapOf<String, Double>()
        val exprValues = mutableMapOf<String, Double>()
        for (i in 0 until fieldsArray.length()) {
            val fieldDef = fieldsArray.getJSONObject(i)
            val fid = fieldDef.optString("id", "field_$i")
            val ftype = fieldDef.optString("type", "raw")
            if (ftype == "expr" || ftype == "formula") continue  // second pass
            try {
                val raw = extractRaw(bytes, fieldDef)
                parsedValues[fid] = raw
                exprValues[fid] = if (ftype == "scale_offset") {
                    raw * fieldDef.optDouble("scale", 1.0) + fieldDef.optDouble("offset_value", 0.0)
                } else {
                    raw
                }
            } catch (e: Exception) {
                warnings.add(FieldWarning(fid, "parse error: ${e.message}"))
            }
        }

        // Second pass: render all fields for display
        val renderedFields = mutableListOf<RenderedField>()
        for (i in 0 until fieldsArray.length()) {
            val fieldDef = fieldsArray.getJSONObject(i)
            val fid = fieldDef.optString("id", "field_$i")
            val label = fieldDef.optString("label", fid)
            val unit = fieldDef.optString("unit", "")
            val display = fieldDef.optBoolean("display", true)
            val ftype = fieldDef.optString("type", "raw")
            val precision = fieldDef.optInt("precision", 2)

            try {
                val rendered = when (ftype) {
                    "raw" -> {
                        val raw = parsedValues[fid]?.toLong() ?: 0L
                        val offset = fieldDef.optInt("offset", 0)
                        val length = fieldDef.optInt("length", 1)
                        val slice = bytes.sliceArray(offset until minOf(offset + length, bytes.size))
                        slice.joinToString("") { "%02x".format(it) }
                    }
                    "scale_offset" -> {
                        val raw = parsedValues[fid] ?: 0.0
                        val scale = fieldDef.optDouble("scale", 1.0)
                        val offsetVal = fieldDef.optDouble("offset_value", 0.0)
                        val result = raw * scale + offsetVal
                        formatDouble(result, precision)
                    }
                    "bitmask" -> {
                        val raw = (parsedValues[fid] ?: 0.0).toInt()
                        renderBitmask(raw, fieldDef)
                    }
                    "enum" -> {
                        val raw = (parsedValues[fid] ?: 0.0).toInt()
                        val values = fieldDef.optJSONObject("values")
                        values?.optString(raw.toString()) ?: "0x%02X".format(raw)
                    }
                    "expr" -> {
                        val expr = fieldDef.optString("expr", "0")
                        val result = evalExpr(expr, exprValues)
                        formatDouble(result, precision)
                    }
                    "formula" -> {
                        val formulaName = fieldDef.optString("formula", "")
                        val inputs = fieldDef.optJSONObject("inputs")
                        val resolvedInputs = mutableMapOf<String, Double>()
                        inputs?.keys()?.forEach { key ->
                            val srcId = inputs.optString(key)
                            resolvedInputs[key] = exprValues[srcId] ?: 0.0
                        }
                        val result = evalBuiltinFormula(formulaName, resolvedInputs)
                            ?: run {
                                warnings.add(FieldWarning(fid, "formula $formulaName not supported — showing 0"))
                                0.0
                            }
                        formatDouble(result, precision)
                    }
                    else -> {
                        // Unknown field type — fall back to raw hex + warning
                        warnings.add(FieldWarning(fid, "unknown field type $ftype — showing raw hex"))
                        val offset = fieldDef.optInt("offset", 0)
                        val length = fieldDef.optInt("length", 1)
                        val slice = bytes.sliceArray(offset until minOf(offset + length, bytes.size))
                        slice.joinToString("") { "%02x".format(it) }
                    }
                }
                renderedFields.add(RenderedField(id = fid, label = label, value = rendered, unit = unit, display = display))
            } catch (e: Exception) {
                warnings.add(FieldWarning(fid, "render error: ${e.message}"))
                renderedFields.add(RenderedField(id = fid, label = label, value = "?", unit = unit, display = display))
            }
        }

        return RenderedFrame(charUuid = charUuid, view = view, fields = renderedFields, warnings = warnings)
    }

    // ------------------------------------------------------------------
    // Byte extraction
    // ------------------------------------------------------------------

    private fun extractRaw(bytes: ByteArray, fieldDef: JSONObject): Double {
        val offset = fieldDef.optInt("offset", 0)
        val length = fieldDef.optInt("length", 1)
        val encoding = fieldDef.optString("encoding", "uint8")
        if (offset + length > bytes.size) return 0.0
        return when (encoding) {
            "uint8"      -> (bytes[offset].toInt() and 0xFF).toDouble()
            "int8"       -> bytes[offset].toDouble()
            "uint16_be"  -> (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toDouble()
            "uint16_le"  -> (((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)).toDouble()
            "int16_be"   -> {
                val u = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                (if (u >= 0x8000) u - 0x10000 else u).toDouble()
            }
            "int16_le"   -> {
                val u = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)
                (if (u >= 0x8000) u - 0x10000 else u).toDouble()
            }
            "uint32_be"  -> (
                ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
            ).toDouble()
            "uint32_le"  -> (
                ((bytes[offset + 3].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                (bytes[offset].toLong() and 0xFF)
            ).toDouble()
            "int32_be"   -> {
                val u = ((bytes[offset].toLong() and 0xFF) shl 24) or
                        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                        (bytes[offset + 3].toLong() and 0xFF)
                (if (u >= 0x80000000L) u - 0x100000000L else u).toDouble()
            }
            "int32_le"   -> {
                val u = ((bytes[offset + 3].toLong() and 0xFF) shl 24) or
                        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                        (bytes[offset].toLong() and 0xFF)
                (if (u >= 0x80000000L) u - 0x100000000L else u).toDouble()
            }
            "float32_be" -> {
                val bits = (((bytes[offset].toInt() and 0xFF) shl 24) or
                            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                            (bytes[offset + 3].toInt() and 0xFF))
                java.lang.Float.intBitsToFloat(bits).toDouble()
            }
            "float32_le" -> {
                val bits = (((bytes[offset + 3].toInt() and 0xFF) shl 24) or
                            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                            (bytes[offset].toInt() and 0xFF))
                java.lang.Float.intBitsToFloat(bits).toDouble()
            }
            "bytes", "utf8" -> 0.0  // handled inline in raw branch
            else -> 0.0
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun renderBitmask(raw: Int, fieldDef: JSONObject): String {
        val bits = fieldDef.optJSONArray("bits") ?: return "0x%02X".format(raw)
        val parts = mutableListOf<String>()
        for (i in 0 until bits.length()) {
            val bitDef = bits.getJSONObject(i)
            val bitPos = bitDef.optInt("bit", 0)
            val bitVal = (raw shr bitPos) and 1
            val label = bitDef.optString("label", "bit$bitPos")
            val values = bitDef.optJSONObject("values")
            val display = values?.optString(bitVal.toString()) ?: bitVal.toString()
            parts.add("$label:$display")
        }
        return parts.joinToString(" ")
    }

    private fun formatDouble(value: Double, precision: Int): String {
        return "%.${precision}f".format(value)
    }

    /**
     * Minimal, safe arithmetic expression evaluator.
     *
     * Grammar (recursive descent):
     *   expr   := term (('+' | '-') term)*
     *   term   := unary (('*' | '/') unary)*
     *   unary  := '-' unary | primary
     *   primary := number | identifier | '(' expr ')'
     *
     * Identifiers are resolved from [context] at the **token** level — never by string
     * substitution — so `temp_c` is matched as a whole token and cannot corrupt a longer
     * name like `temp_c_hidden`. Supports +, -, *, / and parentheses only. No functions,
     * no loops, no side effects → safe to run on untrusted template content.
     *
     * Division by zero yields 0.0 (template authoring convenience, never throws).
     * An unknown identifier resolves to 0.0.
     */
    private fun evalExpr(expr: String, context: Map<String, Double>): Double =
        try {
            ExprParser(expr, context).parse()
        } catch (e: Exception) {
            0.0
        }

    private class ExprParser(
        private val src: String,
        private val context: Map<String, Double>,
    ) {
        private var pos = 0

        fun parse(): Double {
            val result = parseExpr()
            return result
        }

        private fun peek(): Char? {
            skipSpaces()
            return if (pos < src.length) src[pos] else null
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun parseExpr(): Double {
            var result = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; result += parseTerm() }
                    '-' -> { pos++; result -= parseTerm() }
                    else -> return result
                }
            }
        }

        private fun parseTerm(): Double {
            var result = parseUnary()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; result *= parseUnary() }
                    '/' -> { pos++; val d = parseUnary(); result = if (d != 0.0) result / d else 0.0 }
                    else -> return result
                }
            }
        }

        private fun parseUnary(): Double {
            if (peek() == '-') { pos++; return -parseUnary() }
            if (peek() == '+') { pos++; return parseUnary() }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            val c = peek() ?: return 0.0
            if (c == '(') {
                pos++
                val inner = parseExpr()
                if (peek() == ')') pos++
                return inner
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter() || c == '_') return parseIdentifier()
            // Unknown character — consume it and treat as 0 to stay total.
            pos++
            return 0.0
        }

        private fun parseNumber(): Double {
            skipSpaces()
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            // Scientific notation: 1e3, 2.5E-2
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            return src.substring(start, pos).toDoubleOrNull() ?: 0.0
        }

        private fun parseIdentifier(): Double {
            skipSpaces()
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos)
            return context[name] ?: 0.0
        }
    }

    // ------------------------------------------------------------------
    // Built-in formulas
    // ------------------------------------------------------------------

    private fun evalBuiltinFormula(name: String, inputs: Map<String, Double>): Double? {
        return when (name) {
            "density_altitude" -> {
                val tempC = inputs["temp_c"] ?: return null
                val pressureHpa = inputs["pressure_hpa"] ?: return null
                val humidityPct = inputs["humidity_pct"] ?: 0.0
                // Standard density altitude formula (ISA)
                val tempK = tempC + 273.15
                val standardTempK = 288.15
                val pressureRatio = pressureHpa / 1013.25
                val da = 44330.0 * (1.0 - pressureRatio.pow(1.0 / 5.255)) -
                         (tempK - standardTempK) * 120.0 +
                         humidityPct * 0.5  // simplified humidity correction
                da * 3.28084  // metres to feet
            }
            else -> null
        }
    }
}
