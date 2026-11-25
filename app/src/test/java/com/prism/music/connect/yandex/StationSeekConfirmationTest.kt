package com.prism.music.connect.yandex

import org.junit.Assert.*
import org.junit.Test

class StationSeekConfirmationTest {
    private val url = "http://192.168.1.3:47322/cast/session/song.wav"
    private fun state(id: String = url, position: Long? = 45000, at: Long = 101) =
        StationPlaybackState("music", id, "Song", true, position, 180000, 50, at)

    @Test fun actualSeekConfirmed() {
        assertTrue(StationSeekConfirmation.matches(state(), url, 45000, 100))
        assertTrue(StationSeekConfirmation.matches(state(position = 46100), url, 45000, 100))
    }
    @Test fun rejectUnchangedPosition() {
        assertFalse(StationSeekConfirmation.matches(state(position = 12000), url, 45000, 100))
    }
    @Test fun rejectOldFrames() {
        assertFalse(StationSeekConfirmation.matches(state(at = 100), url, 45000, 100))
        assertFalse(StationSeekConfirmation.matches(state(at = 99), url, 45000, 100))
    }
    @Test fun rejectOtherStreamAndMissingProgress() {
        assertFalse(StationSeekConfirmation.matches(state(id = "old"), url, 45000, 100))
        assertFalse(StationSeekConfirmation.matches(state(position = null), url, 45000, 100))
        assertFalse(StationSeekConfirmation.matches(null, url, 45000, 100))
    }
}
