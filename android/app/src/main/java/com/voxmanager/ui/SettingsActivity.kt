// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.voxmanager.R
import com.voxmanager.data.PairedPc
import com.voxmanager.data.ServerStore
import com.voxmanager.wifi.WifiKeyboardManager
import kotlinx.coroutines.launch

/**
 * Settings with a Wi-Fi-style computer list: the connected PC sits on top,
 * tap any PC to connect/disconnect, long-press to unpair, "+" to pair a new one.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var store: ServerStore
    private val wifi = WifiKeyboardManager()
    private var connectingSecret: String? = null
    /** Secret of the PC verified as reachable RIGHT NOW (live status, not a
     *  persisted guess). Null until a probe confirms it. */
    private var liveSecret: String? = null

    private lateinit var pcContainer: LinearLayout
    private lateinit var emptyPcs: TextView
    private lateinit var languageValue: TextView

    private val langLabels = arrayOf(
        "System default", "English (US)", "Русский", "Українська", "Español",
        "Deutsch", "Français", "Italiano", "Polski", "Português (BR)"
    )
    private val langCodes = arrayOf(
        "", "en-US", "ru-RU", "uk-UA", "es-ES",
        "de-DE", "fr-FR", "it-IT", "pl-PL", "pt-BR"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = ServerStore(this)
        pcContainer = findViewById(R.id.pcContainer)
        emptyPcs = findViewById(R.id.emptyPcs)
        languageValue = findViewById(R.id.languageValue)

        applyTitleGradient(findViewById(R.id.settingsTitle))
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.languageRow).setOnClickListener { pickLanguage() }
        findViewById<View>(R.id.addPcRow).setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }
        findViewById<View>(R.id.desktopAppRow).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.desktop_url))))
            } catch (_: Exception) { /* no browser available */ }
        }
    }

    override fun onResume() {
        super.onResume()
        updateLanguageRow()
        probeConnection()
    }

    private fun applyTitleGradient(tv: TextView) {
        tv.post {
            val w = tv.paint.measureText(tv.text.toString()).coerceAtLeast(1f)
            tv.paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.parseColor("#A8B1FF"), Color.WHITE, Color.parseColor("#BFF3DD")),
                floatArrayOf(0f, 0.5f, 0.9f), Shader.TileMode.CLAMP
            )
            tv.invalidate()
        }
    }

    // ---- Computer list ----

    private fun refresh() {
        // Orphan IP hints without a paired secret are useless — drop them.
        if (store.pairedCount() == 0) store.clearServers()

        val paired = store.getPaired()

        pcContainer.removeAllViews()
        emptyPcs.visibility = if (paired.isEmpty()) View.VISIBLE else View.GONE

        // "Connected" reflects a live probe (liveSecret), never a persisted guess —
        // a paired PC that's off / off-network must read "Tap to connect".
        val rows = paired.map { pc ->
            pc to (pc.secret == liveSecret)
        }.sortedByDescending { it.second }

        val inflater = LayoutInflater.from(this)
        for ((pc, connected) in rows) {
            val connectingThis = pc.secret == connectingSecret
            val row = inflater.inflate(R.layout.item_pc, pcContainer, false)
            row.findViewById<TextView>(R.id.pcName).text =
                pc.hostname.ifBlank { pc.ip.ifBlank { getString(R.string.status_connected) } }
            row.findViewById<TextView>(R.id.pcStatus).text = when {
                connectingThis -> getString(R.string.status_connecting)
                connected -> getString(R.string.pc_connected)
                else -> getString(R.string.pc_tap_connect)
            }
            row.findViewById<ImageView>(R.id.pcIcon).setColorFilter(when {
                connected -> 0xFF34D399.toInt()
                connectingThis -> 0xFFE3B765.toInt()
                else -> 0xFF6B6B78.toInt()
            })

            val x = row.findViewById<View>(R.id.pcDisconnect)
            x.visibility = if (connected) View.VISIBLE else View.GONE
            x.setOnClickListener { disconnect() }

            row.setOnClickListener {
                when {
                    connectingThis -> {}
                    connected -> disconnect()
                    else -> connectTo(pc)
                }
            }
            row.setOnLongClickListener { confirmUnpair(pc); true }
            pcContainer.addView(row)
        }
    }

    /** Establish the link here and show the result in place — don't jump to main. */
    private fun connectTo(pc: PairedPc) {
        if (connectingSecret != null) return
        store.setAutoConnectDisabled(false)
        connectingSecret = pc.secret
        refresh()
        lifecycleScope.launch {
            val ip = wifi.discoverServer() ?: pc.ip
            var ok = false
            if (ip.isNotEmpty()) {
                wifi.setServerIp(ip)
                wifi.setSecret(pc.secret)
                ok = wifi.verifyConnection() != null
            }
            runOnUiThread {
                connectingSecret = null
                if (ok) {
                    store.setSelected(ip)
                    liveSecret = pc.secret
                } else {
                    liveSecret = null
                    store.setAutoConnectDisabled(true)   // failed — don't auto-loop on a dead PC
                    Toast.makeText(this@SettingsActivity,
                        getString(R.string.connect_failed_toast), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    private fun disconnect() {
        store.setAutoConnectDisabled(true)
        liveSecret = null
        refresh() // stays here; the PC drops to "Tap to connect"
    }

    /**
     * Silent live status check when Settings opens: discover + verify so the list
     * shows the REAL connection state instead of a persisted guess. Read-only — no
     * toasts, and it never flips auto-connect-disabled (that's the user's call).
     */
    private fun probeConnection() {
        val paired = store.getPaired()
        if (store.isAutoConnectDisabled() || paired.isEmpty()) {
            liveSecret = null
            connectingSecret = null
            refresh()
            return
        }
        // Show "Connecting…" on the most likely target while the probe runs.
        connectingSecret = (paired.firstOrNull { it.ip.isNotEmpty() && it.ip == store.getSelected() }
            ?: paired.singleOrNull())?.secret
        refresh()
        lifecycleScope.launch {
            val ip = wifi.discoverServer() ?: store.getSelected()
            var verified: String? = null
            if (!ip.isNullOrEmpty()) {
                wifi.setServerIp(ip)
                for (pc in paired) {
                    wifi.setSecret(pc.secret)
                    if (wifi.verifyConnection() != null) {
                        verified = pc.secret
                        store.setSelected(ip)
                        break
                    }
                }
            }
            runOnUiThread {
                connectingSecret = null
                liveSecret = verified
                refresh()
            }
        }
    }

    private fun confirmUnpair(pc: PairedPc) {
        val name = pc.hostname.ifBlank { pc.ip }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.confirmTitle).text = getString(R.string.unpair_one, name)
        view.findViewById<TextView>(R.id.confirmSub).text = getString(R.string.unpair_hint)
        view.findViewById<Button>(R.id.btnConfirm).text = getString(R.string.unpair_short)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            store.removePaired(pc.secret)
            if (pc.ip.isNotEmpty() && pc.ip == store.getSelected()) store.setSelected(null)
            if (store.pairedCount() == 0) store.clearServers()
            refresh()
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---- Language (bottom sheet) ----

    private fun prefs() = getSharedPreferences("voice_settings", Context.MODE_PRIVATE)

    private fun updateLanguageRow() {
        val code = prefs().getString("recognition_language", "") ?: ""
        val idx = langCodes.indexOf(code).coerceAtLeast(0)
        languageValue.text = if (idx == 0) getString(R.string.language_system_default) else langLabels[idx]
    }

    private fun pickLanguage() {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_language, null)
        val list = view.findViewById<LinearLayout>(R.id.langContainer)
        val current = prefs().getString("recognition_language", "") ?: ""
        for (i in langLabels.indices) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_language, list, false)
            val selected = langCodes[i] == current
            row.findViewById<TextView>(R.id.langLabel).apply {
                text = langLabels[i]
                if (selected) setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            }
            row.findViewById<TextView>(R.id.langCheck).visibility = if (selected) View.VISIBLE else View.GONE
            row.setOnClickListener {
                prefs().edit().putString("recognition_language", langCodes[i]).apply()
                updateLanguageRow()
                sheet.dismiss()
            }
            list.addView(row)
        }
        sheet.setContentView(view)
        (view.parent as View).setBackgroundColor(Color.TRANSPARENT)
        sheet.show()
    }
}
