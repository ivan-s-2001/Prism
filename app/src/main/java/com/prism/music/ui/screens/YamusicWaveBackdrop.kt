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
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
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

/** Same renderer and sampling resolution regardless of Prism's performance setting. */
@Composable
internal fun YamusicWaveBackdrop(
    modifier: Modifier, a: Color, b: Color, c: Color, time: FloatState, level: FloatState,
    opacity: Float, scale: Float, moving: Boolean,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val palette = remember(a, b, c) {
        listOf(a, lerp(a, b, 0.7f), b, lerp(b, c, 0.7f), c, lerp(c, a, 0.7f))
    }
    val frame by produceState<ImageBitmap?>(null, viewport, palette, opacity, scale, moving) {
        if (viewport.width <= 0 || viewport.height <= 0 || opacity <= 0f) {
            value = null
            return@produceState
        }
        // Like yamusic-rs's downsampled target, intentionally soft. Fixed pixel budget also for 4K TVs.
        val aspect = viewport.width.toFloat() / viewport.height
        val w = if (aspect >= 1f) 192 else (192 * aspect).roundToInt().coerceAtLeast(1)
        val h = if (aspect <= 1f) 192 else (192 / aspect).roundToInt().coerceAtLeast(1)
        snapshotFlow { if (moving) time.floatValue to level.floatValue else 7f to 0f }
            .conflate().collect { (phase, beat) ->
                value = withContext(Dispatchers.Default) {
                    renderYamusicWave(w, h, palette, phase, beat, opacity, scale)
                }
            }
    }
    Canvas(modifier.onSizeChanged { viewport = it }) {
        frame?.let {
            drawImage(it, dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1),
                size.height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Low)
        }
    }
}

private data class Blob(val x: Float, val y: Float, val ca: Float, val sa: Float,
    val radius: Float, val seed: Float, val weight: Float)

private fun smoothstep(lo: Float, hi: Float, x: Float): Float {
    val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Software port of the upstream fragment, evaluated into a small, smoothly filtered texture. */
private fun renderYamusicWave(
    w: Int, h: Int, palette: List<Color>, time: Float, beat: Float, opacity: Float, scale: Float,
): ImageBitmap {
    val pixels = IntArray(w * h)
    val aspect = w.toFloat() / h
    // Use the actual viewport aspect, never clip at the bounds of a scaled composable.
    // On wide screens keep the central glow proportionate to the available height.
    val sceneScale = scale.coerceIn(0.25f, 1f) * minOf(1f, aspect * 1.65f)
    val t = time
    val blobs = Array(3) { i ->
        val fi = i.toFloat()
        val phase = fi * 2.094f
        val ang = t * 0.12f + phase
        Blob(0.05f * sin(t * 0.31f + phase), 0.05f * cos(t * 0.23f + phase * 1.3f),
            cos(ang), sin(ang), (0.27f - fi * 0.03f) * (1f + 0.16f * beat),
            fi * 1.7f, 0.75f - fi * 0.12f)
    }
    for (y in 0 until h) {
        val v = 1f - (y + 0.5f) / h
        val py = (v - 0.62f) / sceneScale
        for (x in 0 until w) {
            val u = (x + 0.5f) / w
            val px = (u - 0.5f) * aspect / sceneScale
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
                val edge = 1f + 0.14f * sin(angle * 3f + t * 0.8f + blob.seed) +
                    0.08f * sin(angle * 5f - t * 1.1f + blob.seed * 2.3f)
                val d = hypot(qx, qy) / (blob.radius * edge)
                val b = exp(-d * d * 3f)
                val mix = smoothstep(0.2f, 0.9f, b)
                val low = palette[i * 2 + 1]
                val high = palette[i * 2]
                val weight = b * blob.weight
                red += (low.red + (high.red - low.red) * mix) * weight
                green += (low.green + (high.green - low.green) * mix) * weight
                blue += (low.blue + (high.blue - low.blue) * mix) * weight
            }
            val glow = (1f - smoothstep(0f, 0.55f, v)) * 0.12f
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
