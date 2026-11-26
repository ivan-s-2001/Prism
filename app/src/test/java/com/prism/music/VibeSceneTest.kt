package com.prism.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.prism.music.ui.screens.WaveColors
import com.prism.music.ui.screens.WaveColorTransition
import com.prism.music.ui.screens.flowingWavePalette
import com.prism.music.ui.screens.waveBand
import com.prism.music.ui.screens.waveDrift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeSceneTest {
    @Test fun paletteRetargetStartsFromVisibleColor() {
        val first = WaveColors(List(6) { Color.Red }, 1f)
        val second = WaveColors(List(6) { Color.Blue }, 0.5f)
        val third = WaveColors(List(6) { Color.Green }, 0.8f)
        val transition = WaveColorTransition(first)
        assertEquals(first, transition.update(second, 100L, true))
        val middle = transition.at(700L)
        assertEquals(0.75f, middle.opacity, 0.001f)
        assertTrue(middle.palette != first.palette && middle.palette != second.palette)
        assertEquals(middle, transition.update(third, 700L, true))
        assertEquals(third, transition.at(1900L))
        assertEquals(first, transition.update(first, 2000L, false))
    }

    @Test fun colorFlowUsesContinuousThemePalette() {
        val colors = listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow)
        assertEquals(colors, flowingWavePalette(colors, 0f))
        val between = flowingWavePalette(colors, 0.5f / 0.045f)
        assertEquals(6, between.size)
        assertTrue(between != colors)
        assertEquals(colors, flowingWavePalette(colors, Float.NaN))
    }

    @Test fun driftIsBoundedAndStaticWhenDisabled() {
        assertEquals(Offset.Zero, waveDrift(7f, 1080f, false))
        assertEquals(Offset.Zero, waveDrift(Float.NaN, 1080f, true))
        val initial = waveDrift(0f, 1080f, true)
        val next = waveDrift(2f, 1080f, true)
        assertTrue(initial != next)
        assertTrue(kotlin.math.abs(next.x) <= 1080f * 0.032f)
        assertTrue(kotlin.math.abs(next.y) <= 1080f * 0.032f)
    }

    @Test fun frequencyFieldIsContinuousAtTheSeam() {
        val bands = floatArrayOf(0.1f, 0.5f, 1f)
        assertEquals(0.1f, waveBand(bands, 0f), 0.001f)
        assertEquals(1f, waveBand(bands, kotlin.math.PI.toFloat()), 0.001f)
        assertEquals(waveBand(bands, -0.01f), waveBand(bands, 0.01f), 0.001f)
        assertEquals(0f, waveBand(FloatArray(0), 1f), 0f)
    }
}
