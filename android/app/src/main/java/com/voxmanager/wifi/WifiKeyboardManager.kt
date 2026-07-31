// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.wifi

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Talks to the PC server with mutual HMAC authentication.
 *
 *  - Every request is signed:  method \n path \n ts \n nonce \n body
 *  - The server signs its reply: resp \n ts \n nonce \n body  (request's ts+nonce)
 *  - We only trust a reply whose signature verifies with our secret, so the app
 *    never types into a stranger's / old / rogue server that lacks the secret.
 */
class WifiKeyboardManager {

    companion object {
        private const val TAG = "WifiKeyboardManager"
        private const val HTTP_PORT = 8765
        // v2 discovery port (8767) — separate from v1's 8766 so both servers coexist.
        private const val DISCOVERY_PORT = 8767
        private const val DISCOVERY_MAGIC = "VOXMANAGER_DISCOVER"
        private const val DISCOVERY_RESPONSE_PREFIX = "VOXMANAGER_SERVER:"
        private const val TIMEOUT_MS = 5000
        // Short timeout for the parallel "restore known servers" probes: a wrong/away
        // network (e.g. the home PC's IP while at work) must fail fast, not stall 5s.
        private const val PROBE_TIMEOUT_MS = 1500
        private const val DISCOVERY_TIMEOUT_MS = 3000L
        private val rng = SecureRandom()
    }

    private var serverIp: String? = null
    private var serverPort: Int = HTTP_PORT
    private var secret: String? = null

    // Server-clock offset (server_ms - local_ms), learned once per IP via /time, so
    // signed requests are stamped in SERVER time and the server can keep a tight
    // replay window without rejecting a phone whose clock drifts.
    @Volatile private var clockOffsetMs: Long = 0L
    private var syncedForIp: String? = null

    /** Hostname reported by the last server we discovered (for UI display). */
    var discoveredHostname: String? = null
        private set

    /** Title of the PC window that had focus at the last verifyConnection — i.e. where
     *  dictated text will land. Shown to the user as a typing-target awareness cue. */
    var lastActiveWindow: String? = null
        private set

    fun setSecret(secret: String?) {
        this.secret = secret
    }

    fun setServerIp(ip: String) {
        serverIp = ip.trim()
        Log.d(TAG, "Server IP set to: $serverIp")
    }

    fun getServerIp(): String? = serverIp

    /** Port last learned from discovery (falls back to the default). */
    fun serverPortOrDefault(): Int = serverPort

    fun isConfigured(): Boolean = !serverIp.isNullOrBlank()

    // ------------------------------------------------------------- crypto

    private fun hmacHex(secretHex: String, message: String): String {
        val key = hexToBytes(secretHex)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it.toInt() and 0xff)  // mask: avoid sign-extension on bytes >= 0x80
        }
    }

    private fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) +
                    Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun randomNonce(): String {
        val b = ByteArray(16)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Constant-time-ish compare on equal-length hex strings. */
    private fun sigEquals(a: String?, b: String): Boolean {
        if (a == null || a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    // -- transport encryption (mirrors the server): AES-256-GCM payloads keyed by
    //    HKDF-SHA256(secret). Wire = base64(nonce[12] || ciphertext+tag).
    private fun transportKey(secretHex: String): ByteArray {
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(hexToBytes(secretHex),
                "voxmanager-transport-v1".toByteArray(Charsets.UTF_8), ByteArray(0)))
            generateBytes(out, 0, 32)
        }
        return out
    }

    private fun encryptPayload(key: ByteArray, plain: String): String {
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))  // ct||tag
        return Base64.encodeToString(nonce + ct, Base64.NO_WRAP)
    }

    private fun decryptPayload(key: ByteArray, b64: String): String {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val nonce = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ------------------------------------------------------------- discovery

    /** One PC seen on the network during a scan. */
    data class DiscoveredPc(val ip: String, val port: Int, val hostname: String)

    /** Collect ALL servers that answer the broadcast within a short window. */
    suspend fun discoverServers(): List<DiscoveredPc> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredPc>()
        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 500
            val message = DISCOVERY_MAGIC.toByteArray()
            socket.send(DatagramPacket(message, message.size,
                InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            val deadline = System.currentTimeMillis() + 2500
            val buffer = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buffer, buffer.size)
                    socket.receive(pkt)
                    val resp = String(pkt.data, 0, pkt.length)
                    if (resp.startsWith(DISCOVERY_RESPONSE_PREFIX)) {
                        val parts = resp.removePrefix(DISCOVERY_RESPONSE_PREFIX).split(":")
                        if (parts.isNotEmpty()) {
                            val ip = parts[0]
                            val port = parts.getOrNull(1)?.toIntOrNull() ?: HTTP_PORT
                            val host = if (parts.size > 3) parts.drop(3).joinToString(":")
                                else parts.getOrNull(2) ?: ""
                            found["$ip:$port"] = DiscoveredPc(ip, port, host)
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // keep listening until the deadline
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "discoverServers failed: ${e.message}")
        }
        found.values.toList()
    }

    /** Discover server via UDP broadcast. Returns its IP, or null. */
    suspend fun discoverServer(): String? = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = DISCOVERY_TIMEOUT_MS.toInt()

            val message = DISCOVERY_MAGIC.toByteArray()
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(message, message.size, broadcastAddress, DISCOVERY_PORT))

            val buffer = ByteArray(1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(responsePacket)
                val response = String(responsePacket.data, 0, responsePacket.length)
                Log.d(TAG, "Discovery response: $response")
                if (response.startsWith(DISCOVERY_RESPONSE_PREFIX)) {
                    // VOXMANAGER_SERVER:<ip>:<port>:<hostname...>
                    val parts = response.removePrefix(DISCOVERY_RESPONSE_PREFIX).split(":")
                    if (parts.isNotEmpty()) {
                        val ip = parts[0]
                        serverPort = parts.getOrNull(1)?.toIntOrNull() ?: HTTP_PORT
                        discoveredHostname = if (parts.size > 3) {
                            parts.drop(3).joinToString(":")
                        } else parts.getOrNull(2)
                        serverIp = ip
                        socket.close()
                        return@withContext ip
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "No discovery response: ${e.message}")
            }
            socket.close()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------- signed I/O

    private data class SignedResponse(val code: Int, val body: String, val verified: Boolean)

    /**
     * One-time clock sync per server IP: hit the unsigned /time beacon and remember
     * the offset so signed requests can be stamped in SERVER time. Best-effort — if
     * it fails we just sign with our own clock (offset 0).
     */
    private fun ensureTimeSynced(ip: String, port: Int) {
        if (syncedForIp == ip) return
        try {
            val c = (URL("http://$ip:$port/time").openConnection() as HttpURLConnection)
            c.requestMethod = "GET"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            val t0 = System.currentTimeMillis()
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val serverMs = JSONObject(body).optLong("server_ms", 0L)
            if (serverMs > 0L) {
                // Approximate the server-handle instant as t0 + rtt/2.
                val rtt = System.currentTimeMillis() - t0
                clockOffsetMs = serverMs + rtt / 2 - System.currentTimeMillis()
                syncedForIp = ip
            }
        } catch (e: Exception) {
            Log.d(TAG, "time sync failed (non-fatal): ${e.message}")
        }
    }

    /** Perform one signed request; verify the server's response signature. */
    private fun signedRequest(method: String, path: String, bodyJson: String?): SignedResponse? {
        val ip = serverIp ?: return null
        val sec = secret ?: return null
        ensureTimeSynced(ip, serverPort)
        val tkey = transportKey(sec)
        val ts = (System.currentTimeMillis() + clockOffsetMs).toString()
        val nonce = randomNonce()
        // Encrypt the body, then sign the CIPHERTEXT (the server verifies + decrypts).
        val wireBody = (bodyJson ?: "").let { if (it.isEmpty()) "" else encryptPayload(tkey, it) }
        val reqSig = hmacHex(sec, listOf(method, path, ts, nonce, wireBody).joinToString("\n"))

        return try {
            val connection = (URL("http://$ip:$serverPort$path").openConnection() as HttpURLConnection)
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("X-Ts", ts)
            connection.setRequestProperty("X-Nonce", nonce)
            connection.setRequestProperty("X-Sig", reqSig)
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(connection.outputStream).use { it.write(wireBody); it.flush() }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val respBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val respSig = connection.getHeaderField("X-Sig")
            connection.disconnect()

            // Signature is over the ciphertext; verify first, then decrypt the payload.
            val expected = hmacHex(sec, listOf("resp", ts, nonce, respBody).joinToString("\n"))
            val verified = sigEquals(respSig, expected)
            val plain = if (code in 200..299 && verified && respBody.isNotEmpty())
                decryptPayload(tkey, respBody) else respBody
            SignedResponse(code, plain, verified)
        } catch (e: Exception) {
            Log.e(TAG, "$method $path failed: ${e.message}")
            null
        }
    }

    /**
     * Verify we are talking to OUR paired PC. Returns its hostname on success
     * (200 + valid response signature), null otherwise.
     */
    suspend fun verifyConnection(): String? = withContext(Dispatchers.IO) {
        val r = signedRequest("GET", "/", null) ?: return@withContext null
        if (r.code == 200 && r.verified) {
            try {
                val obj = JSONObject(r.body)
                lastActiveWindow = obj.optString("active_window", "").ifBlank { null }
                obj.optString("hostname", "")
            } catch (e: Exception) {
                ""
            }
        } else {
            if (r.code == 200 && !r.verified) {
                Log.w(TAG, "Server replied 200 but signature did NOT verify -> not our PC")
            }
            null
        }
    }

    /**
     * Stateless reachability + authenticity probe for ONE candidate (ip, port, secret).
     * Does the full signed handshake using only locals, so many probes can run
     * concurrently without racing the instance fields. Returns the server hostname
     * (possibly "") if it is OUR paired PC, or null otherwise. Uses a short timeout so
     * an unreachable candidate fails fast.
     */
    suspend fun probe(ip: String, port: Int, secret: String): String? = withContext(Dispatchers.IO) {
        try {
            val tkey = transportKey(secret)
            // Best-effort local clock sync (own variable, never touches instance state).
            var offset = 0L
            try {
                val c = (URL("http://$ip:$port/time").openConnection() as HttpURLConnection)
                c.requestMethod = "GET"; c.connectTimeout = PROBE_TIMEOUT_MS; c.readTimeout = PROBE_TIMEOUT_MS
                val t0 = System.currentTimeMillis()
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val serverMs = JSONObject(body).optLong("server_ms", 0L)
                if (serverMs > 0L) offset = serverMs + (System.currentTimeMillis() - t0) / 2 - System.currentTimeMillis()
            } catch (e: Exception) { /* sign with local clock */ }

            val ts = (System.currentTimeMillis() + offset).toString()
            val nonce = randomNonce()
            val reqSig = hmacHex(secret, listOf("GET", "/", ts, nonce, "").joinToString("\n"))
            val conn = (URL("http://$ip:$port/").openConnection() as HttpURLConnection)
            conn.requestMethod = "GET"; conn.connectTimeout = PROBE_TIMEOUT_MS; conn.readTimeout = PROBE_TIMEOUT_MS
            conn.setRequestProperty("X-Ts", ts)
            conn.setRequestProperty("X-Nonce", nonce)
            conn.setRequestProperty("X-Sig", reqSig)
            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val respSig = conn.getHeaderField("X-Sig")
            conn.disconnect()
            if (code != 200) return@withContext null
            val expected = hmacHex(secret, listOf("resp", ts, nonce, respBody).joinToString("\n"))
            if (!sigEquals(respSig, expected)) return@withContext null   // not our PC
            val plain = if (respBody.isNotEmpty()) decryptPayload(tkey, respBody) else respBody
            try { JSONObject(plain).optString("hostname", "") } catch (e: Exception) { "" }
        } catch (e: Exception) {
            null
        }
    }

    /** Bind the manager to a resolved server so signed I/O (sendText/verifyConnection) targets it. */
    fun bind(ip: String, port: Int, secret: String) {
        serverIp = ip.trim()
        serverPort = port
        this.secret = secret
    }

    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        val r = signedRequest("POST", "/", JSONObject().put("text", text).toString())
        r != null && r.code == 200 && r.verified
    }

    suspend fun sendKey(key: String): Boolean = withContext(Dispatchers.IO) {
        val r = signedRequest("POST", "/", JSONObject().put("key", key).toString())
        r != null && r.code == 200 && r.verified
    }

    // ------------------------------------------------------------- pairing

    /**
     * Ask the PC to pop its pairing-code window, so the user doesn't have to click
     * the tray icon. Best-effort and unsigned (it only surfaces a code; the secret
     * is still gated behind the code). Failures are non-fatal.
     */
    suspend fun requestPairingCode(ip: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            val connection = (URL("http://$ip:$port/request-pair").openConnection() as HttpURLConnection)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream).use { it.write("{}"); it.flush() }
            connection.responseCode  // fire the request
            connection.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "requestPairingCode failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Exchange a 6-digit code for the machine secret (one time). The secret is
     * delivered ENCRYPTED: we send an ephemeral X25519 public key with the code, the
     * server seals the secret with X25519 ECDH + HKDF-SHA256 + AES-256-GCM, and we
     * open it here. A passive Wi-Fi sniffer never sees the secret in clear. The wire
     * format mirrors the Python server's seal_secret_for_phone byte-for-byte.
     * Returns (secret, hostname) on success, null on failure.
     */
    suspend fun pairWithCode(ip: String, port: Int, code: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                // Ephemeral X25519 keypair for this pairing only.
                val ephPriv = X25519PrivateKeyParameters(rng)
                val ephPub = ByteArray(X25519PrivateKeyParameters.KEY_SIZE)
                ephPriv.generatePublicKey().encode(ephPub, 0)

                val connection = (URL("http://$ip:$port/pair").openConnection() as HttpURLConnection)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "application/json")
                val payload = JSONObject()
                    .put("code", code)
                    .put("eph_pub", bytesToHex(ephPub))
                    .toString()
                OutputStreamWriter(connection.outputStream).use { it.write(payload); it.flush() }

                val codeResp = connection.responseCode
                val stream = if (codeResp == 200) connection.inputStream else connection.errorStream
                val respBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
                connection.disconnect()

                if (codeResp != 200) {
                    Log.e(TAG, "Pair failed ($codeResp): $respBody")
                    return@withContext null
                }
                val obj = JSONObject(respBody)
                val serverPub = hexToBytes(obj.optString("server_pub", ""))
                val nonce = hexToBytes(obj.optString("nonce", ""))
                val ct = hexToBytes(obj.optString("ct", ""))
                val host = obj.optString("hostname", "")
                if (serverPub.isEmpty() || nonce.isEmpty() || ct.isEmpty()) {
                    Log.e(TAG, "Pair response missing key-exchange fields")
                    return@withContext null
                }

                // X25519 ECDH -> HKDF-SHA256 -> AES-256-GCM open.
                val shared = ByteArray(X25519PrivateKeyParameters.KEY_SIZE)
                X25519Agreement().run {
                    init(ephPriv)
                    calculateAgreement(X25519PublicKeyParameters(serverPub, 0), shared, 0)
                }
                val key = ByteArray(32)
                HKDFBytesGenerator(SHA256Digest()).apply {
                    init(HKDFParameters(shared, "voxmanager-pair-v3".toByteArray(Charsets.UTF_8),
                        ("secret-wrap|" + code).toByteArray(Charsets.UTF_8)))
                    generateBytes(key, 0, 32)
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(ephPub + serverPub)   // AAD = phone_pub || server_pub
                }
                val secret = String(cipher.doFinal(ct), Charsets.UTF_8)
                if (secret.isNotEmpty()) secret to host else null
            } catch (e: Exception) {
                Log.e(TAG, "Pair request failed: ${e.message}")
                null
            }
        }
}
