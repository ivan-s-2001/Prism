package com.prism.music

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.StreamLayout
import com.prism.music.ui.screens.vibeStageMinHeight
import com.prism.music.ui.screens.vibeFillsViewport
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class VibeLayoutTest {
    @Test fun twoEqualTilesFrameCenter() {
        val padding = com.prism.music.ui.screens.vibeOrbitPairPadding(
            androidx.compose.ui.unit.Dp(360f), androidx.compose.ui.unit.Dp(104f), androidx.compose.ui.unit.Dp(10f), 8)
        assertEquals(71f, padding.value, 0.001f)
        assertEquals(0.25f, com.prism.music.ui.screens.vibeOrbitDepth(-0.5f), 0.001f)
        assertEquals(0.25f, com.prism.music.ui.screens.vibeOrbitDepth(0.5f), 0.001f)
    }

    @Test fun scenarioOrbitWrapsBothDirections() {
        val n = 7
        val start = com.prism.music.ui.screens.vibeOrbitStart(n, 0)
        assertEquals(0, com.prism.music.ui.screens.vibeOrbitIndex(start, n))
        assertEquals(6, com.prism.music.ui.screens.vibeOrbitIndex(start - 1, n))
        assertEquals(0, com.prism.music.ui.screens.vibeOrbitIndex(start + n, n))
        assertEquals(start - 1, com.prism.music.ui.screens.vibeOrbitNearest(start, n, 6))
        assertEquals(start + 1, com.prism.music.ui.screens.vibeOrbitNearest(start, n, 1))
        assertEquals(1, com.prism.music.ui.screens.vibeOrbitCount(1))
        assertEquals(0, com.prism.music.ui.screens.vibeOrbitCount(0))
    }

    @Test fun sharedOrbitPreservesCenterAndSoftensEdges() {
        assertEquals(1f, com.prism.music.ui.screens.vibeOrbitScale(0f), 0.001f)
        assertEquals(0.80f, com.prism.music.ui.screens.vibeOrbitScale(1f), 0.001f)
        assertEquals(0.82f, com.prism.music.ui.screens.vibeOrbitAlpha(1f), 0.001f)
        assertEquals(0.25f, com.prism.music.ui.screens.vibeOrbitDrop(0.5f), 0.001f)
        assertEquals(48.dp, com.prism.music.ui.screens.vibeActionDiameter(84.dp, 0.1f))
        assertEquals(88.dp, com.prism.music.ui.screens.vibeActionDiameter(84.dp, 2f))
    }

    @Test fun scenarioOrbitIsSymmetricAndBounded() {
        assertEquals(0f, com.prism.music.ui.screens.vibeOrbitDepth(0f), 0.001f)
        assertEquals(0.5f, com.prism.music.ui.screens.vibeOrbitDepth(1f), 0.001f)
        assertEquals(0.5f, com.prism.music.ui.screens.vibeOrbitDepth(-1f), 0.001f)
        assertEquals(1f, com.prism.music.ui.screens.vibeOrbitDepth(5f), 0.001f)
        assertEquals(0f, com.prism.music.ui.screens.vibeOrbitDepth(Float.NaN), 0.001f)
    }

    @Test fun normalPhoneAndTvUseRemainingSpace() {
        assertTrue(vibeFillsViewport(360.dp, 640.dp))
        assertTrue(vibeFillsViewport(960.dp, 450.dp))
        assertTrue(!vibeFillsViewport(360.dp, 400.dp))
        assertTrue(!vibeFillsViewport(360.dp, Dp.Infinity))
    }

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
