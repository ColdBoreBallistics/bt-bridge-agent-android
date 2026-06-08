// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "bt_bridge_prefs")

private val KEY_IP_HISTORY = stringPreferencesKey("ip_history")
private const val MAX_HISTORY = 5

object Prefs {

    suspend fun loadHosts(context: Context): List<String> {
        val raw = context.dataStore.data.first()[KEY_IP_HISTORY] ?: return emptyList()
        return try { Json.decodeFromString<List<String>>(raw) } catch (_: Exception) { emptyList() }
    }

    suspend fun saveHost(context: Context, host: String) {
        context.dataStore.edit { prefs ->
            val raw  = prefs[KEY_IP_HISTORY]
            val list = if (raw != null) {
                try { Json.decodeFromString<List<String>>(raw) } catch (_: Exception) { emptyList() }
            } else emptyList()
            val updated = (listOf(host) + list.filter { it != host }).take(MAX_HISTORY)
            prefs[KEY_IP_HISTORY] = Json.encodeToString(updated)
        }
    }
}
