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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import kotlin.math.sin
import kotlin.math.cos
import com.prism.music.ui.player.rememberVisualizer
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import androidx.compose.material3.MaterialTheme

private const val SPHERE_AGSL = """
uniform float2 size;
uniform float2 center;
uniform float time;
uniform float bass;
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
    float fold = 0.025 * sin(angle * 3.0 + t * 1.15) + 0.017 * sin(angle * 5.0 - t * 0.82);
    float radius = 0.325 + 0.018 * sin(t * 1.08) + 0.035 * bass;
    float edge = r + (cloud - 0.5) * (0.11 + 0.10 * energy) + (detail - 0.5) * 0.042 + fold;
    float outer = 1.0 - smoothstep(radius * 0.91, radius * 1.48, edge);
    float inner = smoothstep(radius * 0.56, radius * 0.94, edge);
    float ribbon = 0.5 + 0.5 * sin(angle * 2.0 + detail * 6.2 + t * 0.9);
    float sheen = pow(ribbon, 2.1) * (0.30 + 0.70 * detail);
    float tone = 0.5 + 0.5 * sin(angle + cloud * 4.5 - t * 0.8);
    float3 col = mix(mix(colA, colB, smoothstep(0.0, 0.56, tone)), colC, smoothstep(0.52, 1.0, tone));
    col = mix(col, colC, sheen * 0.23);
    // A soft transparent center protects text contrast even in a light theme.
    float light = outer * inner * (0.33 + 0.56 * cloud + sheen * 0.62) * (0.88 + 0.29 * bass);
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
    val liveLevel = rememberUpdatedState(visualizer?.level)
    LaunchedEffect(motion) {
        if (!motion) {
            level.floatValue = 0f
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
                    level.floatValue = liveLevel.value?.floatValue?.coerceIn(0f, 1f) ?: 0f
                    last = now
                }
            }
        }
    }
    val bass = { if (motion) level.floatValue else 0f }
    val opacity = if (dark) 0.90f else 0.70f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.performanceMode) {
        ShaderSphere(modifier, a, b, c, energy.coerceIn(0f, 1f), opacity, { time.floatValue }, bass)
    } else {
        Canvas(modifier) { drawSoftSphere(a, b, c, bass(), opacity, if (motion) time.floatValue else 7f) }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderSphere(
    modifier: Modifier, a: Color, b: Color, c: Color, energy: Float, opacity: Float,
    time: () -> Float, bass: () -> Float,
) {
    // RuntimeShader compilation can fail on a device's Skia implementation. Keep the screen usable.
    val shader = remember { runCatching { RuntimeShader(SPHERE_AGSL) }.onFailure { Log.w("PrismVibe", "AGSL unavailable; using animated gradients", it) }.getOrNull() }
    Canvas(modifier) {
        if (size.minDimension <= 0f) return@Canvas
        val level = bass()
        if (shader == null) {
            drawSoftSphere(a, b, c, level, opacity, time())
        } else {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("center", center.x, center.y)
            shader.setFloatUniform("time", time())
            shader.setFloatUniform("bass", level)
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
