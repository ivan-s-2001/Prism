package com.prism.music

import com.prism.music.ui.components.matchesCurrentTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTrackMarkTest {
    @Test fun sameTrackAndItsSelectedVersionAreMarked() {
        assertTrue(matchesCurrentTrack("song", "song", "song") { null })
        assertTrue(matchesCurrentTrack("original", "version", "version") { null })
        assertFalse(matchesCurrentTrack("other", "other", "song") { null })
        assertFalse(matchesCurrentTrack("song", "song", null) { null })
    }

    @Test fun audioVideoCounterpartsAreMarked() {
        val counterparts = mapOf("audio" to "video")
        assertTrue(matchesCurrentTrack("video", "video", "audio") { counterparts[it] })
        assertTrue(matchesCurrentTrack("audio", "audio", "video") { counterparts[it] })
    }
}
