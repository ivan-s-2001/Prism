package com.prism.music

import androidx.compose.ui.unit.dp
import com.prism.music.ui.player.MiniWaveSkips
import com.prism.music.ui.player.miniWaveSkips
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniWaveSkipsTest {
    @Test fun normalMiniPlayerRetainsUserPreferences() {
        assertEquals(MiniWaveSkips(true, true),
            miniWaveSkips(280.dp, 44.dp, 24.dp, true, true, true, false))
    }
    @Test fun wavePrioritizesReactionsAndCredits() {
        assertEquals(MiniWaveSkips(false, false),
            miniWaveSkips(320.dp, 44.dp, 24.dp, true, true, true, true))
        assertEquals(MiniWaveSkips(false, true),
            miniWaveSkips(360.dp, 44.dp, 24.dp, true, true, true, true))
        assertEquals(MiniWaveSkips(true, true),
            miniWaveSkips(440.dp, 44.dp, 24.dp, true, true, true, true))
    }
}
