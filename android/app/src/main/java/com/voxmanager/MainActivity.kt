// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.voxmanager.data.PairedPc
import com.voxmanager.data.ServerStore
import com.voxmanager.ui.KeyboardFragment
import com.voxmanager.ui.OnboardingActivity
import com.voxmanager.ui.SettingsActivity
import com.voxmanager.wifi.WifiKeyboardManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val HTTP_PORT = 8765   // default server port for the restore phase
    }

    private lateinit var wifiManager: WifiKeyboardManager
    private lateinit var store: ServerStore
    private var keyboardFragment: KeyboardFragment? = null

    private var isConnected = false
    private var connecting = false
    private var reconnecting = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiManager = WifiKeyboardManager()
        store = ServerStore(this)

        keyboardFragment = KeyboardFragment().also { it.setWifiManager(wifiManager) }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, keyboardFragment!!)
            .commit()

        registerNetworkCallback()

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val firstRun = store.pairedCount() == 0 &&
            !getSharedPreferences("voice_settings", Context.MODE_PRIVATE).getBoolean("seen_welcome", false)
        if (!micGranted || firstRun) {
            // Onboarding self-drives: mic rationale -> request -> welcome -> done.
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else {
            connect()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate connection when returning to the app (e.g. after picking a
        // server in settings) — also covers the first foreground.
        if (!isConnected) connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { cb -> connectivityManager?.unregisterNetworkCallback(cb) }
    }

    /** Called by the fragment when the orb is tapped while disconnected. */
    fun requestConnect() {
        Log.d(TAG, "requestConnect (manual tap), paired=${store.pairedCount()}")
        if (store.pairedCount() == 0) {
            // Not paired with any PC yet -> take the user to pairing.
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            // A manual tap clears a user "Disconnect" AND any stuck in-flight guard,
            // so tapping the wave always forces a fresh reconnect attempt.
            store.setAutoConnectDisabled(false)
            connecting = false
            connect()
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network changed/available (e.g. home -> work WiFi): re-resolve.
                runOnUiThread { connect() }
            }
            override fun onLost(network: Network) {
                runOnUiThread {
                    if (isConnected) {
                        isConnected = false
                        reconnecting = true
                        keyboardFragment?.setConnecting(true)
                    }
                }
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun connect() {
        if (connecting) return
        if (store.isAutoConnectDisabled()) {   // user chose Disconnect
            reconnecting = false
            setConnected(false, null)
            return
        }
        connecting = true
        val wasLink = isConnected || reconnecting
        keyboardFragment?.setConnecting(wasLink)   // always show we're establishing a link
        lifecycleScope.launch {
            // resolveServer never throws (its IO is guarded), but catch defensively so
            // the `connecting` flag is ALWAYS cleared — otherwise a stuck flag would
            // block every future reconnect attempt.
            val resolved = try { resolveServer() } catch (e: Exception) {
                Log.e(TAG, "resolveServer failed", e); null
            }
            Log.d(TAG, "connect resolved=${resolved != null}")
            runOnUiThread {
                connecting = false
                reconnecting = false
                if (resolved != null) {
                    val (ip, label) = resolved
                    store.add(ip)
                    store.setSelected(ip)
                    setConnected(true, label)
                } else {
                    if (wasLink) keyboardFragment?.showConnectionLost()
                    else Toast.makeText(this@MainActivity, getString(R.string.connect_failed_toast), Toast.LENGTH_LONG).show()
                    setConnected(false, null)
                }
            }
        }
    }

    private data class ResolveHit(val ip: String, val port: Int, val pc: PairedPc, val host: String)

    /**
     * Find a reachable, PAIRED server. Order matters for a phone that roams between
     * networks (home <-> work), each with its own paired PC:
     *  1. RESTORE: probe every known server's last IP directly and IN PARALLEL, so the
     *     one on the current network answers in ~100ms while the away one fails fast —
     *     no network scan, no 5s serial stalls.
     *  2. FALL BACK to a full UDP discovery scan only if no known IP answered (first
     *     run on a new network, or the server's IP changed).
     * Each candidate is authenticated with the paired secret (signed response), so we
     * never type into a stranger's / rogue PC. Returns (ip, hostnameLabel).
     */
    private suspend fun resolveServer(): Pair<String, String>? {
        val paired = store.getPaired()
        if (paired.isEmpty()) return null // not paired yet -> user pairs in settings

        // 1) Restore: known IPs (each PC's own last IP + the IP history), default port.
        val knownIps = (paired.mapNotNull { it.ip.ifBlank { null } } + store.orderedIps()).distinct()
        resolveFrom(knownIps.map { it to HTTP_PORT }, paired)?.let { return it }

        // 2) Fall back to a full scan; discovery carries the real port (e.g. non-8765).
        val discovered = wifiManager.discoverServers().map { it.ip to it.port }
        return resolveFrom(discovered, paired)
    }

    /** Probe every (target x paired-secret) concurrently; bind + return the first that
     *  authenticates. Refreshes that PC's stored IP so the next restore is a direct hit. */
    private suspend fun resolveFrom(
        targets: List<Pair<String, Int>>,
        paired: List<PairedPc>,
    ): Pair<String, String>? {
        if (targets.isEmpty()) return null
        val hit = coroutineScope {
            targets.flatMap { (ip, port) ->
                paired.map { pc ->
                    async {
                        wifiManager.probe(ip, port, pc.secret)?.let { ResolveHit(ip, port, pc, it) }
                    }
                }
            }.awaitAll().filterNotNull().firstOrNull()
        } ?: return null

        wifiManager.bind(hit.ip, hit.port, hit.pc.secret)
        if (hit.pc.ip != hit.ip) store.addPaired(hit.pc.copy(ip = hit.ip))  // keep IP fresh
        return hit.ip to hit.host.ifBlank { hit.pc.hostname.ifBlank { hit.ip } }
    }

    private fun setConnected(connected: Boolean, ip: String?) {
        isConnected = connected
        keyboardFragment?.setConnected(connected, ip)
    }
}
