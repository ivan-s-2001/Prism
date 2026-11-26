package com.prism.music

import androidx.compose.ui.geometry.Offset
import com.prism.music.ui.screens.waveBand
import com.prism.music.ui.screens.waveDrift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeSceneTest {
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
