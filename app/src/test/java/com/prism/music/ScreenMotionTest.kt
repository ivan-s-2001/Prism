package com.prism.music

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenMotionTest {
    @Test fun tabRestoreUsesFadeInsteadOfDetailSlide() {
        assertEquals(ScreenMotion.TAB, screenMotion(true, true, "album-entry", "artist-entry", "album-entry" to "artist-entry"))
    }
    @Test fun performanceAndReducedMotionOverrideSlide() {
        assertEquals(ScreenMotion.NONE, screenMotion(false, true, "a", "b", "a" to "b"))
    }
    @Test fun backFromRestoredTabIsNotARepeatedTabSwitch() {
        assertEquals(ScreenMotion.DETAIL, screenMotion(true, true, "b", "a", "a" to "b"))
    }
    @Test fun sameEntryAndDisabledSlides() {
        assertEquals(ScreenMotion.NONE, screenMotion(true, true, "a", "a", null))
        assertEquals(ScreenMotion.FADE, screenMotion(true, false, "a", "b", null))
    }
}
