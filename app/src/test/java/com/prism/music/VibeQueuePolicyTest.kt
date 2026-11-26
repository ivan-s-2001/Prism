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
    @Test fun automaticTransitionsApplyAllFiltersTogether() {
        fun allowed(explicit: Boolean = true, available: Boolean = true, manual: Boolean = false,
            disliked: Boolean = false, generated: Boolean = true, excluded: Boolean = false) =
            VibeQueuePolicy.transitionAllowed(explicit, available, true, manual, disliked, generated, excluded)
        assertEquals(true, allowed())
        assertEquals(false, allowed(disliked = true))
        assertEquals(false, allowed(excluded = true))
        assertEquals(false, allowed(available = false))
        assertEquals(false, allowed(explicit = false, manual = true))
        assertEquals(false, allowed(available = false, manual = true))
        assertEquals(true, allowed(manual = true, disliked = true, excluded = true))
        assertEquals(true, allowed(generated = false, excluded = true))
    }
    @Test fun manualSelectionStillHonorsExplicitFilter() {
        assertEquals(true, VibeQueuePolicy.transitionAllowed(true, true, false, false, true, true, true))
        assertEquals(false, VibeQueuePolicy.transitionAllowed(false, true, false, true, false, false, false))
    }
    @Test fun retryIsBounded() {
        assertEquals(listOf(1500L, 4000L, 10000L, null), (0..3).map(VibeQueuePolicy::retryDelay))
    }
}
