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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.prism.music.ui.player.rememberVisualizer
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismTokens
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
    float r = length(uv);
    float spin = time * 0.075 + 0.38 * exp(-r * 2.8);
    float cs = cos(spin);
    float sn = sin(spin);
    float2 p = float2(uv.x * cs - uv.y * sn, uv.x * sn + uv.y * cs);

    float cloud = fbm(p * 2.0 + float2(time * 0.045, -time * 0.035));
    float detail = fbm(p * 3.8 + float2(cloud * 1.7, -cloud * 1.4) + float2(-time * 0.025, time * 0.04));
    float radius = 0.30 * (1.0 + 0.12 * bass);
    float edge = r + (cloud - 0.5) * (0.14 + 0.12 * energy) + (detail - 0.5) * 0.065;
    float outer = 1.0 - smoothstep(radius * 0.85, radius * 1.8, edge);
    float inner = smoothstep(radius * 0.30, radius * 0.90, edge);
    float silk = smoothstep(0.18, 0.82, detail) * 0.72 + cloud * cloud * 0.28;
    float light = outer * inner * (0.22 + silk * 0.95) * (0.82 + 0.48 * bass);
    float3 col = mix(colA, colB, smoothstep(0.18, 0.82, cloud));
    float3 c = col * light * 1.35;
    float al = clamp(max(c.r, max(c.g, c.b)), 0.0, 1.0);
    return half4(half3(min(c, float3(al))) * half(opacity), half(al * opacity));
}
"""

/** A station ambience, not an audio capture service. Reads Prism's existing audio tap only while visible. */
@Composable
internal fun VibeSphere(
    modifier: Modifier = Modifier,
    animated: Boolean,
    energy: Float,
    palette: List<Color>? = null,
) {
    val settings = LocalAppSettings.current
    val ui = LocalUi.current
    val dark = LocalIsDark.current
    val scheme = MaterialTheme.colorScheme
    val colors = if (ui.classicControls) listOf(scheme.primary, scheme.secondary)
        else palette?.takeIf { it.size >= 2 } ?: PrismTokens.spectrumColors(dark).map { it.second }
    val a = colors.firstOrNull() ?: scheme.primary
    // Spectrum often ends with its starting color; choose a hue within it instead.
    val b = colors.getOrElse((colors.size * 2 / 3).coerceAtMost(colors.lastIndex)) { scheme.secondary }
    val motion = animated && !settings.reduceMotion && !settings.performanceMode
    val visualizer = rememberVisualizer(motion)
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
                    time.floatValue += (dt / 1e9f).coerceIn(0f, 0.1f)
                    level.floatValue = liveLevel.value?.floatValue?.coerceIn(0f, 1f) ?: 0f
                    last = now
                }
            }
        }
    }
    val bass = { if (motion) level.floatValue else 0f }
    val opacity = if (dark) 0.90f else 0.70f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.performanceMode) {
        ShaderSphere(modifier, a, b, energy.coerceIn(0f, 1f), opacity, { time.floatValue }, bass)
    } else {
        Canvas(modifier) { drawSoftSphere(a, b, bass(), opacity) }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderSphere(
    modifier: Modifier, a: Color, b: Color, energy: Float, opacity: Float,
    time: () -> Float, bass: () -> Float,
) {
    // RuntimeShader compilation can fail on a device's Skia implementation. Keep the screen usable.
    val shader = remember { runCatching { RuntimeShader(SPHERE_AGSL) }.getOrNull() }
    val brush = remember(shader) { shader?.let { ShaderBrush(it) } }
    Canvas(modifier) {
        if (size.minDimension <= 0f) return@Canvas
        val level = bass()
        if (shader == null || brush == null) {
            drawSoftSphere(a, b, level, opacity)
        } else {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("center", center.x, center.y)
            shader.setFloatUniform("time", time())
            shader.setFloatUniform("bass", level)
            shader.setFloatUniform("energy", energy)
            shader.setFloatUniform("opacity", opacity)
            shader.setFloatUniform("colA", a.red, a.green, a.blue)
            shader.setFloatUniform("colB", b.red, b.green, b.blue)
            drawRect(brush)
        }
    }
}

/** Portable gradient (API 26–32 and Performance mode); no per-pixel noise shader. */
private fun DrawScope.drawSoftSphere(a: Color, b: Color, bass: Float, opacity: Float) {
    if (size.minDimension <= 0f) return
    val r = size.minDimension * (0.48f + 0.025f * bass)
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.28f to Color.Transparent,
            0.50f to b.copy(alpha = 0.20f * opacity),
            0.65f to a.copy(alpha = (0.55f + bass * 0.15f) * opacity),
            0.84f to b.copy(alpha = 0.18f * opacity),
            1f to Color.Transparent,
            center = center,
            radius = r,
        ), radius = r, center = center,
    )
}
