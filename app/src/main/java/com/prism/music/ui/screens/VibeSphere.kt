/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Earlier versions included an AGSL noise/sphere shader adapted from Vexorter42/Unison,
 * WaveSphere.kt at 844b796e4adbd26a4b9ff6930f8da22a6aab9edc (GPL-3.0).
 * https://github.com/Vexorter42/Unison/blob/844b796e4adbd26a4b9ff6930f8da22a6aab9edc/app/src/main/java/com/musichub/ui/WaveSphere.kt
 * Modified for Prism: theme-aware compositing, station energy, bounded animation,
 * existing PCM spectrum, Android fallback and performance/reduced-motion handling.
 */
package com.prism.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import com.prism.music.playback.Spectrum
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import kotlin.math.sin
import kotlin.math.cos
import com.prism.music.playback.VisualizerTap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prism.music.data.prefs.LightStyle
import com.prism.music.ui.theme.SpectrumPresets
import kotlin.math.exp
import com.prism.music.data.prefs.StreamVisualStyle
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import androidx.compose.material3.MaterialTheme

/** A station ambience, not an audio capture service. Reads Prism's existing audio tap only while visible. */
@Composable
internal fun VibeSphere(
    modifier: Modifier = Modifier,
    animated: Boolean,
    energy: Float,
    preview: Boolean = false,
) {
    val settings = LocalAppSettings.current
    val style = settings.streamVisualStyle
    if (style == StreamVisualStyle.OFF) return
    val dark = LocalIsDark.current
    val scheme = MaterialTheme.colorScheme
    // Follow the resolved Material accent, including dynamic wallpaper, custom accent and preview themes.
    // Vary its brightness rather than injecting unrelated hues.
    // Resolve from local settings rather than the global token singleton: a Look preview can have another palette.
    val palette = remember(settings.lightStyle, settings.spectrumPreset, settings.spectrumStops, settings.spectrumBands, dark, scheme.primary) {
        if (settings.lightStyle == LightStyle.WHITE) emptyList()
        else SpectrumPresets.light(settings.spectrumPreset, settings.spectrumStops, settings.spectrumBands).let {
            (if (dark) it.colors else it.deep).map { stop -> stop.second }
        }
    }
    val a = scheme.primary
    val b = if (palette.isEmpty()) lerp(a, scheme.onSurface, if (dark) 0.36f else 0.22f)
        else lerp(a, palette[palette.size / 2], 0.55f)
    val c = if (palette.isEmpty()) lerp(a, scheme.background, if (dark) 0.28f else 0.18f)
        else lerp(a, palette.last(), 0.55f)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val motion = !preview && !(settings.reduceMotionRequested ?: settings.reduceMotion) && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val frameInterval = 33_000_000L
    val listening = motion && animated && settings.streamSensitivity > 0f
    val spectrum = remember { Spectrum() }
    val playing = rememberUpdatedState(animated)
    val speed = rememberUpdatedState(settings.streamMotion)
    val sensitivity = rememberUpdatedState(settings.streamSensitivity)
    val time = remember { mutableFloatStateOf(7f) }
    val frame = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val level = remember { mutableFloatStateOf(0f) }
    val bands = remember { FloatArray(Spectrum.BANDS) }
    DisposableEffect(listening) {
        if (listening) VisualizerTap.listen()
        onDispose { if (listening) VisualizerTap.stop() }
    }
    LaunchedEffect(motion, listening, frameInterval) {
        if (!motion) {
            level.floatValue = 0f
            bands.fill(0f)
            frame.longValue++
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = now - last
                if (dt >= frameInterval) {
                    val seconds = (dt / 1e9f).coerceIn(0.001f, 0.1f)
                    if (listening) spectrum.update(seconds * 1000f)
                    val gain = sensitivity.value
                    val target = if (listening) spectrum.level * gain else 0f
                    level.floatValue = smoothVibe(level.floatValue, target, seconds)
                    for (i in bands.indices) bands[i] = smoothVibe(bands[i], if (listening) spectrum.bands[i] * gain else 0f, seconds)
                    // A separate clock tick keeps draw invalidation alive at zero motion speed, for the FFT.
                    time.floatValue = advanceVibeTime(time.floatValue, seconds, speed.value, playing.value)
                    frame.longValue++
                    last = now
                }
            }
        }
    }
    val bass = { frame.longValue; if (motion) level.floatValue else 0f }
    val opacity = (if (dark) 0.92f else 0.76f) * settings.streamOpacity
    if (style == StreamVisualStyle.GLOW) {
        Canvas(modifier) { drawVibeGlow(a, b, bass(), opacity, if (motion) time.floatValue else 7f, settings.streamVisualSize.fraction) }
    } else if (style == StreamVisualStyle.RING || style == StreamVisualStyle.EQUALIZER) {
        Canvas(modifier) {
            frame.longValue
            val t = if (motion) time.floatValue else 7f
            drawVibeSpectrum(a, b, bands, t, opacity, style == StreamVisualStyle.RING, preview, settings.streamVisualSize.fraction)
        }
    } else {
        YamusicWaveBackdrop(modifier, a, b, c, time, level, opacity, settings.streamVisualSize.fraction, motion)
    }
}

/** The phase advances without any audio input, even while paused. */
internal fun advanceVibeTime(time: Float, seconds: Float, speed: Float, playing: Boolean): Float {
    val dt = if (seconds.isFinite()) seconds.coerceIn(0f, 0.15f) else 0f
    val rate = if (speed.isFinite()) speed.coerceIn(0f, 2f) else 1f
    return (if (time.isFinite()) time else 0f) + dt * rate * (if (playing) 0.9f else 0.4f)
}

/** A quiet, theme-colored halo; deliberately different from the turbulent Wave renderer. */
private fun DrawScope.drawVibeGlow(a: Color, b: Color, bass: Float, opacity: Float, time: Float, scale: Float) {
    if (size.minDimension <= 0f) return
    val radius = size.minDimension * scale * (0.48f + 0.025f * bass + 0.018f * sin(time * 0.55f))
    val centerA = center + Offset(cos(time * 0.48f), sin(time * 0.48f)) * (radius * 0.16f)
    val centerB = center - Offset(cos(time * 0.48f), sin(time * 0.48f)) * (radius * 0.16f)
    listOf(a to centerA, b to centerB).forEach { (color, at) ->
        drawCircle(Brush.radialGradient(
            0f to Color.Transparent,
            0.40f to Color.Transparent,
            0.70f to color.copy(alpha = (0.24f + 0.10f * bass) * opacity),
            1f to Color.Transparent, center = at, radius = radius
        ), radius = radius, center = at)
    }
}

/** Smooth attack/release on top of the shared FFT. Also decays when the PCM tap goes quiet. */
internal fun smoothVibe(previous: Float, next: Float, seconds: Float = 1f / 30f): Float {
    val current = if (previous.isFinite()) previous.coerceIn(0f, 1f) else 0f
    val target = if (next.isFinite()) next.coerceIn(0f, 1f) else 0f
    val dt = if (seconds.isFinite()) seconds.coerceIn(0f, 0.1f) else 0f
    val tau = if (target > current) 0.075f else 0.26f
    return current + (target - current) * (1f - exp(-dt / tau))
}

/** Frequency display: 28 genuine logarithmic FFT bands, no simulated beat when audio is unavailable. */
private fun DrawScope.drawVibeSpectrum(a: Color, b: Color, bands: FloatArray, time: Float, opacity: Float, ring: Boolean, preview: Boolean, scale: Float) {
    if (size.minDimension <= 0f) return
    val n = bands.size
    val span = size.minDimension * scale
    fun value(i: Int): Float = if (preview) (0.18f + 0.52f * (0.5f + 0.5f * sin(i * 0.53f))) else bands[i]
    if (ring) {
        val r = span * (0.31f + 0.012f * sin(time * 0.55f))
        for (i in 0 until n * 2) {
            val index = if (i < n) i else 2 * n - 1 - i
            val v = value(index)
            val angle = i * 6.2831853f / (2 * n) - 1.5707963f
            val dir = Offset(cos(angle), sin(angle))
            drawLine(lerp(a, b, index.toFloat() / n), center + dir * r,
                center + dir * (r + span * (0.017f + 0.105f * v)),
                strokeWidth = (span * 0.008f).coerceAtLeast(density), cap = StrokeCap.Round, alpha = opacity * (0.30f + 0.55f * v))
        }
    } else {
        val width = span * 0.86f
        val step = width / n
        val w = step * 0.52f
        for (i in 0 until n) {
            val v = value(i)
            val h = span * (0.025f + 0.33f * v)
            val x = center.x - width / 2 + i * step + (step - w) / 2
            val top = center.y - h / 2
            val color = lerp(a, b, i.toFloat() / n)
            drawRoundRect(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.18f), color), top, top + h),
                Offset(x, top), Size(w, h), CornerRadius(w / 2), alpha = opacity * (0.38f + 0.48f * v))
        }
    }
}

