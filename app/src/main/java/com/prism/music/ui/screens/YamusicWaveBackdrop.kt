/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from yamusic-rs/android/src/wave.frag (GPL-3.0),
 * https://github.com/SpolzerMod/yamusic-rs/blob/bb98fa9af70f817732ef0d653629302edac94f57/android/src/wave.frag
 * Three soft, rotating, sinusoidally deformed Gaussian blobs, with the original tone mapping.
 * Prism changes: transparent compositing, theme palette, PCM envelope, viewport scale,
 * and low-resolution software rendering off the UI thread on every supported Android version.
 */
package com.prism.music.ui.screens

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import com.prism.music.data.prefs.StreamVisualStyle
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class VibeWaveSignal(val phase: Float, val beat: Float, val bands: FloatArray)
private data class WaveViewport(val size: IntSize = IntSize.Zero, val origin: Offset = Offset.Zero)
internal data class WaveColors(val palette: List<Color>, val opacity: Float)
private data class WaveInput(val sample: VibeWaveSignal, val anchor: Offset?, val colors: WaveColors)

/** Retarget from the interpolated color, not the previous target: rapid theme changes never flash. */
internal class WaveColorTransition(initial: WaveColors) {
    private var from = initial
    private var target = initial
    private var startMs = 0L
    private var durationMs = 1200

    fun at(nowMs: Long): WaveColors {
        if (from == target) return target
        val t = ((nowMs - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        if (t >= 1f) return target
        val k = t * t * (3f - 2f * t)
        return WaveColors(from.palette.zip(target.palette) { a, b -> lerp(a, b, k) },
            from.opacity + (target.opacity - from.opacity) * k)
    }

    fun update(next: WaveColors, nowMs: Long, animate: Boolean, duration: Int = 1200): WaveColors {
        if (!animate) {
            from = next
            target = next
            startMs = nowMs
            return next
        }
        if (next != target) {
            from = at(nowMs)
            target = next
            startMs = nowMs
            durationMs = duration.coerceAtLeast(1)
        }
        return at(nowMs)
    }
}

/** Color flows slowly between the three lobes without introducing hues outside the active theme. */
internal fun flowingWavePalette(colors: List<Color>, phase: Float): List<Color> {
    if (colors.size != 6 || !phase.isFinite()) return colors
    val turn = ((phase * 0.045f) % 3f + 3f) % 3f
    val slot = turn.toInt()
    val fraction = turn - slot
    val blend = fraction * fraction * (3f - 2f * fraction)
    return List(6) { i ->
        val pair = i / 2
        val shade = i % 2
        val a = ((pair + slot) % 3) * 2 + shade
        val b = ((pair + slot + 1) % 3) * 2 + shade
        lerp(colors[a], colors[b], blend)
    }
}

/** All visual styles share the same ambient field and the same published frame position. */
@Composable
internal fun YamusicWaveBackdrop(
    modifier: Modifier, a: Color, b: Color, c: Color, signal: State<VibeWaveSignal>,
    opacity: Float, scale: Float, style: StreamVisualStyle, energy: Float, moving: Boolean,
    colorDurationMs: Int = 1200,
) {
    var viewport by remember { mutableStateOf(WaveViewport()) }
    val scene = LocalVibeScene.current
    val palette = remember(a, b, c) {
        listOf(a, lerp(a, b, 0.7f), b, lerp(b, c, 0.7f), c, lerp(c, a, 0.7f))
    }
    val targetColors = rememberUpdatedState(WaveColors(palette, opacity))
    val transition = remember { WaveColorTransition(targetColors.value) }
    val duration = rememberUpdatedState(colorDurationMs)
    val frame by produceState<ImageBitmap?>(null, viewport, scale, style, energy, moving, scene) {
        val view = viewport.size
        if (view.width <= 0 || view.height <= 0) {
            value = null
            return@produceState
        }
        val aspect = view.width.toFloat() / view.height
        val w = if (aspect >= 1f) 192 else (192 * aspect).roundToInt().coerceAtLeast(1)
        val h = if (aspect <= 1f) 192 else (192 / aspect).roundToInt().coerceAtLeast(1)
        snapshotFlow {
            WaveInput(if (moving) signal.value else VibeWaveSignal(7f, 0f, EMPTY_BANDS),
                scene?.anchor, targetColors.value)
        }.conflate().collect { (sample, anchor, colors) ->
                val blended = transition.update(colors, SystemClock.elapsedRealtime(), moving, duration.value)
                val framePalette = if (moving) flowingWavePalette(blended.palette, sample.phase) else blended.palette
                val drift = waveDrift(sample.phase, minOf(view.width, view.height).toFloat(), moving)
                val at = if (anchor == null) Offset(view.width * 0.5f, view.height * 0.38f) else anchor - viewport.origin
                val center = Offset(((at.x + drift.x) / view.width).coerceIn(0f, 1f),
                    ((at.y + drift.y) / view.height).coerceIn(0f, 1f))
                val bitmap = withContext(Dispatchers.Default) {
                    renderYamusicWave(w, h, framePalette, sample, blended.opacity, scale, style, energy, center)
                }
                // Only the ambient field moves. The anchor and its touch targets remain stationary.
                value = bitmap
            }
    }
    Canvas(modifier.onGloballyPositioned {
        viewport = WaveViewport(it.size, it.positionInRoot())
    }) {
        frame?.let {
            drawImage(it, dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1),
                size.height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Low)
        }
    }
}

private val EMPTY_BANDS = FloatArray(28)

internal fun waveDrift(time: Float, span: Float, moving: Boolean): Offset {
    if (!moving || !time.isFinite() || !span.isFinite() || span <= 0f) return Offset.Zero
    val amplitude = span * 0.032f
    return Offset(sin(time * 0.54f) * amplitude, cos(time * 0.43f) * amplitude)
}

private data class Blob(val x: Float, val y: Float, val ca: Float, val sa: Float,
    val radius: Float, val seed: Float, val weight: Float)

private fun smoothstep(lo: Float, hi: Float, x: Float): Float {
    val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Software port of the upstream fragment, evaluated into a small, smoothly filtered texture. */
private fun renderYamusicWave(
    w: Int, h: Int, palette: List<Color>, sample: VibeWaveSignal, opacity: Float, scale: Float,
    style: StreamVisualStyle, energy: Float, center: Offset,
): ImageBitmap {
    val pixels = IntArray(w * h)
    val aspect = w.toFloat() / h
    // Use the actual viewport aspect, never clip at the bounds of a scaled composable.
    // On wide screens keep the central glow proportionate to the available height.
    val sceneScale = scale.coerceIn(0.25f, 1f) * minOf(1.25f, aspect * 1.95f)
    val t = sample.phase
    val beat = sample.beat.coerceIn(0f, 1f)
    val glowMode = style == StreamVisualStyle.GLOW
    val ringMode = style == StreamVisualStyle.RING
    val spectrumMode = style == StreamVisualStyle.EQUALIZER
    val spread = if (glowMode) 1.33f else if (ringMode) 1.18f else 1f
    val angular = if (glowMode) 0.45f else if (spectrumMode) 1.25f else 1f
    val blobs = Array(3) { i ->
        val fi = i.toFloat()
        val phase = fi * 2.094f
        val ang = t * 0.12f + phase
        Blob(0.05f * sin(t * 0.31f + phase), 0.05f * cos(t * 0.23f + phase * 1.3f),
            cos(ang), sin(ang), (0.27f - fi * 0.03f) * spread * (1f + 0.16f * beat),
            fi * 1.7f, 0.75f - fi * 0.12f)
    }
    for (y in 0 until h) {
        val v = 1f - (y + 0.5f) / h
        val py = (v - (1f - center.y)) / sceneScale
        for (x in 0 until w) {
            val u = (x + 0.5f) / w
            val px = (u - center.x) * aspect / sceneScale
            var red = 0f
            var green = 0f
            var blue = 0f
            for (i in blobs.indices) {
                val blob = blobs[i]
                val dx = px - blob.x
                val dy = py - blob.y
                val qx = blob.ca * dx + blob.sa * dy
                val qy = (-blob.sa * dx + blob.ca * dy) * 1.25f
                val angle = atan2(qy, qx)
                // Frequency bands deform the same soft blob, never draw a separate bar or ring widget.
                val frequency = if (spectrumMode || ringMode) waveBand(sample.bands, angle + t * 0.13f) else 0f
                val edge = 1f + angular * (0.14f * sin(angle * 3f + t * 0.8f + blob.seed) +
                    0.08f * sin(angle * 5f - t * 1.1f + blob.seed * 2.3f)) +
                    frequency * (if (spectrumMode) 0.28f else 0.12f)
                val d = hypot(qx, qy) / (blob.radius * edge)
                val base = exp(-d * d * (if (glowMode) 1.8f else 3f))
                val rim = exp(-((d - 0.76f) * 2.8f) * ((d - 0.76f) * 2.8f))
                val b = when {
                    ringMode -> base * 0.48f + rim * (0.42f + 0.18f * frequency)
                    spectrumMode -> base * (0.82f + 0.18f * energy) + rim * frequency * 0.24f
                    else -> base
                }
                val mix = smoothstep(0.2f, 0.9f, b)
                val low = palette[i * 2 + 1]
                val high = palette[i * 2]
                val weight = b * blob.weight
                red += (low.red + (high.red - low.red) * mix) * weight
                green += (low.green + (high.green - low.green) * mix) * weight
                blue += (low.blue + (high.blue - low.blue) * mix) * weight
            }
            val glow = (1f - smoothstep(0f, 0.65f, v)) * (if (glowMode) 0.20f else 0.12f)
            val low = palette[1]
            val high = palette[3]
            red += (low.red + (high.red - low.red) * u) * glow
            green += (low.green + (high.green - low.green) * u) * glow
            blue += (low.blue + (high.blue - low.blue) * u) * glow
            red = 1f - exp(-red * 1.6f)
            green = 1f - exp(-green * 1.6f)
            blue = 1f - exp(-blue * 1.6f)
            // Upstream composites over black. Recover a transparent colored layer instead,
            // so custom Prism backdrops remain visible and no black rectangle cuts across the page.
            val peak = maxOf(red, green, blue)
            if (peak < 0.001f) continue
            val alpha = (peak * opacity * 0.8f * 255f).roundToInt().coerceIn(0, 255)
            fun channel(value: Float) = (value / peak * 255f).roundToInt().coerceIn(0, 255)
            pixels[y * w + x] = (alpha shl 24) or (channel(red) shl 16) or
                (channel(green) shl 8) or channel(blue)
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Mirror low-to-high FFT along the circumference and interpolate continuously at the seam. */
internal fun waveBand(bands: FloatArray, angle: Float): Float {
    if (bands.isEmpty() || !angle.isFinite()) return 0f
    val turn = ((angle / 6.2831853f) % 1f + 1f) % 1f
    val index = (1f - kotlin.math.abs(turn * 2f - 1f)) * (bands.size - 1)
    val lo = index.toInt().coerceIn(0, bands.lastIndex)
    val hi = minOf(lo + 1, bands.lastIndex)
    return (bands[lo] + (bands[hi] - bands[lo]) * (index - lo)).coerceIn(0f, 1f)
}
