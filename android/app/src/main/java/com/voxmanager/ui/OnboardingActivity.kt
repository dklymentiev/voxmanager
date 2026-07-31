// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.voxmanager.R
import com.voxmanager.data.ServerStore

/**
 * Self-driving first-run flow: mic rationale -> request (-> denied) -> welcome -> done.
 * It figures out the next step itself, so callers just launch it.
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Step { MIC, MIC_DENIED, WELCOME, PC_SERVER }
    private var step = Step.MIC

    private lateinit var circle: FrameLayout
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var cta: Button
    private lateinit var secondary: TextView

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) refresh() else showMicDenied() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        circle = findViewById(R.id.heroCircle)
        icon = findViewById(R.id.heroIcon)
        title = findViewById(R.id.heroTitle)
        sub = findViewById(R.id.heroSub)
        cta = findViewById(R.id.heroCta)
        secondary = findViewById(R.id.heroSecondary)
        secondary.text = getString(R.string.onb_skip)
        secondary.setOnClickListener { finish() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Don't override the denied screen with the rationale while still ungranted.
        if (step == Step.MIC_DENIED && !micGranted()) return
        // The PC step is reached via a button, not by refresh()'s routing — and
        // returning here from the browser must NOT close onboarding (needWelcome is
        // already false by then). Hold the user on the PC step.
        if (step == Step.PC_SERVER) return
        refresh()
    }

    /** Pick and render the next step. */
    private fun refresh() {
        when {
            !micGranted() -> showMicRationale()
            needWelcome() -> showWelcome()
            else -> finish()
        }
    }

    private fun micGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun prefs() = getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    private fun needWelcome() =
        ServerStore(this).pairedCount() == 0 && !prefs().getBoolean("seen_welcome", false)

    // ---- steps ----

    private fun showMicRationale() {
        step = Step.MIC
        circle.setBackgroundResource(R.drawable.bg_circle)
        icon.setImageResource(R.drawable.ic_mic)
        icon.setColorFilter(0xFFCBBDFF.toInt())
        title.text = getString(R.string.onb_mic_title)
        title.paint.shader = null
        sub.text = getString(R.string.onb_mic_sub)
        cta.text = getString(R.string.onb_mic_cta)
        cta.setOnClickListener { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        secondary.visibility = View.VISIBLE
    }

    private fun showMicDenied() {
        step = Step.MIC_DENIED
        circle.setBackgroundResource(R.drawable.bg_circle_warn)
        icon.setImageResource(R.drawable.ic_mic_off)
        icon.clearColorFilter()
        title.text = getString(R.string.onb_denied_title)
        title.paint.shader = null
        sub.text = getString(R.string.onb_denied_sub)
        cta.text = getString(R.string.onb_denied_cta)
        cta.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
        secondary.visibility = View.VISIBLE
    }

    private fun showWelcome() {
        step = Step.WELCOME
        prefs().edit().putBoolean("seen_welcome", true).apply()
        circle.setBackgroundResource(R.drawable.bg_circle)
        icon.setImageResource(R.drawable.ic_mic)
        icon.setColorFilter(0xFFCBBDFF.toInt())
        title.text = getString(R.string.app_name)
        gradientTitle(title)
        sub.text = getString(R.string.onb_welcome_sub)
        cta.text = getString(R.string.onb_welcome_cta)
        cta.setOnClickListener { showPcServer() }   // explain the PC server before pairing
        secondary.text = getString(R.string.onb_skip)
        secondary.setOnClickListener { finish() }
        secondary.visibility = View.VISIBLE
    }

    /** Tell the user Vox Manager needs a desktop app on their PC, and where to get it.
     *  Without this, a fresh Play install would hit pairing with no server to find. */
    private fun showPcServer() {
        step = Step.PC_SERVER
        circle.setBackgroundResource(R.drawable.bg_circle)
        icon.setImageResource(R.drawable.ic_mic)
        icon.setColorFilter(0xFFCBBDFF.toInt())
        title.text = getString(R.string.onb_pc_title)
        title.paint.shader = null
        sub.text = getString(R.string.onb_pc_sub)
        cta.text = getString(R.string.onb_pc_cta)
        cta.setOnClickListener { openDesktopSite() }
        secondary.text = getString(R.string.onb_pc_have)
        secondary.setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
            finish()
        }
        secondary.visibility = View.VISIBLE
    }

    private fun openDesktopSite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.desktop_url))))
        } catch (_: Exception) { /* no browser available */ }
    }

    private fun gradientTitle(tv: TextView) {
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
}
