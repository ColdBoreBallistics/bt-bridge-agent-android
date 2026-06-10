# BT Bridge Android Agent — Template Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Commit workflow (FOSS).** The `bt-bridge-*` repos are governed as open-source projects, not
> under the CBB app SDLC. The per-task `git commit` steps use **Conventional Commits**
> (`type(scope): subject`) and are the intended granularity — one focused commit per task, made
> after that task's tests pass. This is the standard FOSS commit-as-you-go flow; the CBB "ask
> before every commit" gate does not apply here. Pushing and opening PRs remain explicit actions.

**Goal:** Add template-aware rendering to the Android agent: receive `push_templates` manifest, request and persist template files, respond to `apply_template` commands, render BLE notifications using display templates (all v1 field types), support view selection (raw/metric/imperial/etc.), emit `view_changed` events, and fall back to the built-in raw GATT analyser when no template is available.

**Architecture:** `TemplateStore` owns disk persistence and in-memory registry. `TemplateRenderer` applies a display template to a raw byte array and returns a `RenderedFrame`. `GattAnalyser` is the always-available fallback. `MainViewModel` routes `push_templates`/`template_data`/`apply_template`/`set_view` commands and emits `template_request`/`template_applied`/`view_changed` events. The active device view is stored per-address in `DataStore`.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization (existing), AndroidX DataStore (existing), `org.json` (stdlib — no new deps), JUnit + Robolectric for unit tests.

**Prerequisites:** Plan 1 (broker rewrite) complete. The broker sends `push_templates` on connect, responds with `template_data` on `template_request`, and sends `apply_template` after `services_discovered`.

**Design reference:** `bt-bridge-broker/docs/2026-06-08-template-system-design.md` (§13 Agent Runtime)

---

## File Map

| Action | Path | Responsibility |
|---|---|---|
| Create | `app/src/main/java/…/btbridge/template/TemplateStore.kt` | Disk persistence in `filesDir/templates/`; in-memory registry by (id, version) |
| Create | `app/src/main/java/…/btbridge/template/TemplateRenderer.kt` | Parse byte payload with a display template; return `RenderedFrame` |
| Create | `app/src/main/java/…/btbridge/template/GattAnalyser.kt` | Raw GATT fallback renderer — always available |
| Create | `app/src/main/java/…/btbridge/template/RenderedField.kt` | Data classes: `RenderedFrame`, `RenderedField`, `FieldWarning` |
| Modify | `app/src/main/java/…/btbridge/Protocol.kt` | Add `BleCommand` variants: `PushTemplates`, `TemplateData`, `ApplyTemplate`, `SetView`; add `buildTemplateRequest`, `buildTemplateApplied`, `buildViewChanged` |
| Modify | `app/src/main/java/…/btbridge/MainViewModel.kt` | Wire template commands; remove hardcoded WeatherFlow parsing (replaced by template renderer) |
| Modify | `app/src/main/java/…/btbridge/AppState` (in MainViewModel.kt) | Add `activeTemplate`, `activeView`, `templateWarnings`, `renderedFrame`, `gattAnalyserMode` |
| Modify | `app/src/main/java/…/btbridge/ui/MainScreen.kt` | Add template display panel; add view selector; show warning banners; remove hardcoded WeatherFlow panel |
| Create | `app/src/test/java/…/btbridge/template/TemplateRendererTest.kt` | JVM unit tests for renderer |
| Create | `app/src/test/java/…/btbridge/template/TemplateStoreTest.kt` | JVM unit tests for store persistence |

All paths under `app/src/main/java/com/coldboreballisticsllc/btbridge/`.

---

## Task 0: JVM unit-test harness

> **Why this task exists:** `app/build.gradle.kts` currently declares **no** test dependencies (no JUnit, no Robolectric, no `org.json:json`), and there is no `app/src/test/` directory. Every later task in this plan writes a JVM unit test and runs `./gradlew :app:testDebugUnitTest`. Without this task those tasks fail at the *first* test with `unresolved reference: org.junit` — not the expected "class under test not found" compilation error. This task stands up the harness and proves it green before any feature work.
>
> **`org.json` note:** The renderer and store tests use `org.json.JSONObject`. Android ships a *stub* `org.json` on the unit-test classpath that throws `"Method ... not mocked"` under plain JVM tests. Adding the real `org.json:json` artifact as a `testImplementation` dependency shadows the stub so JSON parsing works in unit tests **without** needing Robolectric for the renderer/store tests. Robolectric is only required where a test touches Android framework types (`Context`, `filesDir`) — none of this plan's tests do, because `TemplateStore` is constructed from a plain `java.io.File` root in tests (see Task 4).

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/…/btbridge/HarnessSmokeTest.kt`

- [ ] **Step 1: Add test library versions and entries to `gradle/libs.versions.toml`**

In the `[versions]` block, add:
```toml
junit    = "4.13.2"
orgJson  = "20240303"
```

In the `[libraries]` block, add:
```toml
junit    = { group = "junit",     name = "junit", version.ref = "junit" }
org-json = { group = "org.json",  name = "json",  version.ref = "orgJson" }
```

> Version pins above are current stable as of this plan's authoring. At execution time, verify the latest patch versions (`junit` 4.13.x, `org.json:json` date-stamped releases) and bump if a newer stable exists — do not downgrade.

- [ ] **Step 2: Add the test dependencies to `app/build.gradle.kts`**

In the `dependencies { ... }` block, add at the end:
```kotlin
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
```

> `org.json:json` as a `testImplementation` shadows the Android `org.json` stub on the unit-test classpath so `JSONObject` parses real JSON in JVM tests. It must **not** be added as `implementation` — the app already gets `org.json` from the Android SDK at runtime; adding it to `implementation` would cause a duplicate-class conflict.

- [ ] **Step 3: Create the source-set directories**

```bash
mkdir -p /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android/app/src/test/java/com/coldboreballisticsllc/btbridge
mkdir -p /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android/app/src/main/java/com/coldboreballisticsllc/btbridge/template
mkdir -p /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android/app/src/test/java/com/coldboreballisticsllc/btbridge/template
```

- [ ] **Step 4: Create a trivial smoke test proving the harness works**

`app/src/test/java/com/coldboreballisticsllc/btbridge/HarnessSmokeTest.kt`:
```kotlin
package com.coldboreballisticsllc.btbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the JVM unit-test harness is wired: JUnit runs, and the real org.json parses. */
class HarnessSmokeTest {

    @Test
    fun junit_runs() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun org_json_parses_real_json() {
        val obj = JSONObject("""{"id":"builtin.smoke","version":"1.0.0"}""")
        assertEquals("builtin.smoke", obj.getString("id"))
        assertEquals("1.0.0", obj.getString("version"))
    }
}
```

- [ ] **Step 5: Run the smoke test — expect GREEN**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:testDebugUnitTest --tests "com.coldboreballisticsllc.btbridge.HarnessSmokeTest" 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`, 2 tests PASS. If `org_json_parses_real_json` fails with `"... not mocked"`, the `org.json:json` test dependency did not resolve — fix before proceeding. **Do not start Task 1 until this is green.**

- [ ] **Step 6: Commit**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/test/java/com/coldboreballisticsllc/btbridge/HarnessSmokeTest.kt
git commit -m "test: add JVM unit-test harness (JUnit + org.json) for template runtime"
```

---

## Task 1: RenderedField data classes

**Files:**
- Create: `app/src/main/java/…/btbridge/template/RenderedField.kt`
- Create: `app/src/test/java/…/btbridge/template/RenderedFieldTest.kt`

- [ ] **Step 1: Write failing tests**

`app/src/test/java/com/coldboreballisticsllc/btbridge/template/RenderedFieldTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run tests — expect compilation error (file doesn't exist)**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:testDebugUnitTest --tests "com.coldboreballisticsllc.btbridge.template.RenderedFieldTest" 2>&1 | tail -10
```

Expected: compilation failure — `RenderedField` not found.

- [ ] **Step 3: Create app/src/main/java/…/btbridge/template/RenderedField.kt**

First, create the directory:
```bash
mkdir -p /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android/app/src/main/java/com/coldboreballisticsllc/btbridge/template
mkdir -p /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android/app/src/test/java/com/coldboreballisticsllc/btbridge/template
```

`app/src/main/java/com/coldboreballisticsllc/btbridge/template/RenderedField.kt`:
```kotlin
// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge.template

/**
 * A single parsed and converted field from a display template.
 * [display] mirrors the template's display flag — false fields are available for
 * expr calculations but are not shown in the UI.
 */
data class RenderedField(
    val id: String,
    val label: String,
    val value: String,
    val unit: String = "",
    val display: Boolean = true,
)

/** Warning attached to a field that could not be fully rendered. */
data class FieldWarning(
    val fieldId: String,
    val message: String,
)

/**
 * The complete output of rendering one BLE notification or read value
 * through a display template view.
 */
data class RenderedFrame(
    val charUuid: String,
    val view: String,
    val fields: List<RenderedField>,
    val warnings: List<FieldWarning>,
)
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:testDebugUnitTest --tests "com.coldboreballisticsllc.btbridge.template.RenderedFieldTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
git add app/src/main/java/com/coldboreballisticsllc/btbridge/template/RenderedField.kt \
        app/src/test/java/com/coldboreballisticsllc/btbridge/template/RenderedFieldTest.kt
git commit -m "feat(template): RenderedField, RenderedFrame, FieldWarning data classes"
```

---

## Task 2: TemplateRenderer — raw and scale_offset fields

**Files:**
- Create: `app/src/main/java/…/btbridge/template/TemplateRenderer.kt`
- Create: `app/src/test/java/…/btbridge/template/TemplateRendererTest.kt`

- [ ] **Step 1: Write failing renderer tests**

`app/src/test/java/com/coldboreballisticsllc/btbridge/template/TemplateRendererTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:testDebugUnitTest --tests "com.coldboreballisticsllc.btbridge.template.TemplateRendererTest" 2>&1 | tail -10
```

Expected: compilation failure — `TemplateRenderer` not found.

- [ ] **Step 3: Create TemplateRenderer.kt**

`app/src/main/java/com/coldboreballisticsllc/btbridge/template/TemplateRenderer.kt`:
```kotlin
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
            val notifChar = notif.optString("char")
            if (notifChar.isEmpty()) continue
            if (!charUuid.startsWith(notifChar, ignoreCase = true)) continue
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
        val width = when (encoding) {
            "uint8", "int8" -> 1
            "uint16_be", "uint16_le", "int16_be", "int16_le" -> 2
            "uint32_be", "uint32_le", "int32_be", "int32_le", "float32_be", "float32_le" -> 4
            else -> length  // bytes / utf8 / unknown — use declared length
        }
        if (offset < 0 || offset + width > bytes.size) return 0.0
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
        return String.format(java.util.Locale.US, "%.${precision}f", value)
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
```

- [ ] **Step 4: Run renderer tests**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:testDebugUnitTest --tests "com.coldboreballisticsllc.btbridge.template.TemplateRendererTest" 2>&1 | tail -20
```

Expected: all 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
git add app/src/main/java/com/coldboreballisticsllc/btbridge/template/TemplateRenderer.kt \
        app/src/test/java/com/coldboreballisticsllc/btbridge/template/TemplateRendererTest.kt
git commit -m "feat(template): TemplateRenderer with raw, scale_offset, bitmask, enum, expr, formula"
```

---

## Task 3: Raw GATT analyser fallback

**Files:**
- Create: `app/src/main/java/…/btbridge/template/GattAnalyser.kt`

- [ ] **Step 1: Create GattAnalyser.kt**

```kotlin
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
```

- [ ] **Step 2: Write a quick test for the analyser**

Append to `TemplateRendererTest.kt`:
```kotlin
    @Test
    fun gatt_analyser_renders_hex_dump() {
        val frame = GattAnalyser.render("0000ff01", byteArrayOf(0x55, 0xAA.toByte()))
        val hexDump = frame.fields.first { it.id == "hex_dump" }
        assertEquals("55 aa", hexDump.value.lowercase())
    }
```

- [ ] **Step 3: Run tests**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:testDebugUnitTest --tests "com.coldboreballisticsllc.btbridge.template.TemplateRendererTest" 2>&1 | tail -10
```

Expected: all 10 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/coldboreballisticsllc/btbridge/template/GattAnalyser.kt \
        app/src/test/java/com/coldboreballisticsllc/btbridge/template/TemplateRendererTest.kt
git commit -m "feat(template): GattAnalyser raw hex fallback renderer"
```

---

## Task 4: TemplateStore — disk persistence

**Files:**
- Create: `app/src/main/java/…/btbridge/template/TemplateStore.kt`
- Create: `app/src/test/java/…/btbridge/template/TemplateStoreTest.kt`

- [ ] **Step 1: Write failing store tests**

`app/src/test/java/com/coldboreballisticsllc/btbridge/template/TemplateStoreTest.kt`:
```kotlin
package com.coldboreballisticsllc.btbridge.template

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = TemplateStore(tmp.root)

    private fun deviceTemplate(id: String = "builtin.test-device", ver: String = "1.0.0") = JSONObject("""
        {"schema_version":1,"id":"$id","version":"$ver","type":"device","name":"Test"}
    """)

    @Test
    fun save_and_load_template() {
        val s = store()
        val t = deviceTemplate()
        s.save(t)
        val loaded = s.get("builtin.test-device", "1.0.0")
        assertNotNull(loaded)
        assertEquals("builtin.test-device", loaded!!.getString("id"))
    }

    @Test
    fun get_missing_returns_null() {
        val s = store()
        assertNull(s.get("builtin.nonexistent", "1.0.0"))
    }

    @Test
    fun higher_version_wins_on_conflict() {
        val s = store()
        val old = JSONObject("""{"schema_version":1,"id":"builtin.test","version":"1.0.0","type":"device"}""")
        val newer = JSONObject("""{"schema_version":1,"id":"builtin.test","version":"1.1.0","type":"device"}""")
        s.save(old)
        s.save(newer)
        // Both versions should coexist
        assertNotNull(s.get("builtin.test", "1.0.0"))
        assertNotNull(s.get("builtin.test", "1.1.0"))
    }

    @Test
    fun list_all_returns_saved_templates() {
        val s = store()
        s.save(deviceTemplate("builtin.a", "1.0.0"))
        s.save(deviceTemplate("builtin.b", "1.0.0"))
        assertEquals(2, s.listAll().size)
    }

    @Test
    fun persists_across_store_instances() {
        val dir = tmp.root
        val s1 = TemplateStore(dir)
        s1.save(deviceTemplate())
        val s2 = TemplateStore(dir)
        assertNotNull(s2.get("builtin.test-device", "1.0.0"))
    }

    @Test
    fun local_versions_returns_cached_id_version_pairs() {
        val s = store()
        s.save(deviceTemplate("builtin.a", "1.0.0"))
        s.save(deviceTemplate("builtin.a", "2.0.0"))
        val versions = s.localVersions()
        assertTrue(versions.any { it.id == "builtin.a" && it.version == "1.0.0" })
        assertTrue(versions.any { it.id == "builtin.a" && it.version == "2.0.0" })
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:testDebugUnitTest --tests "com.coldboreballisticsllc.btbridge.template.TemplateStoreTest" 2>&1 | tail -10
```

Expected: compilation failure — `TemplateStore` not found.

- [ ] **Step 3: Create TemplateStore.kt**

`app/src/main/java/com/coldboreballisticsllc/btbridge/template/TemplateStore.kt`:
```kotlin
// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge.template

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persists template JSON files to app-private storage.
 * Layout: [root]/templates/<namespace>/<local-name>/<version>.json
 *
 * Also keeps an in-memory map for fast lookup after initial load.
 */
class TemplateStore(private val root: File) {

    data class TemplateRef(val id: String, val version: String)

    private val cache = mutableMapOf<Pair<String, String>, JSONObject>()

    init {
        loadFromDisk()
    }

    constructor(context: Context) : this(File(context.filesDir, "templates"))

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun save(template: JSONObject) {
        // optString returns "" (never null) when a key is absent, so guard on emptiness.
        val tid = template.optString("id")
        val ver = template.optString("version")
        if (tid.isEmpty() || ver.isEmpty()) return
        val file = fileFor(tid, ver)
        file.parentFile?.mkdirs()
        file.writeText(template.toString(2))
        cache[tid to ver] = template
    }

    fun get(templateId: String, version: String): JSONObject? =
        cache[templateId to version]

    fun listAll(): List<JSONObject> = cache.values.toList()

    fun localVersions(): List<TemplateRef> =
        cache.keys.map { (id, ver) -> TemplateRef(id, ver) }

    // ------------------------------------------------------------------
    // Disk helpers
    // ------------------------------------------------------------------

    private fun loadFromDisk() {
        cache.clear()
        if (!root.exists()) return
        root.walk()
            .filter { it.isFile && it.extension == "json" }
            .forEach { file ->
                try {
                    val obj = JSONObject(file.readText())
                    val tid = obj.optString("id")
                    val ver = obj.optString("version")
                    if (tid.isNotEmpty() && ver.isNotEmpty()) {
                        cache[tid to ver] = obj
                    }
                } catch (_: Exception) {}
            }
    }

    private fun fileFor(templateId: String, version: String): File {
        val parts = templateId.split(".", limit = 2)
        val namespace = parts.getOrElse(0) { "unknown" }
        val local = parts.getOrElse(1) { templateId }
        return File(root, "$namespace/$local/$version.json")
    }
}
```

- [ ] **Step 4: Run store tests**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:testDebugUnitTest --tests "com.coldboreballisticsllc.btbridge.template.TemplateStoreTest" 2>&1 | tail -10
```

Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coldboreballisticsllc/btbridge/template/TemplateStore.kt \
        app/src/test/java/com/coldboreballisticsllc/btbridge/template/TemplateStoreTest.kt
git commit -m "feat(template): TemplateStore — disk persistence and in-memory cache"
```

---

## Task 5: Protocol additions — template commands and events

**Files:**
- Modify: `app/src/main/java/…/btbridge/Protocol.kt`

- [ ] **Step 1: Add new BleCommand variants to the sealed class**

In `Protocol.kt`, add to the `sealed class BleCommand` block after `DismissQuestion`:
```kotlin
    data class PushTemplates(val manifest: List<TemplateManifestEntry>) : BleCommand()
    data class TemplateData(val id: String, val version: String, val content: String) : BleCommand()
    data class ApplyTemplate(val address: String, val deviceTemplateId: String, val version: String, val variantId: String?) : BleCommand()
    data class SetView(val address: String, val view: String) : BleCommand()
```

Add before the sealed class:
```kotlin
data class TemplateManifestEntry(val id: String, val version: String)
```

- [ ] **Step 2: Add parsing cases to parseBleCommand**

In `parseBleCommand`, in the `when (val cmd = ...)` block, add before `else ->`:
```kotlin
            "push_templates" -> {
                val manifest = obj.optJSONArray("manifest")
                val entries = buildList {
                    if (manifest != null) {
                        for (i in 0 until manifest.length()) {
                            val e = manifest.getJSONObject(i)
                            add(TemplateManifestEntry(
                                id = e.optString("id"),
                                version = e.optString("version"),
                            ))
                        }
                    }
                }
                BleCommand.PushTemplates(entries)
            }
            "template_data" -> BleCommand.TemplateData(
                id = obj.optString("id"),
                version = obj.optString("version"),
                content = obj.optString("content"),
            )
            "apply_template" -> BleCommand.ApplyTemplate(
                address = obj.optString("address"),
                deviceTemplateId = obj.optString("device_template_id"),
                version = obj.optString("version"),
                variantId = obj.optString("variant_id").takeIf { it.isNotEmpty() },
            )
            "set_view" -> BleCommand.SetView(
                address = obj.optString("address"),
                view = obj.optString("view"),
            )
```

- [ ] **Step 3: Add outbound event builders**

At the end of `Protocol.kt`, add:
```kotlin
fun buildTemplateRequest(ids: List<TemplateManifestEntry>): String {
    val idsJson = ids.joinToString(",") { """{"id":"${it.id}","version":"${it.version}"}""" }
    return """{"event":"template_request","ids":[$idsJson],"ts":${nowMs()}}"""
}

fun buildTemplateApplied(address: String, deviceTemplateId: String, version: String, variantId: String?): String {
    val variantPart = if (variantId != null) ""","variant_id":"$variantId"""" else ","variant_id":null"
    return """{"event":"template_applied","address":"$address","device_template_id":"$deviceTemplateId","version":"$version"$variantPart,"ts":${nowMs()}}"""
}

fun buildViewChanged(address: String, view: String): String =
    """{"event":"view_changed","address":"$address","view":"$view","ts":${nowMs()}}"""

fun buildHello(platform: String, capabilities: List<String>, bleEnabled: Boolean): String {
    val caps = capabilities.joinToString(",") { "\"$it\"" }
    return """{"event":"hello","platform":"$platform","capabilities":[$caps],"ble_enabled":$bleEnabled,"ts":${nowMs()}}"""
}
```

- [ ] **Step 4: Build to verify compilation**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` with no compilation errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coldboreballisticsllc/btbridge/Protocol.kt
git commit -m "feat(protocol): PushTemplates, TemplateData, ApplyTemplate, SetView commands; template event builders"
```

---

## Task 6: MainViewModel — wire template handling

**Files:**
- Modify: `app/src/main/java/…/btbridge/MainViewModel.kt`

- [ ] **Step 1: Update AppState to include template fields**

In `MainViewModel.kt`, update `AppState`:
```kotlin
data class AppState(
    val serverHost:            String                        = "192.168.1.1",
    val serverPort:            String                        = "2653",
    val serverConnected:       Boolean                       = false,
    val bleDevice:             String?                       = null,
    val isScanning:            Boolean                       = false,
    val scanResults:           List<ScanDevice>              = emptyList(),
    val renderedFrame:         RenderedFrame?                = null,
    val gattAnalyserMode:      Boolean                       = false,
    val activeView:            String                        = "raw",
    val availableViews:        List<String>                  = listOf("raw"),
    val templateWarnings:      List<String>                  = emptyList(),
    val log:                   List<String>                  = emptyList(),
    val ipHistory:             List<String>                  = emptyList(),
    val pendingQuestions:      List<ServerQuestion>          = emptyList(),
)
```

Note: `weatherFlow: WeatherFlowReading?` is removed — replaced by `renderedFrame`. The WeatherFlow data will be rendered through the template system.

- [ ] **Step 2: Add TemplateStore and active template state to MainViewModel**

Add these private fields to `MainViewModel` class body, below `private val bleManager = ...`:
```kotlin
    private lateinit var templateStore: TemplateStore
    private var activeDisplayTemplate: org.json.JSONObject? = null
    private var activeTemplateRenderer: com.coldboreballisticsllc.btbridge.template.TemplateRenderer? = null
    private val activeViewPerDevice = mutableMapOf<String, String>()
```

In `init { ... }`, add initialization of templateStore after existing setup:
```kotlin
        templateStore = TemplateStore(app)
        // Send hello on first TCP connect
        viewModelScope.launch {
            tcpClient.connectionState.collect { connected ->
                if (connected) {
                    tcpClient.send(buildHello(
                        platform = "android",
                        capabilities = listOf("push_templates", "apply_template", "set_view"),
                        bleEnabled = true,
                    ))
                }
            }
        }
```

- [ ] **Step 3: Add template command handlers to handleCommand**

In `handleCommand(cmd: BleCommand)`, add new cases after the existing `DismissQuestion` case:
```kotlin
            is BleCommand.PushTemplates -> handlePushTemplates(cmd)
            is BleCommand.TemplateData -> handleTemplateData(cmd)
            is BleCommand.ApplyTemplate -> handleApplyTemplate(cmd)
            is BleCommand.SetView -> handleSetView(cmd)
```

- [ ] **Step 4: Add the handler methods**

Add these private methods to `MainViewModel`, after `tryParseDisconnected`:
```kotlin
    private fun handlePushTemplates(cmd: BleCommand.PushTemplates) {
        val localVersions = templateStore.localVersions().associate { it.id to it.version }
        val toRequest = cmd.manifest.filter { entry ->
            val local = localVersions[entry.id]
            local == null || isNewerVersion(entry.version, local)
        }
        if (toRequest.isNotEmpty()) {
            tcpClient.send(buildTemplateRequest(toRequest))
            addLog("→SRV template_request: ${toRequest.size} templates")
        }
    }

    private fun handleTemplateData(cmd: BleCommand.TemplateData) {
        try {
            val obj = org.json.JSONObject(cmd.content)
            templateStore.save(obj)
            addLog("←SRV template_data: ${cmd.id}@${cmd.version} saved")
        } catch (e: Exception) {
            addLog("template_data parse error: ${e.message}")
        }
    }

    private fun handleApplyTemplate(cmd: BleCommand.ApplyTemplate) {
        val deviceTemplate = templateStore.get(cmd.deviceTemplateId, cmd.version)
        if (deviceTemplate == null) {
            addLog("apply_template: ${cmd.deviceTemplateId}@${cmd.version} not in local cache — GATT analyser active")
            _state.update { it.copy(
                gattAnalyserMode = true,
                templateWarnings = listOf("No device template — showing raw GATT data"),
            )}
            return
        }
        // Resolve display template reference
        val displayRef = deviceTemplate.optString("display_template").takeIf { it.isNotEmpty() }
            ?: deviceTemplate.optJSONObject("references")?.optString("display")
        val displayTemplate = displayRef?.let {
            val (id, ver) = parseTemplateRef(it)
            templateStore.get(id, ver ?: templateStore.localVersions()
                .filter { r -> r.id == id }
                .maxByOrNull { r -> r.version }?.version ?: "")
        }

        if (displayTemplate == null) {
            addLog("apply_template: display template not found for ${cmd.deviceTemplateId} — GATT analyser active")
            _state.update { it.copy(
                gattAnalyserMode = true,
                templateWarnings = listOf("No display template — showing raw GATT data"),
            )}
        } else {
            activeDisplayTemplate = displayTemplate
            activeTemplateRenderer = com.coldboreballisticsllc.btbridge.template.TemplateRenderer(displayTemplate)
            val defaultView = displayTemplate.optString("default_view", "raw")
            val views = extractViews(displayTemplate)
            val device = cmd.address
            activeViewPerDevice[device] = activeViewPerDevice[device] ?: defaultView
            _state.update { it.copy(
                gattAnalyserMode = false,
                activeView = activeViewPerDevice[device] ?: defaultView,
                availableViews = views,
                templateWarnings = emptyList(),
            )}
            tcpClient.send(buildTemplateApplied(cmd.address, cmd.deviceTemplateId, cmd.version, cmd.variantId))
            addLog("Template applied: ${cmd.deviceTemplateId}@${cmd.version} variant=${cmd.variantId}")
        }
    }

    private fun handleSetView(cmd: BleCommand.SetView) {
        activeViewPerDevice[cmd.address] = cmd.view
        _state.update { it.copy(activeView = cmd.view) }
        // Re-render last frame if available
        _state.value.renderedFrame?.let { frame ->
            // Trigger re-render on next notification
        }
        addLog("View set to ${cmd.view} for ${cmd.address}")
    }

    fun selectView(address: String, view: String) {
        activeViewPerDevice[address] = view
        _state.update { it.copy(activeView = view) }
        tcpClient.send(buildViewChanged(address, view))
    }

    private fun parseTemplateRef(ref: String): Pair<String, String?> {
        // Format: "builtin.some-template@^1.0.0" or "builtin.some-template"
        val atIdx = ref.indexOf('@')
        return if (atIdx >= 0) ref.substring(0, atIdx) to ref.substring(atIdx + 1)
        else ref to null
    }

    private fun extractViews(displayTemplate: org.json.JSONObject): List<String> {
        val notifications = displayTemplate.optJSONArray("notifications") ?: return listOf("raw")
        if (notifications.length() == 0) return listOf("raw")
        val first = notifications.getJSONObject(0)
        val views = first.optJSONObject("views") ?: return listOf("raw")
        return views.keys().asSequence().toList()
    }

    private fun isNewerVersion(a: String, b: String): Boolean {
        val aParts = a.split(".").mapNotNull { it.toIntOrNull() }
        val bParts = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val av = aParts.getOrElse(i) { 0 }
            val bv = bParts.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }
```

- [ ] **Step 5: Update tryParseServicesDiscovered and notification handling**

Replace `tryParseWeatherFlow` with a generic template-aware notification handler. In `BleManager.onEvent` callback in `MainViewModel`, change the event routing:

Find the `bleManager = BleManager(...)` block and replace `tryParseWeatherFlow(msg)` with `tryParseNotification(msg)`.

Add the new method:
```kotlin
    private fun tryParseNotification(msg: String) {
        try {
            val obj = BridgeJson.decodeFromString<kotlinx.serialization.json.JsonObject>(msg)
            if (obj["event"]?.jsonPrimitive?.content != "notification") return
            val char = obj["char"]?.jsonPrimitive?.content ?: return
            val hex = obj["value"]?.jsonPrimitive?.content ?: return
            val bytes = hex.hexToBytes()
            val address = obj["address"]?.jsonPrimitive?.content ?: return
            val currentView = activeViewPerDevice[address] ?: _state.value.activeView

            val frame = if (_state.value.gattAnalyserMode || activeTemplateRenderer == null) {
                com.coldboreballisticsllc.btbridge.template.GattAnalyser.render(char, bytes)
            } else {
                activeTemplateRenderer!!.render(char, bytes, currentView)
                    ?: com.coldboreballisticsllc.btbridge.template.GattAnalyser.render(char, bytes)
            }
            _state.update { it.copy(renderedFrame = frame) }
        } catch (_: Exception) {}
    }
```

- [ ] **Step 6: Fix default port to 2653 (was 9876)**

In `AppState`, the `serverPort` default was `"9876"`. It was already updated in Plan 1 at the broker level. Change the `AppState` default to `"2653"`:
```kotlin
    val serverPort: String = "2653",
```

Also update `autoConnect` in `MainViewModel` where it falls back to the old port:
```kotlin
val port = _state.value.serverPort.trim().toIntOrNull() ?: 2653
```

- [ ] **Step 7: Build to verify no compilation errors**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/coldboreballisticsllc/btbridge/MainViewModel.kt
git commit -m "feat(viewmodel): template push/data/apply/view handling, generic notification renderer"
```

---

## Task 7: UI — template display panel and view selector

**Files:**
- Modify: `app/src/main/java/…/btbridge/ui/MainScreen.kt`

- [ ] **Step 1: Read the existing MainScreen.kt fully to understand all composables**

```bash
cat "/home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android/app/src/main/java/com/coldboreballisticsllc/btbridge/ui/MainScreen.kt"
```

- [ ] **Step 2: Add template display panel composable**

Add before the end of `MainScreen.kt` (before the last `}`):
```kotlin
@Composable
fun TemplateDisplayPanel(
    renderedFrame: RenderedFrame?,
    gattAnalyserMode: Boolean,
    activeView: String,
    availableViews: List<String>,
    templateWarnings: List<String>,
    onViewSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Warning banners
        for (warning in templateWarnings) {
            Surface(
                color = Color(0xFF3A2E00),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "⚠ $warning",
                    color = Color(0xFFFFCC00),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // View selector — only show if more than one view available
        if (availableViews.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                availableViews.forEach { view ->
                    FilterChip(
                        selected = view == activeView,
                        onClick = { onViewSelected(view) },
                        label = { Text(view, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        // Rendered fields
        if (renderedFrame != null) {
            val displayFields = renderedFrame.fields.filter { it.display }
            displayFields.forEach { field ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Text(
                        text = if (field.unit.isNotEmpty()) "${field.value} ${field.unit}" else field.value,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Field warnings
            renderedFrame.warnings.forEach { w ->
                Text(
                    text = "⚠ ${w.fieldId}: ${w.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFCC00),
                )
            }
        } else if (gattAnalyserMode || templateWarnings.isEmpty()) {
            Text(
                text = if (gattAnalyserMode) "GATT Analyser — no template" else "Waiting for BLE data…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}
```

- [ ] **Step 3: Wire TemplateDisplayPanel into MainScreen**

In `MainScreen`, remove the existing `WeatherFlowPanel` block:
```kotlin
        // Remove this entire block:
        // state.weatherFlow?.let { wf ->
        //     WeatherFlowPanel(reading = wf)
        //     HorizontalDivider(color = Color(0xFF2A2E30))
        // }
```

Replace it with:
```kotlin
        // Template display — shown when connected to a device
        if (state.bleDevice != null) {
            TemplateDisplayPanel(
                renderedFrame = state.renderedFrame,
                gattAnalyserMode = state.gattAnalyserMode,
                activeView = state.activeView,
                availableViews = state.availableViews,
                templateWarnings = state.templateWarnings,
                onViewSelected = { view ->
                    state.bleDevice?.let { addr -> viewModel.selectView(addr, view) }
                },
            )
            HorizontalDivider(color = Color(0xFF2A2E30))
        }
```

- [ ] **Step 4: Add required imports to MainScreen.kt**

Add at the top import block:
```kotlin
import com.coldboreballisticsllc.btbridge.template.RenderedFrame
import androidx.compose.material3.FilterChip
```

- [ ] **Step 5: Build and verify compilation**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run full test suite**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/coldboreballisticsllc/btbridge/ui/MainScreen.kt
git commit -m "feat(ui): TemplateDisplayPanel with view selector and warning banners; remove hardcoded WeatherFlow panel"
```

---

## Task 8: Build debug APK and drop to file server path

- [ ] **Step 1: Build the debug APK**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
./gradlew assembleDebug 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Copy to the internal file server drop path**

The ScopeDOPE APK drop convention (`project_apk_drop_location.md`) puts debug APKs under
`files/Android/`. BT Bridge is a separate product line, so its APKs go in a dedicated
`files/Android/BTBridge/` subdir to keep product lines cleanly separated.

**Filename convention:** `BT_Bridge_<versionName>-<git-short-sha>_debug.apk`. The version name
gives human-readable release context; the git short SHA makes every build unambiguously
traceable to a commit — essential for a hardware-test tool where "which build is on the
phone?" comes up constantly. Derive both at copy time rather than hardcoding:

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
VERSION_NAME=$(grep -E 'versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
SHORT_SHA=$(git rev-parse --short HEAD)
DROP_DIR=/home/jschwefel/repositories/ColdBoreBallistics/files/Android/BTBridge
APK_NAME="BT_Bridge_${VERSION_NAME}-${SHORT_SHA}_debug.apk"
mkdir -p "$DROP_DIR"
cp app/build/outputs/apk/debug/app-debug.apk "$DROP_DIR/$APK_NAME"
echo "Dropped: $DROP_DIR/$APK_NAME"
```

> The drop must come **after** the final commit (Step 4) if you want the SHA to reflect the
> committed state — or accept that the SHA names the parent commit. For a debug drop, naming
> the current `HEAD` is fine; just be aware the APK built from a dirty tree carries the last
> commit's SHA. If a dirty tree matters, append `-dirty`: `git describe --always --dirty`.

- [ ] **Step 3: Verify file is present**

```bash
ls -lh /home/jschwefel/repositories/ColdBoreBallistics/files/Android/BTBridge/
```

Expected: a `BT_Bridge_<version>-<sha>_debug.apk` listed with a reasonable size (>1 MB).

- [ ] **Step 4: Final commit for Plan 3**

```bash
cd /home/jschwefel/repositories/ColdBoreBallistics/bt-bridge-agent-android
git add -A
git commit -m "feat: template runtime — TemplateStore, TemplateRenderer, GattAnalyser, Protocol v1.2 commands, UI display panel"
```

---

## Post-Plan Notes

- **Debug-output hygiene (Y3).** This plan introduces no `Log.d`/`println`/`System.out` debug
  statements — verify with `grep -rn 'Log\.\|println\|System\.out' app/src/main/java/` after
  implementation; the only expected hits are the pre-existing `TcpClient.kt` `writer?.println(msg)`
  (which writes protocol JSON to the **TCP socket** — functional I/O, **must not** be guarded or
  removed) and any `addLog(...)` calls (which append to the in-app **UI log list**, a user-facing
  feature — also not debug output). If a contributor later adds genuine diagnostic `Log.d`, it must
  be wrapped in `if (BuildConfig.DEBUG)`. Do not wrap `addLog` or the TCP `println`.
- The `WeatherFlowPanel` composable and `WeatherFlowReading` data class remain in the codebase but are no longer shown in `MainScreen`. They can be deleted in a follow-on cleanup once template-rendered display is validated against real hardware.
- Template component merging (`includes` array from design §9) is not implemented — component templates are served by the broker and stored by the agent, but the merge step is deferred to a follow-on plan.
- iOS template runtime is **out of scope** — `bt-bridge-agent-ios` is a placeholder spec only, implementation begins when iOS ScopeDOPE dev starts.
- Persisted active view in DataStore (design §13.3) uses an in-memory `activeViewPerDevice` map here. Full DataStore persistence survives app restart and is a follow-on improvement.
