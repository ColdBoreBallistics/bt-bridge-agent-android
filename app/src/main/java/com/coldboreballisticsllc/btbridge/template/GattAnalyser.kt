// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge.template

/**
 * Built-in raw GATT analyser — always available regardless of template load state.
 * Renders every BLE notification as hex with byte offsets.
 * Cannot be replaced or overwritten by any template push.
 */
object GattAnalyser {

    fun render(charUuid: String, bytes: ByteArray): RenderedFrame {
        val fields = bytes.mapIndexed { i, byte ->
            RenderedField(
                id = "byte_$i",
                label = "Byte $i",
                value = "%02x".format(byte),
                unit = "",
                display = true,
            )
        }
        // Add a hex dump field showing the full payload
        val hexDump = bytes.joinToString(" ") { "%02x".format(it) }
        val allFields = listOf(
            RenderedField(id = "hex_dump", label = "Hex", value = hexDump, unit = "", display = true)
        ) + fields

        return RenderedFrame(
            charUuid = charUuid,
            view = "raw",
            fields = allFields,
            warnings = emptyList(),
        )
    }
}
