package com.prism.music.connect.yandex

import org.junit.Assert.*
import org.junit.Test

class StationPlaybackStateTest {
    private val url = "http://192.168.1.2:1234/cast/session/song.wav"

    private fun frame(playing: Boolean, id: String = url, progress: String = "3.25",
                      volume: String = "0.55", type: String = "music") =
        """{"state":{"playing":$playing,"volume":$volume,"playerState":{"id":"$id","title":"Same title","playerType":"$type","hasPause":true,"progress":$progress,"duration":180}}}"""

    @Test fun playingStateContainsActualPositionAndVolume() {
        val s = requireNotNull(StationPlaybackState.parse(frame(true), 42))
        assertEquals(true, s.playing)
        assertTrue(s.matches(url))
        assertEquals(3250L, s.position)
        assertEquals(180000L, s.duration)
        assertEquals(55, s.volume)
        assertEquals(42L, s.at)
    }

    @Test fun hasPauseDoesNotMeanPlaying() {
        val s = requireNotNull(StationPlaybackState.parse(frame(false), 42))
        assertEquals(false, s.playing)
        assertTrue(s.matches(url))
    }

    @Test fun foreignTrackWithSameTitleDoesNotMatch() {
        val s = requireNotNull(StationPlaybackState.parse(frame(true, id = "other"), 42))
        assertFalse(s.matches(url))
        assertFalse(s.matches(null))
    }

    @Test fun bluetoothDoesNotMatch() {
        assertFalse(requireNotNull(StationPlaybackState.parse(frame(true, type = "bluetooth"), 1)).matches(url))
    }

    @Test fun replyWithoutStateIsNotPlaybackEvidence() {
        for (text in listOf("", "not JSON", "[]", "{}", """{"status":"success","requestId":"abc"}""")) {
            assertNull(StationPlaybackState.parse(text, 42))
        }
    }

    @Test fun missingPlayingIsUnknownNotStopped() {
        val s = requireNotNull(StationPlaybackState.parse("""{"state":{"playerState":{"hasPause":true}}}""", 42))
        assertNull(s.playing)
        assertFalse(s.matches(url))
    }

    @Test fun rejectBadNumericValues() {
        for (value in listOf("-1", "1e99", "null", "\"NaN\"", "{}")) {
            val s = requireNotNull(StationPlaybackState.parse(frame(true, progress = value), 42))
            assertNull(s.position)
        }
        for (value in listOf("-0.01", "1.01", "null", "\"NaN\"", "{}")) {
            assertNull(requireNotNull(StationPlaybackState.parse(frame(true, volume = value), 42)).volume)
        }
    }

    @Test fun everyVolumeStepRoundTrips() {
        for (n in 0..100) {
            val s = requireNotNull(StationPlaybackState.parse(frame(true, volume = (n / 100.0).toString()), 42))
            assertEquals(n, s.volume)
        }
    }
}
