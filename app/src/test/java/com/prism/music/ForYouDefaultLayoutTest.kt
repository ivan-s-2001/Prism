package com.prism.music

import com.prism.music.data.prefs.HomeSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouDefaultLayoutTest {
    @Test fun personalMusicPrecedesSeasonalAndRecap() {
        val keys = HomeSections.initial.map { it.key }
        assertEquals(keys.distinct(), keys)
        assertEquals(HomeSections.MADE_FOR_YOU, keys.first())
        assertTrue(keys.indexOf(HomeSections.RECENT) < keys.indexOf(HomeSections.REPLAY))
        assertTrue(keys.indexOf(HomeSections.MADE_FOR_YOU) < keys.indexOf(HomeSections.SEASONAL))
        assertTrue(keys.indexOf(HomeSections.QUICK_PICKS) < keys.indexOf(HomeSections.REPLAY))
    }
}
