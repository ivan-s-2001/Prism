package com.prism.music

import com.prism.music.ui.screens.smoothVibe
import com.prism.music.ui.screens.advanceVibeTime
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeMotionTest {
    @Test fun phaseMovesWithoutAudioAndWhilePaused() {
        val start = 7f
        assertTrue(advanceVibeTime(start, 0.033f, 1f, false) > start)
        assertTrue(advanceVibeTime(start, 0.033f, 1f, true) > advanceVibeTime(start, 0.033f, 1f, false))
        assertTrue(advanceVibeTime(start, 0.033f, 0f, true) == start)
        assertTrue(advanceVibeTime(start, 0.066f, 1f, false).isFinite())
    }

    @Test fun attackIsFastAndReleaseIsGentle() {
        val attack = smoothVibe(0f, 1f)
        val release = smoothVibe(1f, 0f)
        assertTrue(attack in 0.3f..0.5f)
        assertTrue(release in 0.8f..0.95f)
        var level = 1f
        repeat(60) { level = smoothVibe(level, 0f) }
        assertTrue(level < 0.001f)
    }

    @Test fun envelopeIsIndependentOfFrameRate() {
        fun rise(fps: Int): Float {
            var value = 0f
            repeat(fps) { value = smoothVibe(value, 0.75f, 1f / fps) }
            return value
        }
        fun fall(fps: Int): Float {
            var value = 1f
            repeat(fps) { value = smoothVibe(value, 0f, 1f / fps) }
            return value
        }
        assertTrue(kotlin.math.abs(rise(30) - rise(60)) < 0.0001f)
        assertTrue(kotlin.math.abs(fall(30) - fall(60)) < 0.0001f)
    }

    @Test fun invalidInputCannotPoisonTheAnimation() {
        assertTrue(smoothVibe(0.5f, Float.NaN) in 0f..0.5f)
        assertTrue(smoothVibe(0f, 20f) in 0f..1f)
        assertTrue(smoothVibe(0f, -1f) == 0f)
    }
}
