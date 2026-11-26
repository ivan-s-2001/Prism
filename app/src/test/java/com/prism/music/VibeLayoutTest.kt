package com.prism.music

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.StreamLayout
import com.prism.music.ui.screens.vibeHeroSize
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeLayoutTest {
    @Test fun compactDoesNotConsumeTheScreen() {
        val center = vibeHeroSize(360.dp, Dp.Infinity, StreamLayout.CENTERED, false, 1f)
        val compact = vibeHeroSize(360.dp, Dp.Infinity, StreamLayout.COMPACT, false, 1f)
        assertTrue(compact < center)
        assertTrue(compact <= 154.dp)
    }

    @Test fun smallScreensAndTvStayInsideConstraints() {
        for (layout in StreamLayout.entries) {
            val narrow = vibeHeroSize(240.dp, 300.dp, layout, false, 1f)
            assertTrue(narrow <= 150.dp)
            val wide = vibeHeroSize(900.dp, 260.dp, layout, true, 1f)
            assertTrue(wide <= 260.dp && wide <= 387.dp)
        }
    }

    @Test fun scaleNeverExceedsTheLayoutBudget() {
        val full = vibeHeroSize(360.dp, Dp.Infinity, StreamLayout.CENTERED, false, 1f)
        val small = vibeHeroSize(360.dp, Dp.Infinity, StreamLayout.CENTERED, false, 0.6f)
        assertTrue(small < full)
    }
}
