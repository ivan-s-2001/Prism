package com.prism.music

import com.prism.music.playback.VibeQueuePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class VibeQueuePolicyTest {
    @Test fun reservesSeveralUnheardSourceTracks() {
        assertEquals(listOf(2, 3, 4, 5), VibeQueuePolicy.initial((1..8).toList()) { it == 1 })
    }
    @Test fun aFullyHeardContextStillStartsOnOneTrack() {
        assertEquals(listOf(1), VibeQueuePolicy.initial(listOf(1, 2, 3)) { true })
    }
    @Test fun retryIsBounded() {
        assertEquals(listOf(1500L, 4000L, 10000L, null), (0..3).map(VibeQueuePolicy::retryDelay))
    }
}
