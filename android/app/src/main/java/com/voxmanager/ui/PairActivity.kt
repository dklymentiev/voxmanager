// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.voxmanager.R
import com.voxmanager.data.PairedPc
import com.voxmanager.data.ServerStore
import com.voxmanager.wifi.WifiKeyboardManager
import kotlinx.coroutines.launch

/**
 * Full-screen pairing flow (per design2): search for the PC, then enter the
 * 6-digit code into tall cells. Replaces the old stock dialog.
 */
class PairActivity : AppCompatActivity() {

    private lateinit var store: ServerStore
    private val wifi = WifiKeyboardManager()

    private lateinit var searchView: View
    private lateinit var listView: View
    private lateinit var pcListContainer: LinearLayout
    private lateinit var emptyList: TextView
    private lateinit var getAppLink: TextView
    private lateinit var codeView: View
    private lateinit var boxes: List<TextView>
    private lateinit var input: EditText
    private lateinit var codeError: TextView

    private var ip: String? = null
    private var port: Int = 8765
    private var hostname: String? = null
    private var pairing = false   // guards against double-submit (manual tap + auto-submit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)

        store = ServerStore(this)
        searchView = findViewById(R.id.searchView)
        listView = findViewById(R.id.listView)
        pcListContainer = findViewById(R.id.pcListContainer)
        emptyList = findViewById(R.id.emptyList)
        getAppLink = findViewById(R.id.getAppLink)
        getAppLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.desktop_url))))
            } catch (_: Exception) { /* no browser available */ }
        }
        codeView = findViewById(R.id.codeView)
        boxes = listOf(R.id.box0, R.id.box1, R.id.box2, R.id.box3, R.id.box4, R.id.box5)
            .map { findViewById(it) }
        input = findViewById(R.id.codeInput)
        codeError = findViewById(R.id.codeError)

        findViewById<ImageView>(R.id.backButton).setOnClickListener { onBack() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener { submit() }
        findViewById<View>(R.id.enterIpRow).setOnClickListener { promptForIp() }
        findViewById<Button>(R.id.rescanButton).setOnClickListener { discover() }

        renderBoxes("")
        input.addTextChangedListener {
            renderBoxes(input.text.toString())
            codeError.visibility = View.GONE
            if (input.text.length == 6) submit()   // auto-submit on the 6th digit
        }

        discover()
    }

    private fun discover() {
        searchView.visibility = View.VISIBLE
        listView.visibility = View.GONE
        codeView.visibility = View.GONE
        lifecycleScope.launch {
            val pcs = wifi.discoverServers()
            runOnUiThread { showList(pcs) }
        }
    }

    private fun showList(pcs: List<WifiKeyboardManager.DiscoveredPc>) {
        searchView.visibility = View.GONE
        codeView.visibility = View.GONE
        listView.visibility = View.VISIBLE
        pcListContainer.removeAllViews()

        // Only NEW computers belong here — already-paired ones are in the main list
        // and connect without a code, so showing them (and asking for a code) is wrong.
        val paired = store.getPaired()
        val newPcs = pcs.filter { pc ->
            paired.none { it.ip == pc.ip || (it.hostname.isNotBlank() && it.hostname.equals(pc.hostname, true)) }
        }

        emptyList.visibility = if (newPcs.isEmpty()) View.VISIBLE else View.GONE
        emptyList.text = if (pcs.isNotEmpty() && newPcs.isEmpty())
            getString(R.string.pair_all_paired) else getString(R.string.pair_none_found)

        // Nothing on the network at all -> the user may not have installed the PC app.
        // Point them to where to download it (the all-paired case doesn't need this).
        getAppLink.visibility = if (pcs.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (pc in newPcs) {
            val row = inflater.inflate(R.layout.item_pc, pcListContainer, false)
            row.findViewById<TextView>(R.id.pcName).text = pc.hostname.ifBlank { pc.ip }
            row.findViewById<TextView>(R.id.pcStatus).text = pc.ip
            row.findViewById<ImageView>(R.id.pcIcon).setColorFilter(0xFF34D399.toInt())
            row.setOnClickListener {
                ip = pc.ip; port = pc.port; hostname = pc.hostname
                showCodeEntry()
            }
            pcListContainer.addView(row)
        }
    }

    private fun onBack() {
        if (codeView.visibility == View.VISIBLE) discover()  // back to the list
        else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBack()
    }

    private fun promptForIp() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_input, null)
        view.findViewById<TextView>(R.id.inputTitle).text = getString(R.string.pair_enter_ip)
        view.findViewById<TextView>(R.id.inputSub).text = getString(R.string.add_ip_hint)
        val field = view.findViewById<EditText>(R.id.inputField)
        field.hint = getString(R.string.ip_hint)
        view.findViewById<Button>(R.id.btnOk).text = getString(android.R.string.ok)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnOk).setOnClickListener {
            val v = field.text.toString().trim()
            if (v.isNotEmpty()) {
                ip = v; port = 8765; hostname = null
                dialog.dismiss(); showCodeEntry()
            }
        }
        dialog.show()
    }

    private fun showCodeEntry() {
        searchView.visibility = View.GONE
        listView.visibility = View.GONE
        codeView.visibility = View.VISIBLE

        // Ask the PC to pop its code window automatically, so the user doesn't have
        // to click the tray icon. Best-effort; manual tray is still the fallback.
        ip?.let { targetIp ->
            lifecycleScope.launch { wifi.requestPairingCode(targetIp, port) }
        }
        val title = findViewById<TextView>(R.id.codeTitle)
        title.text =
            if (!hostname.isNullOrBlank()) getString(R.string.pair_with, hostname)
            else getString(R.string.pair_device)
        title.post {
            val w = title.paint.measureText(title.text.toString()).coerceAtLeast(1f)
            title.paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.parseColor("#A8B1FF"), Color.WHITE, Color.parseColor("#BFF3DD")),
                floatArrayOf(0f, 0.5f, 0.9f), Shader.TileMode.CLAMP
            )
            title.invalidate()
        }
        input.requestFocus()
        input.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun renderBoxes(s: String) {
        for (i in 0..5) {
            boxes[i].text = s.getOrNull(i)?.toString() ?: ""
            boxes[i].setBackgroundResource(
                if (i < s.length || (i == s.length && s.length < 6))
                    R.drawable.bg_code_box_active else R.drawable.bg_code_box
            )
        }
    }

    private fun submit() {
        if (pairing) return
        val code = input.text.toString().trim()
        if (code.length != 6) {
            codeError.text = getString(R.string.pair_code_len)
            codeError.visibility = View.VISIBLE
            return
        }
        val targetIp = ip ?: return
        pairing = true
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        lifecycleScope.launch {
            val result = wifi.pairWithCode(targetIp, port, code)
            runOnUiThread {
                pairing = false
                if (result != null) {
                    val (secret, host) = result
                    store.addPaired(PairedPc(secret, host, targetIp))
                    store.add(targetIp)
                    store.setSelected(targetIp)
                    Toast.makeText(this@PairActivity, getString(R.string.paired_ok), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    for (b in boxes) b.setBackgroundResource(R.drawable.bg_code_box_error)
                    codeError.text = getString(R.string.pair_failed)
                    codeError.visibility = View.VISIBLE
                }
            }
        }
    }
}
