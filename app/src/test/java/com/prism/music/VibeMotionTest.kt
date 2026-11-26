package com.prism.music

import com.prism.music.ui.screens.smoothVibe
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeMotionTest {
    @Test fun attackIsFastAndReleaseIsGentle() {
        val attack = smoothVibe(0f, 1f)
        val release = smoothVibe(1f, 0f)
        assertTrue(attack in 0.3f..0.5f)
        assertTrue(release in 0.8f..0.95f)
        var level = 1f
        repeat(60) { level = smoothVibe(level, 0f) }
        assertTrue(level < 0.001f)
    }

    @Test fun invalidInputCannotPoisonTheAnimation() {
        assertTrue(smoothVibe(0.5f, Float.NaN) in 0f..0.5f)
        assertTrue(smoothVibe(0f, 20f) in 0f..1f)
        assertTrue(smoothVibe(0f, -1f) == 0f)
    }
}
