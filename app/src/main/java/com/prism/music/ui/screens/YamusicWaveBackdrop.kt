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
        val buffers = WaveLightBuffers(w, h)
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
                    renderYamusicWave(w, h, framePalette, sample, blended.opacity, scale, style, energy, center, buffers)
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
                size.height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Medium)
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

private fun gaussian(x: Float): Float = exp(-x * x)

/** Three independent light intensities, one per theme color pair. All effects stay translucent. */
internal data class VibeLight(val first: Float, val second: Float, val third: Float)

/** Original yamusic-rs morphology, with stronger separate lobes and an asymmetric soft core. */
private fun organicWave(x: Float, y: Float, t: Float, blobs: Array<Blob>): VibeLight {
    fun lobe(i: Int): Float {
        val blob = blobs[i]
        val dx = x - blob.x
        val dy = y - blob.y
        val qx = blob.ca * dx + blob.sa * dy
        val qy = (-blob.sa * dx + blob.ca * dy) * 1.25f
        val angle = atan2(qy, qx)
        val edge = 1f + 0.22f * sin(angle * 3f + t * 0.8f + blob.seed) +
            0.10f * sin(angle * 5f - t * 1.1f + blob.seed * 2.3f)
        val d = hypot(qx, qy) / (blob.radius * edge)
        return gaussian(d * 1.55f) * blob.weight
    }
    return VibeLight(lobe(0), lobe(1), lobe(2))
}

/** Two luminous, hollow, rippling membranes. Dark center, broad colored rim, no filled blob. */
internal fun liquidRing(x: Float, y: Float, t: Float, beat: Float, bands: FloatArray): VibeLight {
    val qy = y * 1.10f
    val angle = atan2(qy, x)
    val radius = hypot(x, qy)
    val band = waveBand(bands, angle - t * 0.25f)
    val target = 0.255f + 0.024f * sin(angle * 3f + t * 0.72f) +
        0.013f * cos(angle * 5f - t * 0.58f) + 0.045f * band + 0.022f * beat
    val outer = gaussian((radius - target) / 0.039f)
    val inner = gaussian((radius - target + 0.054f + 0.012f * sin(angle * 2f - t)) / 0.022f)
    val halo = gaussian((radius - target) / 0.093f)
    val sector = 0.5f + 0.5f * cos(angle - t * 0.42f)
    return VibeLight(outer * (0.45f + 0.60f * sector),
        inner * (0.48f + 0.35f * (1f - sector)), halo * 0.33f + outer * (1f - sector) * 0.46f)
}

/** Aurora: broad drifting pools of light, with no sphere, edge, or concentrated center. */
internal fun softAurora(x: Float, y: Float, t: Float, beat: Float): VibeLight {
    val leftX = -0.30f + 0.07f * sin(t * 0.39f)
    val leftY = -0.16f + 0.07f * cos(t * 0.29f)
    val rightX = 0.29f + 0.06f * cos(t * 0.34f)
    val rightY = 0.22f + 0.08f * sin(t * 0.41f)
    val tilt = 0.55f
    val l = gaussian((x - leftX) / 0.29f) * gaussian((y - leftY + tilt * (x - leftX)) / 0.43f)
    val r = gaussian((x - rightX) / 0.27f) * gaussian((y - rightY - tilt * (x - rightX)) / 0.42f)
    val curtain = gaussian((x + 0.08f * sin(y * 5f + t * 0.5f)) / 0.46f) *
        gaussian((y + 0.30f) / 0.25f)
    return VibeLight(l * (0.76f + 0.20f * beat), r * (0.82f + 0.14f * beat), curtain * 0.43f)
}

/** A continuous spectral ribbon across the screen. The 28 FFT bands become smooth displacement,
 * not bars or radial bumps. On pause the ribbons retain a slow independent sine motion. */
internal fun spectralRibbon(x: Float, y: Float, t: Float, beat: Float, band: Float, fine: Float): VibeLight {
    val fade = gaussian(x / 0.53f)
    val bend = 0.052f * sin(x * 10f + t * 0.72f) + 0.026f * cos(x * 17f - t * 0.4f)
    val spread = 0.077f + 0.10f * band + 0.032f * beat
    val upper = bend + spread + fine * 0.045f
    val lower = bend - spread - fine * 0.03f
    val ribbonA = gaussian((y - upper) / (0.032f + band * 0.027f))
    val ribbonB = gaussian((y - lower) / (0.038f + fine * 0.024f))
    val body = gaussian((y - bend) / (spread + 0.044f))
    val cross = gaussian((y - bend * 0.6f) / 0.026f)
    return VibeLight(fade * (ribbonA * 0.98f + body * 0.09f),
        fade * (ribbonB * 0.94f + body * 0.09f), fade * (body * 0.35f + cross * 0.27f))
}

/** Smooth interpolation across all FFT bins, with no seam or staircase between frequency bands. */
internal fun ribbonBand(bands: FloatArray, position: Float): Float {
    if (bands.isEmpty() || !position.isFinite()) return 0f
    val p = position.coerceIn(0f, 1f) * bands.lastIndex
    val i = p.toInt()
    val j = minOf(i + 1, bands.lastIndex)
    val k = p - i
    return (bands[i] + (bands[j] - bands[i]) * k).coerceIn(0f, 1f)
}

/** Distinct spatial light fields, shared palette, tone mapping and continuous screen coordinates. */
private fun renderYamusicWave(
    w: Int, h: Int, palette: List<Color>, sample: VibeWaveSignal, opacity: Float, scale: Float,
    style: StreamVisualStyle, energy: Float, center: Offset, buffers: WaveLightBuffers,
): ImageBitmap {
    val pixels = buffers.pixels
    val aspect = w.toFloat() / h
    val sceneScale = scale.coerceIn(0.25f, 1f) * minOf(1.25f, aspect * 1.95f)
    val t = sample.phase
    val beat = sample.beat.coerceIn(0f, 1f)
    val blobs = Array(3) { i ->
        val fi = i.toFloat()
        val phase = fi * 2.094f
        val ang = t * 0.12f + phase
        Blob(0.09f * sin(t * 0.31f + phase), 0.085f * cos(t * 0.23f + phase * 1.3f),
            cos(ang), sin(ang), (0.27f - fi * 0.025f) * (1f + 0.16f * beat),
            fi * 1.7f, 0.88f - fi * 0.14f)
    }
    // Precompute ribbon response once per column rather than sampling the FFT per pixel.
    val ribbon = FloatArray(w) { ribbonBand(sample.bands, it / maxOf(1f, w - 1f)) }
    val fine = FloatArray(w) { ribbonBand(sample.bands, (it / maxOf(1f, w - 1f) * 0.82f + 0.18f)) }
    val low = Array(3) { palette[it * 2 + 1] }
    val high = Array(3) { palette[it * 2] }
    val raw = buffers.raw
    for (y in 0 until h) {
        val v = 1f - (y + 0.5f) / h
        val py = (v - (1f - center.y)) / sceneScale
        for (x in 0 until w) {
            val u = (x + 0.5f) / w
            val px = (u - center.x) * aspect / sceneScale
            val light = when (style) {
                StreamVisualStyle.RING -> liquidRing(px, py, t, beat, sample.bands)
                StreamVisualStyle.GLOW -> softAurora(px, py, t, beat)
                StreamVisualStyle.EQUALIZER -> spectralRibbon(px, py, t, beat, ribbon[x], fine[x])
                StreamVisualStyle.WAVE -> organicWave(px, py, t, blobs)
                StreamVisualStyle.OFF -> VibeLight(0f, 0f, 0f)
            }
            var red = 0f
            var green = 0f
            var blue = 0f
            for (i in 0..2) {
                val amount = when (i) { 0 -> light.first; 1 -> light.second; else -> light.third }
                val mix = smoothstep(0.1f, 0.85f, amount)
                red += (low[i].red + (high[i].red - low[i].red) * mix) * amount
                green += (low[i].green + (high[i].green - low[i].green) * mix) * amount
                blue += (low[i].blue + (high[i].blue - low[i].blue) * mix) * amount
            }
            // Continue the moving atmosphere under the mini player.
            val sweep = 0.72f + 0.28f * sin(u * 4.8f + v * 3.2f - t * 0.42f)
            val ambient = (1f - smoothstep(0f, 0.78f, v)) * 0.105f * sweep
            red += (palette[1].red + (palette[3].red - palette[1].red) * u) * ambient
            green += (palette[1].green + (palette[3].green - palette[1].green) * u) * ambient
            blue += (palette[1].blue + (palette[3].blue - palette[1].blue) * u) * ambient
            val i = (y * w + x) * 3
            raw[i] = red
            raw[i + 1] = green
            raw[i + 2] = blue
        }
    }
    // Shared optical pipeline: interior softness, edge diffusion, and highlight bloom.
    val unit = (minOf(w, h) / 96f).coerceAtLeast(0.5f)
    blurWaveLight(raw, buffers.inner, buffers.scratch, w, h, (2 * unit).roundToInt())
    blurWaveLight(buffers.inner, buffers.edge, buffers.scratch, w, h, (6 * unit).roundToInt())
    for (i in raw.indices step 3) {
        val peak = maxOf(raw[i], raw[i + 1], raw[i + 2])
        val weight = smoothstep(0.18f, 0.75f, peak)
        for (c in 0..2) buffers.bright[i + c] = raw[i + c] * weight
    }
    blurWaveLight(buffers.bright, buffers.bloom, buffers.scratch, w, h, (12 * unit).roundToInt())
    val diffuse = if (style == StreamVisualStyle.GLOW) 0.72f else 0.38f
    val core = if (style == StreamVisualStyle.GLOW) 0.12f else 0.28f
    val gain = 1.48f + 0.18f * energy.coerceIn(0f, 1f)
    val bloom = 0.38f + 0.24f * beat
    for (p in pixels.indices) {
        val i = p * 3
        fun tone(c: Int): Float {
            val j = i + c
            val light = raw[j] * core + buffers.inner[j] * (1f - core) +
                buffers.edge[j] * diffuse + buffers.bloom[j] * bloom
            return 1f - exp(-light * gain)
        }
        val red = tone(0)
        val green = tone(1)
        val blue = tone(2)
        val peak = maxOf(red, green, blue)
        if (peak < 0.001f) {
            pixels[p] = 0
        } else {
            val alpha = (peak * opacity * 255f).roundToInt().coerceIn(0, 255)
            fun channel(value: Float) = (value / peak * 255f).roundToInt().coerceIn(0, 255)
            pixels[p] = (alpha shl 24) or (channel(red) shl 16) or
                (channel(green) shl 8) or channel(blue)
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private class WaveLightBuffers(w: Int, h: Int) {
    val pixels = IntArray(w * h)
    val raw = FloatArray(w * h * 3)
    val inner = FloatArray(raw.size)
    val edge = FloatArray(raw.size)
    val bright = FloatArray(raw.size)
    val bloom = FloatArray(raw.size)
    val scratch = FloatArray(raw.size)
}

/** Separable sliding-window RGB blur in O(pixels), with clamped edges.
 * Working storage belongs to a single render coroutine and is reused. */
internal fun blurWaveLight(input: FloatArray, output: FloatArray, scratch: FloatArray,
    w: Int, h: Int, radius: Int) {
    val r = radius.coerceAtLeast(1)
    val divisor = (r * 2 + 1).toFloat()
    for (y in 0 until h) for (c in 0..2) {
        val row = y * w * 3 + c
        var sum = 0f
        for (k in -r..r) sum += input[row + k.coerceIn(0, w - 1) * 3]
        for (x in 0 until w) {
            scratch[row + x * 3] = (sum / divisor).coerceAtLeast(0f)
            sum += input[row + (x + r + 1).coerceIn(0, w - 1) * 3] -
                input[row + (x - r).coerceIn(0, w - 1) * 3]
        }
    }
    for (x in 0 until w) for (c in 0..2) {
        val column = x * 3 + c
        val stride = w * 3
        var sum = 0f
        for (k in -r..r) sum += scratch[column + k.coerceIn(0, h - 1) * stride]
        for (y in 0 until h) {
            output[column + y * stride] = (sum / divisor).coerceAtLeast(0f)
            sum += scratch[column + (y + r + 1).coerceIn(0, h - 1) * stride] -
                scratch[column + (y - r).coerceIn(0, h - 1) * stride]
        }
    }
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
