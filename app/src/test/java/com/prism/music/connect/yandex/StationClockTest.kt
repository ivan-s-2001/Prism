package com.prism.music.connect.yandex

import org.junit.Assert.*
import org.junit.Test

class StationClockTest {
    private val url = "http://192.168.1.2:47322/cast/session/track.wav"

    @Test fun missingProgressDoesNotAdvanceClock() {
        val clock = StationClock("song").withObservation("song", 12000, 180000, true, false)
        repeat(1000) {
            assertEquals(12000L, clock.withObservation("song", null, null, true, false).positionMs)
        }
    }

    @Test fun receiverSeekBackAndNewSong() {
        val clock = StationClock("song", 12000, 180000)
        assertEquals(2000L, clock.withObservation("song", 2000, null, true, false).positionMs)
        assertEquals(0L, clock.withObservation("new", null, null, null, true).positionMs)
        assertEquals(180000L, clock.withObservation("song", 190000, null, true, false).positionMs)
    }

    private fun frame(at: Long, playing: Boolean, position: Long?, id: String? = url) =
        StationPlaybackState("music", id, "Track", playing, position, 180000, 50, at)

    @Test fun endRequiresMatchingPlaybackNearEndAndStoppedFrame() {
        val gate = StationEndGate()
        assertFalse(gate.observe(frame(1000, true, 179500), url, 180000, true))
        assertTrue(gate.observe(frame(1500, false, null, null), url, 180000, true))
        assertFalse(gate.observe(frame(1600, false, null, null), url, 180000, true))
    }

    @Test fun stopMidSongOrForeignSourceDoesNotAdvance() {
        val gate = StationEndGate()
        assertFalse(gate.observe(frame(1000, true, 50000), url, 180000, true))
        assertFalse(gate.observe(frame(1500, false, null, null), url, 180000, true))
        gate.reset()
        assertFalse(gate.observe(frame(2000, true, 179500), url, 180000, true))
        assertFalse(gate.observe(frame(2500, false, null, "foreign"), url, 180000, true))
        gate.reset()
        assertFalse(gate.observe(frame(3000, true, 179500), url, 180000, true))
        assertFalse(gate.observe(frame(3500, false, null, null), url, 180000, false))
    }
}
