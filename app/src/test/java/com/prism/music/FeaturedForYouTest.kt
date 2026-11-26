package com.prism.music

import com.prism.music.data.prefs.HomeSections
import org.junit.Assert.assertEquals
import org.junit.Test

class FeaturedForYouTest {
    @Test fun personalMixIsFirst() {
        assertEquals(HomeSections.MADE_FOR_YOU, HomeSections.initial.first().key)
    }
    @Test fun customLayoutIsNotChanged() {
        val custom = HomeSections.initial.reversed().map { it.copy(visible = false) }
        assertEquals(custom, HomeSections.upgradeForYou(custom))
    }
}
