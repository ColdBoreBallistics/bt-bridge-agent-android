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

    @Test
    fun save_rejects_path_traversal_id() {
        val s = store()
        val evil = JSONObject("""{"schema_version":1,"id":"../../evil","version":"1.0.0","type":"device"}""")
        s.save(evil)
        // Not cached, and nothing written outside the store root.
        assertNull(s.get("../../evil", "1.0.0"))
        val escaped = File(tmp.root.parentFile, "evil/1.0.0.json")
        assertFalse("a file escaped the store root: $escaped", escaped.exists())
    }

    @Test
    fun save_rejects_bad_version() {
        val s = store()
        val bad = JSONObject("""{"schema_version":1,"id":"builtin.ok","version":"../evil","type":"device"}""")
        s.save(bad)
        assertNull(s.get("builtin.ok", "../evil"))
    }

    @Test
    fun save_accepts_normal_id_and_version() {
        // Regression: the validation must NOT reject legitimate ids/versions.
        val s = store()
        s.save(deviceTemplate("builtin.weatherflow-tactical-display", "1.2.0"))
        assertNotNull(s.get("builtin.weatherflow-tactical-display", "1.2.0"))
        // contrib namespace + hyphens + dotted version also fine.
        s.save(deviceTemplate("contrib.my-device-display", "0.1.0"))
        assertNotNull(s.get("contrib.my-device-display", "0.1.0"))
    }
}
