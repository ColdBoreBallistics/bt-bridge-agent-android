package com.coldboreballisticsllc.btbridge.template

import org.junit.Assert.*
import org.junit.Test

class RenderedFieldTest {

    @Test
    fun renderedFrameHoldsFields() {
        val fields = listOf(
            RenderedField(id = "wind", label = "Wind Speed", value = "12.5", unit = "mph"),
            RenderedField(id = "temp", label = "Temperature", value = "72.1", unit = "°F"),
        )
        val frame = RenderedFrame(
            charUuid = "961f0005",
            view = "imperial",
            fields = fields,
            warnings = emptyList(),
        )
        assertEquals(2, frame.fields.size)
        assertEquals("wind", frame.fields[0].id)
        assertEquals("mph", frame.fields[0].unit)
    }

    @Test
    fun fieldWarningHoldsMessage() {
        val w = FieldWarning(fieldId = "da_ft", message = "formula density_altitude not supported")
        assertEquals("da_ft", w.fieldId)
    }
}
