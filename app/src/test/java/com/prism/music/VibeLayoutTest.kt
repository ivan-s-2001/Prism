package com.prism.music

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.StreamLayout
import com.prism.music.ui.screens.vibeStageMinHeight
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class VibeLayoutTest {
    @Test fun compactUsesLessSpace() {
        val center = vibeStageMinHeight(360.dp, Dp.Infinity, StreamLayout.CENTERED, true)
        val compact = vibeStageMinHeight(360.dp, Dp.Infinity, StreamLayout.COMPACT, true)
        assertTrue(compact < center)
    }
    @Test fun stageNeverExceedsAvailableHeight() {
        for (layout in StreamLayout.entries) {
            assertTrue(vibeStageMinHeight(240.dp, 180.dp, layout, true) <= 180.dp)
            assertTrue(vibeStageMinHeight(900.dp, 220.dp, layout, true) <= 220.dp)
        }
    }
    @Test fun disabledBackdropReservesNoSpace() {
        for (layout in StreamLayout.entries) {
            assertEquals(0.dp, vibeStageMinHeight(360.dp, Dp.Infinity, layout, false))
        }
    }
}
