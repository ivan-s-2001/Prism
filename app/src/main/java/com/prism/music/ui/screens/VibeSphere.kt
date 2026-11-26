/*
 * SPDX-License-Identifier: GPL-3.0-only
 * The AGSL noise/sphere shader below is adapted from Vexorter42/Unison,
 * WaveSphere.kt at 844b796e4adbd26a4b9ff6930f8da22a6aab9edc (GPL-3.0).
 * https://github.com/Vexorter42/Unison/blob/844b796e4adbd26a4b9ff6930f8da22a6aab9edc/app/src/main/java/com/musichub/ui/WaveSphere.kt
 * Modified for Prism: theme-aware compositing, station energy, bounded animation,
 * existing PCM spectrum, Android fallback and performance/reduced-motion handling.
 */
package com.prism.music.ui.screens

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.ShaderBrush
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
import com.prism.music.ui.player.rememberVisualizer
import com.prism.music.data.prefs.StreamVisualStyle
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import androidx.compose.material3.MaterialTheme

private const val SPHERE_AGSL = """
uniform float2 size;
uniform float2 center;
uniform float time;
uniform float bass;
uniform float4 frequencies;
uniform float energy;
uniform float opacity;
uniform float3 colA;
uniform float3 colB;
uniform float3 colC;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float fbm(float2 p) {
    float v = 0.60 * noise(p);
    p = p * 2.03 + float2(13.7, 9.2);
    v += 0.28 * noise(p);
    p = p * 2.01 + float2(7.1, 17.3);
    return v + 0.12 * noise(p);
}

half4 main(float2 fc) {
    float2 uv = (fc - center) / min(size.x, size.y);
    uv -= float2(sin(time * 0.37), cos(time * 0.29)) * 0.026;
    float t = time * (0.42 + energy * 0.36);
    // Two independently travelling distortion fields make the light fold, rather than rotate as a flat picture.
    float angle = atan(uv.y, uv.x);
    float r = length(uv);
    float spin = 0.34 * sin(t * 0.73) + t * 0.16 + 0.56 * exp(-r * 3.2);
    float cs = cos(spin);
    float sn = sin(spin);
    float2 p = float2(uv.x * cs - uv.y * sn, uv.x * sn + uv.y * cs);
    float cloud = fbm(p * 3.1 + float2(t * 0.21, -t * 0.16));
    float detail = fbm(p * 5.4 + float2(cloud * 1.8, -cloud * 1.6) + float2(-t * 0.15, t * 0.12));
    // Low frequencies swell the body, mids fold the membrane, highs ripple its edge.
    float fold = (0.042 + 0.027 * frequencies.y) * sin(angle * 3.0 + t * 1.15)
               + (0.025 + 0.021 * frequencies.z) * sin(angle * 5.0 - t * 0.82)
               + (0.010 + 0.012 * frequencies.w) * sin(angle * 9.0 + t * 1.7);
    float radius = 0.315 + 0.019 * sin(t * 1.08) + 0.037 * bass + 0.018 * frequencies.x;
    float edge = r + (cloud - 0.5) * (0.11 + 0.10 * energy) + (detail - 0.5) * 0.042 + fold;
    float outer = 1.0 - smoothstep(radius * 0.91, radius * 1.48, edge);
    float inner = smoothstep(radius * 0.56, radius * 0.94, edge);
    float ribbon = 0.5 + 0.5 * sin(angle * 2.0 + detail * 6.2 + t * 0.9);
    float sheen = pow(ribbon, 2.1) * (0.30 + 0.70 * detail);
    float tone = 0.5 + 0.5 * sin(angle + cloud * 4.5 - t * 0.8);
    float3 col = mix(mix(colA, colB, smoothstep(0.0, 0.56, tone)), colC, smoothstep(0.52, 1.0, tone));
    col = mix(col, colC, sheen * 0.23);
    // A soft transparent center protects text contrast even in a light theme.
    float m = (edge - radius) * 26.0;
    float n = (edge - radius * 0.79 + 0.013 * sin(angle * 4.0 - t)) * 36.0;
    float membrane = exp(-m * m);
    float secondary = exp(-n * n);
    float light = outer * inner * (0.23 + 0.40 * cloud + sheen * 0.42)
        + membrane * (0.22 + 0.40 * sheen + 0.12 * frequencies.w)
        + secondary * (0.08 + 0.20 * detail);
    light *= 0.88 + 0.29 * bass;
    float clipEdge = 1.0 - smoothstep(0.42, 0.50, r);
    float al = clamp(light * opacity * clipEdge, 0.0, 0.95);
    return half4(half3(clamp(col, 0.0, 1.0) * al), half(al));
}
"""

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
    val a = scheme.primary
    val b = lerp(a, scheme.onSurface, if (dark) 0.36f else 0.22f)
    val c = lerp(a, scheme.background, if (dark) 0.28f else 0.18f)
    val motion = !preview && !settings.reduceMotion && !settings.performanceMode
    val visualizer = rememberVisualizer(motion && animated)
    val playing = rememberUpdatedState(animated)
    val time = remember { mutableFloatStateOf(7f) }
    val level = remember { mutableFloatStateOf(0f) }
    val live = rememberUpdatedState(visualizer)
    val bands = remember { FloatArray(Spectrum.BANDS) }
    val group = remember { FloatArray(4) }
    LaunchedEffect(motion) {
        if (!motion) {
            level.floatValue = 0f
            bands.fill(0f)
            group.fill(0f)
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                // Update the GPU field at most ~30 fps, and never jump on returning from background.
                val dt = now - last
                if (dt >= 30_000_000L) {
                    time.floatValue += (dt / 1e9f).coerceIn(0f, 0.1f) * if (playing.value) 2.2f else 0.75f
                    val source = live.value
                    val target = source?.level?.floatValue ?: 0f
                    level.floatValue = smoothVibe(level.floatValue, target)
                    for (i in bands.indices) bands[i] = smoothVibe(bands[i], source?.bands?.get(i) ?: 0f)
                    for (i in group.indices) {
                        var sum = 0f
                        val start = i * 7
                        for (j in start until start + 7) sum += bands[j]
                        group[i] = sum / 7f
                    }
                    last = now
                }
            }
        }
    }
    val bass = { if (motion) level.floatValue else 0f }
    val opacity = if (dark) 0.90f else 0.70f
    if (style == StreamVisualStyle.GLOW) {
        Canvas(modifier) { drawVibeGlow(a, b, bass(), opacity, if (motion) time.floatValue else 7f) }
    } else if (style == StreamVisualStyle.RING || style == StreamVisualStyle.EQUALIZER) {
        Canvas(modifier) {
            val t = if (motion) time.floatValue else 7f
            drawVibeSpectrum(a, b, bands, t, opacity, style == StreamVisualStyle.RING, preview)
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.performanceMode) {
        ShaderSphere(modifier, a, b, c, energy.coerceIn(0f, 1f), opacity, { time.floatValue }, bass, bands, group)
    } else {
        Canvas(modifier) { drawLiquidVibe(a, b, c, bands, bass(), opacity, if (motion) time.floatValue else 7f) }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderSphere(
    modifier: Modifier, a: Color, b: Color, c: Color, energy: Float, opacity: Float,
    time: () -> Float, bass: () -> Float, bands: FloatArray, group: FloatArray,
) {
    // RuntimeShader compilation can fail on a device's Skia implementation. Keep the screen usable.
    val shader = remember { runCatching { RuntimeShader(SPHERE_AGSL) }.onFailure { Log.w("PrismVibe", "AGSL unavailable; using animated gradients", it) }.getOrNull() }
    Canvas(modifier) {
        if (size.minDimension <= 0f) return@Canvas
        val level = bass()
        if (shader == null) {
            drawLiquidVibe(a, b, c, bands, level, opacity, time())
        } else {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("center", center.x, center.y)
            shader.setFloatUniform("time", time())
            shader.setFloatUniform("bass", level)
            shader.setFloatUniform("frequencies", group[0], group[1], group[2], group[3])
            shader.setFloatUniform("energy", energy)
            shader.setFloatUniform("opacity", opacity)
            shader.setFloatUniform("colA", a.red, a.green, a.blue)
            shader.setFloatUniform("colB", b.red, b.green, b.blue)
            shader.setFloatUniform("colC", c.red, c.green, c.blue)
            // Create a fresh brush after writing uniforms, avoiding a cached native shader snapshot.
            drawRect(ShaderBrush(shader))
        }
    }
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
internal fun smoothVibe(previous: Float, next: Float): Float {
    val target = if (next.isFinite()) next.coerceIn(0f, 1f) else 0f
    return previous + (target - previous) * if (target > previous) 0.36f else 0.12f
}

/** A portable liquid membrane with continuous curved contours, even without AGSL. */
private fun DrawScope.drawLiquidVibe(a: Color, b: Color, c: Color, bands: FloatArray, bass: Float, opacity: Float, time: Float) {
    if (size.minDimension <= 0f) return
    val radius = size.minDimension * (0.30f + 0.035f * bass)
    val origin = center + Offset(sin(time * 0.37f), cos(time * 0.29f)) * (radius * 0.09f)
    drawSoftSphere(a, b, c, bass, opacity * 0.36f, time)
    val count = 84
    for (layer in 0..3) {
        val phase = time * (0.26f + layer * 0.037f) + layer * 0.9f
        val points = Array(count) { i ->
            val angle = i * 6.2831853f / count
            val index = i * bands.size.toFloat() / count
            val left = index.toInt() % bands.size
            val right = (left + 1) % bands.size
            val v = bands[left] + (bands[right] - bands[left]) * (index - index.toInt())
            val shape = 1f + 0.16f * sin(3f * angle + phase) + 0.085f * cos(5f * angle - phase * 1.3f) +
                0.045f * sin(7f * angle + phase * 0.8f) + v * 0.10f
            val r = radius * (1f - layer * 0.072f) * shape
            origin + Offset(cos(angle), sin(angle)) * r
        }
        val path = Path()
        val start = (points.last() + points.first()) * 0.5f
        path.moveTo(start.x, start.y)
        for (i in points.indices) {
            val p = points[i]
            val end = (p + points[(i + 1) % count]) * 0.5f
            path.quadraticBezierTo(p.x, p.y, end.x, end.y)
        }
        path.close()
        val color = if (layer % 2 == 0) a else b
        val brush = Brush.sweepGradient(listOf(a, b, c, a), origin)
        drawPath(path, Brush.radialGradient(
            0f to Color.Transparent, 0.5f to Color.Transparent,
            0.83f to color.copy(alpha = 0.12f * opacity), 1f to Color.Transparent,
            center = origin, radius = radius * 1.3f
        ))
        drawPath(path, brush, alpha = opacity * (0.20f - layer * 0.03f), style = Stroke((2.8f - layer * 0.4f) * density))
        drawPath(path, brush, alpha = opacity * (0.36f - layer * 0.05f), style = Stroke(0.7f * density))
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
    val r = size.minDimension * (0.44f + 0.018f * bass)
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
