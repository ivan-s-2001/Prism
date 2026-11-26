/*
 * SPDX-License-Identifier: GPL-3.0-only
 * AGSL port of the Prism light fields and yamusic-rs/android/src/wave.frag (GPL-3.0):
 * https://github.com/SpolzerMod/yamusic-rs/blob/bb98fa9af70f817732ef0d653629302edac94f57/android/src/wave.frag
 */
package com.prism.music.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.prism.music.data.prefs.StreamVisualStyle

/** Explicit IDs, independent of persisted enum order. */
internal fun waveShaderMode(style: StreamVisualStyle): Int = when (style) {
    StreamVisualStyle.WAVE -> 0
    StreamVisualStyle.RING -> 1
    StreamVisualStyle.GLOW -> 2
    StreamVisualStyle.EQUALIZER -> 3
    StreamVisualStyle.OFF -> 4
}

internal interface WaveGpuRenderer {
    /** False requests the existing CPU backend, e.g. for software canvases or a driver error. */
    fun draw(canvas: Canvas, width: Float, height: Float, frame: WaveRenderFrame): Boolean
}

internal fun createWaveGpuRenderer(): WaveGpuRenderer? {
    if (Build.VERSION.SDK_INT < 33) return null
    return try {
        AgslWaveRenderer()
    } catch (error: RuntimeException) {
        Log.w("PrismWave", "AGSL unavailable; using CPU wave", error)
        null
    }
}

@RequiresApi(33)
private class AgslWaveRenderer : WaveGpuRenderer {
    private val shader = RuntimeShader(WAVE_AGSL)
    private val paint = Paint()
    private val fft = FloatArray(28)

    override fun draw(canvas: Canvas, width: Float, height: Float, frame: WaveRenderFrame): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        if (width <= 0f || height <= 0f) return true
        return try {
            val sample = frame.sample
            for (i in fft.indices) {
                val band = sample.bands.getOrElse(i) { 0f }
                fft[i] = if (band.isFinite()) band.coerceIn(0f, 1f) else 0f
            }
            val aspect = width / height
            val scale = frame.scale.coerceIn(0.25f, 1f) * minOf(1.25f, aspect * 1.95f)
            shader.setFloatUniform("uSize", width, height)
            shader.setFloatUniform("uCenter", frame.center.x, frame.center.y)
            shader.setFloatUniform("uTime", if (sample.phase.isFinite()) sample.phase else 0f)
            shader.setFloatUniform("uBeat", if (sample.beat.isFinite()) sample.beat.coerceIn(0f, 1f) else 0f)
            shader.setFloatUniform("uScale", scale)
            shader.setFloatUniform("uOpacity", frame.colors.opacity.coerceIn(0f, 1f))
            shader.setFloatUniform("uEnergy", frame.energy.coerceIn(0f, 1f))
            shader.setFloatUniform("uMode", waveShaderMode(frame.style).toFloat())
            shader.setFloatUniform("uBands", *fft)
            frame.colors.palette.forEachIndexed { i, color ->
                shader.setFloatUniform("uColor$i", color.red, color.green, color.blue)
            }
            // Reassign after uniform updates so the Paint records the current shader instance.
            paint.shader = shader
            canvas.drawRect(0f, 0f, width, height, paint)
            true
        } catch (error: RuntimeException) {
            Log.w("PrismWave", "AGSL draw failed; using CPU wave", error)
            false
        }
    }
}

/**
 * Same four light-field models as the CPU renderer. GPU softness is analytic:
 * widened Gaussian cores, diffuse edges and thresholded broad highlights.
 * It deliberately avoids repeated full-resolution bitmap allocation or a large per-pixel blur loop.
 * The CPU path retains its separable blur; optical results are comparable, not pixel-identical.
 */
private const val WAVE_AGSL = """
uniform float2 uSize;
uniform float2 uCenter;
uniform float uTime;
uniform float uBeat;
uniform float uScale;
uniform float uOpacity;
uniform float uEnergy;
uniform float uMode;
uniform float uBands[28];
uniform float3 uColor0;
uniform float3 uColor1;
uniform float3 uColor2;
uniform float3 uColor3;
uniform float3 uColor4;
uniform float3 uColor5;

float gauss(float x) { return exp(-x*x); }

// AGSL requires constant array indices. Select the two FFT bins with a bounded decision tree.
float band(float p) {
    float x = clamp(p, 0.0, 1.0) * 27.0;
    if (x <= 14.0) { if (x <= 7.0) { if (x <= 4.0) { if (x <= 2.0) { if (x <= 1.0) { return mix(uBands[0], uBands[1], clamp(x - 0.0, 0.0, 1.0)); } else { return mix(uBands[1], uBands[2], clamp(x - 1.0, 0.0, 1.0)); } } else { if (x <= 3.0) { return mix(uBands[2], uBands[3], clamp(x - 2.0, 0.0, 1.0)); } else { return mix(uBands[3], uBands[4], clamp(x - 3.0, 0.0, 1.0)); } } } else { if (x <= 6.0) { if (x <= 5.0) { return mix(uBands[4], uBands[5], clamp(x - 4.0, 0.0, 1.0)); } else { return mix(uBands[5], uBands[6], clamp(x - 5.0, 0.0, 1.0)); } } else { return mix(uBands[6], uBands[7], clamp(x - 6.0, 0.0, 1.0)); } } } else { if (x <= 11.0) { if (x <= 9.0) { if (x <= 8.0) { return mix(uBands[7], uBands[8], clamp(x - 7.0, 0.0, 1.0)); } else { return mix(uBands[8], uBands[9], clamp(x - 8.0, 0.0, 1.0)); } } else { if (x <= 10.0) { return mix(uBands[9], uBands[10], clamp(x - 9.0, 0.0, 1.0)); } else { return mix(uBands[10], uBands[11], clamp(x - 10.0, 0.0, 1.0)); } } } else { if (x <= 13.0) { if (x <= 12.0) { return mix(uBands[11], uBands[12], clamp(x - 11.0, 0.0, 1.0)); } else { return mix(uBands[12], uBands[13], clamp(x - 12.0, 0.0, 1.0)); } } else { return mix(uBands[13], uBands[14], clamp(x - 13.0, 0.0, 1.0)); } } } } else { if (x <= 21.0) { if (x <= 18.0) { if (x <= 16.0) { if (x <= 15.0) { return mix(uBands[14], uBands[15], clamp(x - 14.0, 0.0, 1.0)); } else { return mix(uBands[15], uBands[16], clamp(x - 15.0, 0.0, 1.0)); } } else { if (x <= 17.0) { return mix(uBands[16], uBands[17], clamp(x - 16.0, 0.0, 1.0)); } else { return mix(uBands[17], uBands[18], clamp(x - 17.0, 0.0, 1.0)); } } } else { if (x <= 20.0) { if (x <= 19.0) { return mix(uBands[18], uBands[19], clamp(x - 18.0, 0.0, 1.0)); } else { return mix(uBands[19], uBands[20], clamp(x - 19.0, 0.0, 1.0)); } } else { return mix(uBands[20], uBands[21], clamp(x - 20.0, 0.0, 1.0)); } } } else { if (x <= 24.0) { if (x <= 23.0) { if (x <= 22.0) { return mix(uBands[21], uBands[22], clamp(x - 21.0, 0.0, 1.0)); } else { return mix(uBands[22], uBands[23], clamp(x - 22.0, 0.0, 1.0)); } } else { return mix(uBands[23], uBands[24], clamp(x - 23.0, 0.0, 1.0)); } } else { if (x <= 26.0) { if (x <= 25.0) { return mix(uBands[24], uBands[25], clamp(x - 24.0, 0.0, 1.0)); } else { return mix(uBands[25], uBands[26], clamp(x - 25.0, 0.0, 1.0)); } } else { return mix(uBands[26], uBands[27], clamp(x - 26.0, 0.0, 1.0)); } } } }
}

float lobe(float2 p, float i, float softness) {
    float phase = i * 2.094;
    float2 c = float2(0.09*sin(uTime*0.31+phase), 0.085*cos(uTime*0.23+phase*1.3));
    float angle = uTime*0.12+phase;
    float2 d = p-c;
    float2 q = float2(cos(angle)*d.x+sin(angle)*d.y,
        (-sin(angle)*d.x+cos(angle)*d.y)*1.25);
    float a = atan(q.y, q.x+0.000001);
    float seed = i*1.7;
    float edge = 1.0+0.22*sin(a*3.0+uTime*0.8+seed)+0.10*sin(a*5.0-uTime*1.1+seed*2.3);
    float radius = (0.27-i*0.025)*(1.0+0.16*uBeat);
    return gauss(length(q)*1.55/(radius*edge*softness))*(0.88-i*0.14);
}
float3 wave(float2 p, float s) {
    return float3(lobe(p,0.0,s),lobe(p,1.0,s),lobe(p,2.0,s));
}
float3 ring(float2 p, float s, float b) {
    float2 q = float2(p.x,p.y*1.10);
    float a = atan(q.y,q.x+0.000001);
    float radius = length(q);
    float target = 0.255+0.024*sin(a*3.0+uTime*0.72)+0.013*cos(a*5.0-uTime*0.58)+0.045*b+0.022*uBeat;
    float outer = gauss((radius-target)/(0.039*s));
    float inner = gauss((radius-target+0.054+0.012*sin(a*2.0-uTime))/(0.022*s));
    float halo = gauss((radius-target)/(0.093*s));
    float sector = 0.5+0.5*cos(a-uTime*0.42);
    return float3(outer*(0.45+0.60*sector),inner*(0.48+0.35*(1.0-sector)),
        halo*0.33+outer*(1.0-sector)*0.46);
}
float3 aurora(float2 p,float s) {
    float lx = p.x-(-0.30+0.07*sin(uTime*0.39));
    float ly = p.y-(-0.16+0.07*cos(uTime*0.29));
    float rx = p.x-(0.29+0.06*cos(uTime*0.34));
    float ry = p.y-(0.22+0.08*sin(uTime*0.41));
    float l = gauss(lx/(0.29*s))*gauss((ly+0.55*lx)/(0.43*s));
    float r = gauss(rx/(0.27*s))*gauss((ry-0.55*rx)/(0.42*s));
    float c = gauss((p.x+0.08*sin(p.y*5.0+uTime*0.5))/(0.46*s))*gauss((p.y+0.30)/(0.25*s));
    return float3(l*(0.76+0.20*uBeat),r*(0.82+0.14*uBeat),c*0.43);
}
float3 ribbon(float2 p,float s,float b,float fine) {
    float fade = gauss(p.x/(0.53*s));
    float bend = 0.052*sin(p.x*10.0+uTime*0.72)+0.026*cos(p.x*17.0-uTime*0.4);
    float spread = 0.077+0.10*b+0.032*uBeat;
    float upper = bend+spread+fine*0.045;
    float lower = bend-spread-fine*0.03;
    float a = gauss((p.y-upper)/((0.032+b*0.027)*s));
    float c = gauss((p.y-lower)/((0.038+fine*0.024)*s));
    float body = gauss((p.y-bend)/((spread+0.044)*s));
    float crossLight = gauss((p.y-bend*0.6)/(0.026*s));
    return fade*float3(a*0.98+body*0.09,c*0.94+body*0.09,body*0.35+crossLight*0.27);
}
float3 field(float2 p,float softness,float b,float fine) {
    if (uMode < 0.5) return wave(p,softness);
    if (uMode < 1.5) return ring(p,softness,b);
    if (uMode < 2.5) return aurora(p,softness);
    if (uMode < 3.5) return ribbon(p,softness,b,fine);
    return float3(0.0);
}
float3 colorize(float3 light) {
    return mix(uColor1,uColor0,smoothstep(0.1,0.85,light.x))*light.x
         + mix(uColor3,uColor2,smoothstep(0.1,0.85,light.y))*light.y
         + mix(uColor5,uColor4,smoothstep(0.1,0.85,light.z))*light.z;
}
float peak(float3 c) { return max(c.x,max(c.y,c.z)); }

half4 main(float2 frag) {
    if (uMode > 3.5) return half4(0.0);
    float2 uv = frag/uSize;
    float2 p = float2((uv.x-uCenter.x)*uSize.x/uSize.y,uCenter.y-uv.y)/uScale;
    float b = 0.0;
    float fine = 0.0;
    if (uMode > 0.5 && uMode < 1.5) {
        float turn = fract((atan(p.y*1.10,p.x+0.000001)-uTime*0.25)/6.2831853);
        b = band(1.0-abs(turn*2.0-1.0));
    } else if (uMode > 2.5 && uMode < 3.5) {
        b = band(uv.x);
        fine = band(uv.x*0.82+0.18);
    }
    float3 core = colorize(field(p,1.0,b,fine));
    float3 inner = colorize(field(p,1.13,b,fine))*0.88;
    float3 edge = colorize(field(p,1.48,b,fine))*0.58;
    float3 halo = colorize(field(p,1.85,b,fine))*0.35;
    float glow = smoothstep(0.18,0.75,peak(core));
    float diffuse = uMode > 1.5 && uMode < 2.5 ? 0.72 : 0.38;
    float weight = uMode > 1.5 && uMode < 2.5 ? 0.12 : 0.28;
    float sweep = 0.72+0.28*sin(uv.x*4.8+(1.0-uv.y)*3.2-uTime*0.42);
    float ambient = (1.0-smoothstep(0.0,0.78,1.0-uv.y))*0.105*sweep;
    float3 light = core*weight+inner*(1.0-weight)+edge*diffuse+halo*(0.38+0.24*uBeat)*(0.35+0.65*glow)
        + mix(uColor1,uColor3,uv.x)*ambient;
    float3 tone = 1.0-exp(-light*(1.48+0.18*uEnergy));
    // AGSL returns premultiplied color, matching the transparent CPU composition.
    return half4(tone*uOpacity,peak(tone)*uOpacity);
}
"""
