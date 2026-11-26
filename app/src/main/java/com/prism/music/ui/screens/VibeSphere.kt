/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Earlier versions included an AGSL noise/sphere shader adapted from Vexorter42/Unison,
 * WaveSphere.kt at 844b796e4adbd26a4b9ff6930f8da22a6aab9edc (GPL-3.0).
 * https://github.com/Vexorter42/Unison/blob/844b796e4adbd26a4b9ff6930f8da22a6aab9edc/app/src/main/java/com/musichub/ui/WaveSphere.kt
 * Modified for Prism: theme-aware compositing, station energy, bounded animation,
 * existing PCM spectrum, Android fallback and performance/reduced-motion handling.
 */
package com.prism.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.prism.music.playback.Spectrum
import com.prism.music.playback.VisualizerTap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prism.music.data.prefs.LightStyle
import com.prism.music.data.prefs.ColorSource
import com.prism.music.ui.theme.LocalArtworkColor
import com.prism.music.ui.theme.seedFor
import com.prism.music.ui.theme.themeColors
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
    val currentScheme = MaterialTheme.colorScheme
    val artwork = LocalArtworkColor.current
    // Use the final artwork seed, not the global theme's already interpolating primary.
    // Otherwise the wave repeatedly restarts its own tween on every intermediate theme frame.
    val scheme = if (settings.colorSource == ColorSource.DYNAMIC) currentScheme
        else remember(settings, dark, artwork) {
            themeColors(settings, dark, seedFor(settings, dark, artwork), null).scheme
        }
    val coverStops = if (settings.spectrumPreset == SpectrumPresets.COVER) SpectrumPresets.coverTargetStops else null
    val palette = remember(settings.lightStyle, settings.spectrumPreset, settings.spectrumStops, settings.spectrumBands, dark, coverStops) {
        if (settings.lightStyle == LightStyle.WHITE) emptyList()
        else SpectrumPresets.light(if (coverStops != null) "CUSTOM" else settings.spectrumPreset,
            coverStops ?: settings.spectrumStops, settings.spectrumBands).let {
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
    val signal = remember { mutableStateOf(VibeWaveSignal(7f, 0f, FloatArray(Spectrum.BANDS))) }
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
            signal.value = VibeWaveSignal(7f, 0f, FloatArray(Spectrum.BANDS))
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
                    signal.value = VibeWaveSignal(time.floatValue, level.floatValue, bands.copyOf())
                    last = now
                }
            }
        }
    }
    val opacity = (if (dark) 0.92f else 0.76f) * settings.streamOpacity
    YamusicWaveBackdrop(modifier, a, b, c, signal, opacity, settings.streamVisualSize.fraction,
        style, energy, motion, colorDurationMs = if (settings.colorSource == ColorSource.ARTWORK || coverStops != null)
            settings.artworkChange.ms.coerceAtLeast(900) else 1200)
}

/** The phase advances without any audio input, even while paused. */
internal fun advanceVibeTime(time: Float, seconds: Float, speed: Float, playing: Boolean): Float {
    val dt = if (seconds.isFinite()) seconds.coerceIn(0f, 0.15f) else 0f
    val rate = if (speed.isFinite()) speed.coerceIn(0f, 2f) else 1f
    return (if (time.isFinite()) time else 0f) + dt * rate * (if (playing) 0.9f else 0.4f)
}

/** Smooth attack/release on top of the shared FFT. Also decays when the PCM tap goes quiet. */
internal fun smoothVibe(previous: Float, next: Float, seconds: Float = 1f / 30f): Float {
    val current = if (previous.isFinite()) previous.coerceIn(0f, 1f) else 0f
    val target = if (next.isFinite()) next.coerceIn(0f, 1f) else 0f
    val dt = if (seconds.isFinite()) seconds.coerceIn(0f, 0.1f) else 0f
    val tau = if (target > current) 0.075f else 0.26f
    return current + (target - current) * (1f - exp(-dt / tau))
}

