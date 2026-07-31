// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dmytro Klymentiev
package com.voxmanager.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * Voice waveform that doubles as the app's signature indicator.
 *
 * - [setMode] picks the colour: idle (violet→green), recording (red), offline (grey).
 * - [setActive] starts/stops the animation loop. The loop only runs while active,
 *   so it costs nothing at rest or when the hosting fragment is paused.
 * - [setAmplitude] feeds the live mic level (SpeechRecognizer.onRmsChanged) so the
 *   bars actually react to the voice instead of looping a fixed pattern.
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MODE_IDLE = 0
        const val MODE_RECORDING = 1
        const val MODE_OFFLINE = 2
        private const val BARS = 13
        private const val FLAT = 0.22f
    }

    private val levels = FloatArray(BARS) { FLAT }
    private val targets = FloatArray(BARS) { FLAT }
    // Each bar moves on its own cadence so the wave varies across the whole width
    // instead of all bars peaking in the middle and twitching in sync.
    private val countdown = IntArray(BARS) { Random.nextInt(2, 9) }
    private var amplitude = 0f
    private var active = false
    private var mode = MODE_IDLE

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(11f, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()
    private var gradient: LinearGradient? = null
    private var gradientMode = -1
    private var gradientH = -1

    init {
        // BlurMaskFilter (the neon glow) needs a software layer to render.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val frame = object : Runnable {
        override fun run() {
            step()
            invalidate()
            if (active) postOnAnimation(this)
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) {
            postOnAnimation(frame)
        } else {
            removeCallbacks(frame)
            for (i in 0 until BARS) { levels[i] = FLAT; targets[i] = FLAT }
            amplitude = 0f
            invalidate()
        }
    }

    fun setMode(newMode: Int) {
        if (newMode == mode) return
        mode = newMode
        gradient = null
        invalidate()
    }

    /**
     * rmsdB from SpeechRecognizer is roughly -2..10 dB; map to 0..1.
     * A noise gate ignores ambient room level so the bars rest flat in silence,
     * and a slow decay gives the wave inertia instead of twitching.
     */
    fun setAmplitude(rmsdB: Float) {
        val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        val gated = if (norm < 0.22f) 0f else norm   // below this = treated as silence
        amplitude = max(amplitude * 0.85f, gated)
    }

    private fun step() {
        for (i in 0 until BARS) {
            // Each bar picks a fresh random height on its own staggered cadence, so
            // heights differ across the full width and bars don't move together.
            if (--countdown[i] <= 0) {
                val peak = FLAT + amplitude * (0.3f + Random.nextFloat() * 0.7f)
                targets[i] = peak.coerceIn(FLAT, 1f)
                countdown[i] = Random.nextInt(3, 11)
            }
            levels[i] += (targets[i] - levels[i]) * 0.2f   // smooth, inert follow
        }
        amplitude *= 0.93f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (gradient == null || gradientMode != mode || gradientH != height) {
            gradient = when (mode) {
                MODE_RECORDING -> LinearGradient(0f, 0f, 0f, h, 0xFFFB7185.toInt(), 0xFFEF4444.toInt(), Shader.TileMode.CLAMP)
                MODE_OFFLINE -> null
                else -> LinearGradient(0f, 0f, 0f, h, 0xFF8B5CF6.toInt(), 0xFF34D399.toInt(), Shader.TileMode.CLAMP)
            }
            gradientMode = mode
            gradientH = height
        }
        val glow = mode != MODE_OFFLINE
        if (mode == MODE_OFFLINE) {
            paint.shader = null
            paint.color = 0xFF4A4A66.toInt()
        } else {
            paint.shader = gradient
            glowPaint.shader = gradient
            glowPaint.alpha = 130
        }

        val unit = w / (BARS * 2f - 1f)   // bar width == gap width
        val r = unit / 2f
        for (i in 0 until BARS) {
            val bh = max(unit, levels[i] * h)
            val left = i * unit * 2f
            val top = (h - bh) / 2f
            rect.set(left, top, left + unit, top + bh)
            if (glow) canvas.drawRoundRect(rect, r, r, glowPaint)  // neon halo
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
    }
}
