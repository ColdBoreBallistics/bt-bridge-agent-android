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

    companion object {
        // Template id/version must be safe filesystem-path components — no separators,
        // no parent-dir escapes. Mirrors the broker's save_draft validation.
        private val SAFE_ID = Regex("^[a-z0-9]+([._-][a-z0-9]+)*$")
        private val SAFE_VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    }

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
        // Reject ids/versions that aren't safe path components (path-traversal defense).
        if (!SAFE_ID.matches(tid) || !SAFE_VERSION.matches(ver)) return
        val file = fileFor(tid, ver)
        // Defense in depth: the resolved file must stay within the store root.
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) return
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
                    if (tid.isNotEmpty() && ver.isNotEmpty()
                        && SAFE_ID.matches(tid) && SAFE_VERSION.matches(ver)) {
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
