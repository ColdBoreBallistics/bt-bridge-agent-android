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
