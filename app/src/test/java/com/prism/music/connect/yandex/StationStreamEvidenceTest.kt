package com.prism.music.connect.yandex

import org.junit.Assert.*
import org.junit.Test

class StationStreamEvidenceTest {
    private val url = "http://192.168.1.2:47322/cast/session/track.wav"
    private fun frame(id: String?, at: Long = 101, playing: Boolean? = true, type: String = "music") =
        StationPlaybackState(type, id, "Track", playing, 12000, 180000, 50, at)

    @Test fun anonymousStreamRequiresNewDelivery() {
        val gate = StationStreamEvidence()
        gate.begin(url, 5, 100)
        assertEquals("", gate.observe(frame(""), { 5 }).id)
        assertEquals(url, gate.observe(frame(""), { 6 }).id)
        assertEquals(url, gate.observe(frame("", at = 102, playing = false), { 6 }).id)
    }

    @Test fun staleAndMissingIdsAreNotProof() {
        val gate = StationStreamEvidence()
        gate.begin(url, 5, 100)
        assertEquals("", gate.observe(frame("", at = 99), { 6 }).id)
        assertNull(gate.observe(frame(null), { 6 }).id)
        assertEquals("", gate.observe(frame("", playing = false), { 6 }).id)
    }

    @Test fun foreignPlaybackInvalidatesAnonymousAttribution() {
        val gate = StationStreamEvidence()
        gate.begin(url, 0, 100)
        assertEquals(url, gate.observe(frame(""), { 1 }).id)
        assertEquals("native", gate.observe(frame("native", at = 102), { 1 }).id)
        assertEquals("", gate.observe(frame("", at = 103, playing = false), { 1 }).id)
        assertEquals("", gate.observe(frame("", type = "bluetooth"), { 1 }).id)
    }

    @Test fun newAttemptMustNotReusePreviousDelivery() {
        val gate = StationStreamEvidence()
        gate.begin(url, 0, 100)
        assertEquals(url, gate.observe(frame(""), { 1 }).id)
        gate.begin(url, 1, 200)
        assertEquals("", gate.observe(frame("", at = 201), { 1 }).id)
        assertEquals(url, gate.observe(frame("", at = 202), { 2 }).id)
        gate.clear()
        assertEquals("", gate.observe(frame("", at = 203), { 3 }).id)
    }
}
