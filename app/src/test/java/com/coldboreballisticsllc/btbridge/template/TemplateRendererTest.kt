package com.coldboreballisticsllc.btbridge.template

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TemplateRendererTest {

    private fun makeDisplayTemplate(
        charUuid: String = "0000ff01-0000-1000-8000-00805f9b34fb",
        viewName: String = "raw",
        fieldsJson: String,
    ): JSONObject {
        return JSONObject("""
        {
          "schema_version": 1,
          "id": "test.display",
          "version": "1.0.0",
          "type": "display",
          "default_view": "$viewName",
          "notifications": [{
            "char": "$charUuid",
            "views": {
              "$viewName": { "fields": $fieldsJson }
            }
          }]
        }
        """)
    }

    @Test
    fun renders_raw_field_as_hex() {
        val template = makeDisplayTemplate(fieldsJson = """[
          {"id":"hdr","label":"Header","type":"raw","offset":0,"length":2,"encoding":"bytes","display":true}
        ]""")
        val renderer = TemplateRenderer(template)
        val bytes = byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x02)
        val frame = renderer.render("0000ff01-0000-1000-8000-00805f9b34fb", bytes, view = "raw")
        assertNotNull(frame)
        assertEquals(1, frame!!.fields.size)
        assertEquals("55aa", frame.fields[0].value.lowercase())
    }

    @Test
    fun renders_scale_offset_uint16_be() {
        val template = makeDisplayTemplate(
            viewName = "metric",
            fieldsJson = """[
              {"id":"wind","label":"Wind","type":"scale_offset","offset":0,"length":2,
               "encoding":"uint16_be","scale":0.001,"offset_value":0.0,"unit":"m/s",
               "precision":2,"display":true}
            ]"""
        )
        val renderer = TemplateRenderer(template)
        // 0x03E8 = 1000 → 1000 * 0.001 = 1.00 m/s
        val bytes = byteArrayOf(0x03, 0xE8.toByte())
        val frame = renderer.render("0000ff01-0000-1000-8000-00805f9b34fb", bytes, view = "metric")
        assertNotNull(frame)
        assertEquals("1.00", frame!!.fields[0].value)
        assertEquals("m/s", frame.fields[0].unit)
    }

    @Test
    fun renders_scale_offset_uint16_le() {
        val template = makeDisplayTemplate(
            viewName = "raw",
            fieldsJson = """[
              {"id":"speed","label":"Speed","type":"scale_offset","offset":0,"length":2,
               "encoding":"uint16_le","scale":1.0,"offset_value":0.0,"unit":"","precision":0,"display":true}
            ]"""
        )
        val renderer = TemplateRenderer(template)
        // LE: 0xE8 0x03 = 1000
        val bytes = byteArrayOf(0xE8.toByte(), 0x03)
        val frame = renderer.render("0000ff01-0000-1000-8000-00805f9b34fb", bytes, view = "raw")
        assertEquals("1000", frame!!.fields[0].value)
    }

    @Test
    fun char_not_matched_returns_null() {
        val template = makeDisplayTemplate(fieldsJson = """[]""")
        val renderer = TemplateRenderer(template)
        val frame = renderer.render("0000ffff-0000-1000-8000-00805f9b34fb", byteArrayOf(), view = "raw")
        assertNull(frame)
    }

    @Test
    fun display_false_field_excluded_from_output() {
        val template = makeDisplayTemplate(fieldsJson = """[
          {"id":"hidden","label":"Hidden","type":"raw","offset":0,"length":1,"encoding":"uint8","display":false},
          {"id":"shown","label":"Shown","type":"raw","offset":1,"length":1,"encoding":"uint8","display":true}
        ]""")
        val renderer = TemplateRenderer(template)
        val bytes = byteArrayOf(0x01, 0x02)
        val frame = renderer.render("0000ff01-0000-1000-8000-00805f9b34fb", bytes, view = "raw")
        val displayedIds = frame!!.fields.filter { it.display }.map { it.id }
        assertFalse("hidden" in displayedIds)
        assertTrue("shown" in displayedIds)
    }

    @Test
    fun renders_bitmask_field() {
        val template = makeDisplayTemplate(fieldsJson = """[
          {"id":"status","label":"Status","type":"bitmask","offset":0,"length":1,"encoding":"uint8","display":true,
           "bits":[
             {"bit":0,"id":"cover","label":"Cover","values":{"0":"Closed","1":"Open"}},
             {"bit":1,"id":"paper","label":"Paper","values":{"0":"OK","1":"Low"}}
           ]}
        ]""")
        val renderer = TemplateRenderer(template)
        // 0x03 = bits 0 and 1 set → Cover=Open, Paper=Low
        val bytes = byteArrayOf(0x03)
        val frame = renderer.render("0000ff01-0000-1000-8000-00805f9b34fb", bytes, view = "raw")
        assertNotNull(frame)
        // Bitmask renders as composite label
        assertTrue(frame!!.fields[0].value.contains("Open") || frame.fields[0].value.contains("Low"))
    }

    @Test
    fun renders_enum_field() {
        val template = makeDisplayTemplate(fieldsJson = """[
          {"id":"density","label":"Print Density","type":"enum","offset":0,"length":1,"encoding":"uint8","display":true,
           "values":{"1":"Light","2":"Medium","3":"Dark"}}
        ]""")
        val renderer = TemplateRenderer(template)
        val frame = renderer.render("0000ff01-0000-1000-8000-00805f9b34fb", byteArrayOf(0x02), view = "raw")
        assertEquals("Medium", frame!!.fields[0].value)
    }

    @Test
    fun unknown_field_type_returns_raw_with_warning() {
        val template = makeDisplayTemplate(fieldsJson = """[
          {"id":"exotic","label":"Future Field","type":"future_type_v99","offset":0,"length":1,"display":true}
        ]""")
        val renderer = TemplateRenderer(template)
        val frame = renderer.render("0000ff01-0000-1000-8000-00805f9b34fb", byteArrayOf(0xAB.toByte()), view = "raw")
        assertNotNull(frame)
        assertTrue(frame!!.warnings.any { it.fieldId == "exotic" })
        // Falls back to hex display
        assertEquals("ab", frame.fields.first { it.id == "exotic" }.value.lowercase())
    }

    @Test
    fun renders_expr_field() {
        val template = makeDisplayTemplate(
            viewName = "imperial",
            fieldsJson = """[
              {"id":"temp_c","label":"Temp C","type":"scale_offset","offset":0,"length":2,
               "encoding":"int16_be","scale":0.1,"offset_value":0.0,"unit":"","precision":1,"display":false},
              {"id":"temp_f","label":"Temperature","type":"expr","expr":"temp_c * 9/5 + 32",
               "unit":"°F","precision":1,"display":true}
            ]"""
        )
        val renderer = TemplateRenderer(template)
        // 0x00C8 = 200 → 200 * 0.1 = 20.0 °C → (20 * 9/5) + 32 = 68.0 °F
        val bytes = byteArrayOf(0x00, 0xC8.toByte())
        val frame = renderer.render("0000ff01-0000-1000-8000-00805f9b34fb", bytes, view = "imperial")
        assertNotNull(frame)
        val tempF = frame!!.fields.first { it.id == "temp_f" }
        assertEquals("68.0", tempF.value)
        assertEquals("°F", tempF.unit)
    }
}
