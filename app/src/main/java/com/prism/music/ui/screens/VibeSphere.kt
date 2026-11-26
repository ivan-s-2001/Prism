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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val motion = !preview && !settings.reduceMotion && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val frameInterval = if (settings.performanceMode) 66_000_000L else 30_000_000L
    val listening = motion && animated && settings.streamSensitivity > 0f
    val spectrum = remember { Spectrum() }
    val playing = rememberUpdatedState(animated)
    val speed = rememberUpdatedState(settings.streamMotion)
    val sensitivity = rememberUpdatedState(settings.streamSensitivity)
    val time = remember { mutableFloatStateOf(7f) }
    val frame = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val level = remember { mutableFloatStateOf(0f) }
    val bands = remember { FloatArray(Spectrum.BANDS) }
    val group = remember { FloatArray(4) }
    DisposableEffect(listening) {
        if (listening) VisualizerTap.listen()
        onDispose { if (listening) VisualizerTap.stop() }
    }
    LaunchedEffect(motion, listening, frameInterval) {
        if (!motion) {
            level.floatValue = 0f
            bands.fill(0f)
            group.fill(0f)
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
                    for (i in group.indices) {
                        var sum = 0f
                        val start = i * 7
                        for (j in start until start + 7) sum += bands[j]
                        group[i] = sum / 7f
                    }
                    // A separate clock tick keeps draw invalidation alive at zero motion speed, for the FFT.
                    time.floatValue = advanceVibeTime(time.floatValue, seconds, speed.value, playing.value)
                    frame.longValue++
                    last = now
                }
            }
        }
    }
    val bass = { frame.longValue; if (motion) level.floatValue else 0f }
    val opacity = if (dark) 0.92f else 0.76f
    if (style == StreamVisualStyle.GLOW) {
        Canvas(modifier) { drawVibeGlow(a, b, bass(), opacity, if (motion) time.floatValue else 7f) }
    } else if (style == StreamVisualStyle.RING || style == StreamVisualStyle.EQUALIZER) {
        Canvas(modifier) {
            frame.longValue
            val t = if (motion) time.floatValue else 7f
            drawVibeSpectrum(a, b, bands, t, opacity, style == StreamVisualStyle.RING, preview)
        }
    } else {
        Canvas(modifier) {
            frame.longValue
            drawLiquidVibe(a, b, c, bands, bass(), opacity, if (motion) time.floatValue else 7f,
                energy.coerceIn(0f, 1f), settings.performanceMode)
        }
    }
}

/** The phase advances without any audio input, even while paused. */
internal fun advanceVibeTime(time: Float, seconds: Float, speed: Float, playing: Boolean): Float {
    val dt = if (seconds.isFinite()) seconds.coerceIn(0f, 0.15f) else 0f
    val rate = if (speed.isFinite()) speed.coerceIn(0f, 2f) else 1f
    return (if (time.isFinite()) time else 0f) + dt * rate * (if (playing) 2.8f else 1.15f)
}

/** A quiet, theme-colored halo; deliberately different from the turbulent Wave renderer. */
private fun DrawScope.drawVibeGlow(a: Color, b: Color, bass: Float, opacity: Float, time: Float) {
    if (size.minDimension <= 0f) return
    val radius = size.minDimension * (0.38f + 0.025f * bass + 0.018f * sin(time * 0.55f))
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

/** An asymmetric field of translucent pigment, deformed by real frequency bands.
 * Fixed-size geometry, no runtime shader compiler or mutable GPU uniforms. */
private fun DrawScope.drawLiquidVibe(a: Color, b: Color, c: Color, bands: FloatArray,
    bass: Float, opacity: Float, time: Float, energy: Float, economical: Boolean) {
    if (size.minDimension <= 0f) return
    val span = size.minDimension
    val rx = minOf(size.width * 0.46f, span * 0.64f)
    val ry = minOf(size.height * 0.44f, span * 0.69f)
    val origin = center + Offset(sin(time * 0.67f) * rx * 0.11f, cos(time * 0.49f) * ry * 0.12f)
    drawRect(Brush.radialGradient(listOf(a.copy(alpha = 0.13f * opacity), Color.Transparent),
        center = origin, radius = maxOf(size.width, size.height) * 0.70f))
    val count = if (economical) 36 else 64
    val layers = if (economical) 3 else 4
    val points = FloatArray(count * 2)
    val path = Path()
    for (layer in 0 until layers) {
        val phase = time * (0.65f + layer * 0.13f) + layer * 1.7f
        val shrink = 1f - layer * 0.105f
        val at = origin + Offset(cos(phase * 0.71f) * rx * 0.075f, sin(phase * 0.92f) * ry * 0.09f)
        for (i in 0 until count) {
            val angle = i * 6.2831853f / count
            val pos = i * bands.size.toFloat() / count
            val lo = pos.toInt() % bands.size
            val hi = (lo + 1) % bands.size
            val frequency = bands[lo] + (bands[hi] - bands[lo]) * (pos - pos.toInt())
            val radius = shrink * (0.82f + 0.09f * bass +
                (0.14f + 0.06f * energy) * sin(3f * angle + phase) +
                0.09f * cos(2f * angle - phase * 0.77f) +
                0.055f * sin(5f * angle + phase * 1.26f) + frequency * 0.13f)
            points[i * 2] = at.x + cos(angle) * rx * radius
            points[i * 2 + 1] = at.y + sin(angle) * ry * radius
        }
        path.reset()
        path.moveTo((points[(count - 1) * 2] + points[0]) / 2f,
            (points[(count - 1) * 2 + 1] + points[1]) / 2f)
        for (i in 0 until count) {
            val j = (i + 1) % count
            path.quadraticBezierTo(points[i * 2], points[i * 2 + 1],
                (points[i * 2] + points[j * 2]) / 2f, (points[i * 2 + 1] + points[j * 2 + 1]) / 2f)
        }
        path.close()
        val color = when (layer % 3) { 0 -> a; 1 -> b; else -> c }
        val light = at + Offset(cos(phase) * rx * 0.50f, sin(phase) * ry * 0.50f)
        drawPath(path, Brush.radialGradient(
            0f to color.copy(alpha = 0.38f * opacity),
            0.50f to lerp(color, b, 0.4f).copy(alpha = 0.21f * opacity),
            1f to a.copy(alpha = 0.05f * opacity), center = light, radius = maxOf(rx, ry) * 1.4f))
        drawPath(path, Brush.linearGradient(
            listOf(a.copy(alpha = 0.05f), b.copy(alpha = 0.52f), c.copy(alpha = 0.08f)),
            start = light, end = at - (light - at)), alpha = opacity * 0.65f,
            style = Stroke((3.6f - layer * 0.55f) * density))
    }
}

/** Frequency display: 28 genuine logarithmic FFT bands, no simulated beat when audio is unavailable. */
private fun DrawScope.drawVibeSpectrum(a: Color, b: Color, bands: FloatArray, time: Float, opacity: Float, ring: Boolean, preview: Boolean) {
    if (size.minDimension <= 0f) return
    val n = bands.size
    val span = size.minDimension
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

/** Portable gradient (API 26–32 and Performance mode); no per-pixel noise shader. */
private fun DrawScope.drawSoftSphere(a: Color, b: Color, c: Color, bass: Float, opacity: Float, time: Float) {
    if (size.minDimension <= 0f) return
    val r = size.minDimension * (0.49f + 0.018f * bass)
    listOf(a, b, c).forEachIndexed { i, color ->
        val phase = time * (0.72f + i * 0.17f) + i * 2.09f
        val at = center + Offset(cos(phase) * r * 0.17f, sin(phase) * r * 0.17f)
        drawCircle(Brush.radialGradient(
            0f to Color.Transparent, 0.49f to Color.Transparent,
            0.76f to color.copy(alpha = (0.42f + 0.11f * sin(phase) + bass * 0.14f) * opacity),
            1f to Color.Transparent, center = at, radius = r
        ), radius = r, center = at)
    }
}
