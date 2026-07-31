// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** One paired PC: the shared HMAC secret, a human-readable hostname, last IP. */
data class PairedPc(val secret: String, val hostname: String, val ip: String = "")

/**
 * Persists:
 *  - paired PCs (secret + hostname) used to mutually authenticate, and
 *  - a history of server IPs (connection hints; trust comes from the secret).
 * No SSID / location involved.
 */
class ServerStore(context: Context) {

    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_LIST = "saved_servers"
        private const val KEY_SELECTED = "selected_ip"
        private const val KEY_PAIRED = "paired_pcs"
        private const val KEY_DISABLED = "auto_connect_disabled"
    }

    /** User tapped Disconnect: block auto-connect until they reconnect manually. */
    fun isAutoConnectDisabled(): Boolean = prefs.getBoolean(KEY_DISABLED, false)
    fun setAutoConnectDisabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_DISABLED, value).apply()
    }

    // ---- Paired PCs (secret per machine; tried in turn when connecting) ----

    fun getPaired(): MutableList<PairedPc> {
        val json = prefs.getString(KEY_PAIRED, null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<PairedPc>>() {}.type)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** Add (or refresh the hostname of) a paired PC, keyed by its secret. */
    fun addPaired(pc: PairedPc) {
        val l = getPaired().filter { it.secret != pc.secret }.toMutableList()
        l.add(pc)
        prefs.edit().putString(KEY_PAIRED, gson.toJson(l)).apply()
    }

    fun clearPaired() {
        prefs.edit().remove(KEY_PAIRED).apply()
    }

    fun removePaired(secret: String) {
        val l = getPaired().filter { it.secret != secret }
        prefs.edit().putString(KEY_PAIRED, gson.toJson(l)).apply()
    }

    /** Drop all server hints + the selected one (used when unpairing everything). */
    fun clearServers() {
        prefs.edit().remove(KEY_LIST).remove(KEY_SELECTED).apply()
    }

    fun pairedCount(): Int = getPaired().size

    // ---- Server IP history (connection hints only) ----

    fun getAll(): MutableList<String> {
        val json = prefs.getString(KEY_LIST, null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<String>>() {}.type)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(list: List<String>) {
        prefs.edit().putString(KEY_LIST, gson.toJson(list)).apply()
    }

    fun add(ip: String) {
        val l = getAll()
        if (l.none { it == ip }) {
            l.add(ip)
            save(l)
        }
    }

    fun remove(ip: String) {
        save(getAll().filter { it != ip })
        if (getSelected() == ip) setSelected(null)
    }

    fun getSelected(): String? = prefs.getString(KEY_SELECTED, null)

    fun setSelected(ip: String?) {
        prefs.edit().putString(KEY_SELECTED, ip).apply()
    }

    /** Selected IP first (if any), then the rest of the history. */
    fun orderedIps(): List<String> {
        val sel = getSelected()
        return (listOfNotNull(sel) + getAll().filter { it != sel }).distinct()
    }
}
