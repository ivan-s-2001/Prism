package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.ambient.ambientAllowed
import com.prism.music.ui.ambient.ambientDrift
import com.prism.music.ui.ambient.ambientDue
import com.prism.music.ui.ambient.keepScreenOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AmbientLogicTest {
    @Test fun quietTimeRequiresActivePlaybackAndEnabledDeskMode() {
        val three = 3 * 60_000L
        assertTrue(ambientDue(true, true, 3, three))
        assertFalse(ambientDue(true, true, 3, three - 1))
        assertFalse(ambientDue(false, true, 3, three * 10))
        assertFalse(ambientDue(true, false, 3, three * 10))
        assertFalse(ambientDue(true, true, 0, three * 10))
    }

    @Test fun onlyChargingDeskModeAllowsAmbient() {
        assertTrue(ambientAllowed(3, true, true, false, false))
        assertFalse(ambientAllowed(0, true, true, false, false))
        assertFalse(ambientAllowed(3, false, true, false, false))
        assertFalse(ambientAllowed(3, true, false, false, false))
        assertFalse(ambientAllowed(3, true, true, true, false))
        assertFalse(ambientAllowed(3, true, true, false, true))
    }

    @Test fun screenStaysAwakeOnlyForActiveAmbientPlayback() {
        assertTrue(keepScreenOn(true, true))
        assertFalse(keepScreenOn(false, true))
        assertFalse(keepScreenOn(true, false))
    }

    @Test fun artworkDriftsWithinSafeBounds() {
        val points = (0 until 600 step 7).map { ambientDrift(it * 1_000L) }
        assertTrue(points.all { abs(it.x.value) <= 14f && abs(it.y.value) <= 10f })
        assertTrue(points.map { it.x.value.toInt() to it.y.value.toInt() }.distinct().size > 20)
    }

    @Test fun deskModeIsOffByDefault() {
        val ambient = AppSettings().ambient
        assertFalse(ambient.deskMode)
        assertEquals(3, ambient.ambientMinutes)
    }
}
