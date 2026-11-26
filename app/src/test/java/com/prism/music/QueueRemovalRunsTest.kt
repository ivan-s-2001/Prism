package com.prism.music

import com.prism.music.playback.removalRuns
import com.prism.music.playback.boundaryAfterRemoval
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRemovalRunsTest {
    @Test fun longQueueRemovedInOneBatch() {
        assertEquals(listOf(1..9999), removalRuns(1, 10000) { true })
    }
    @Test fun manualTracksRemainInOrder() {
        assertEquals(listOf(8..9, 4..6, 1..2), removalRuns(1, 10) { it !in setOf(3, 7) })
    }
    @Test fun queueBoundariesFollowRemovedRanges() {
        val removed = listOf(8..9, 4..6, 1..2)
        assertEquals(-1, boundaryAfterRemoval(-1, removed))
        assertEquals(0, boundaryAfterRemoval(0, removed))
        assertEquals(1, boundaryAfterRemoval(2, removed))
        assertEquals(2, boundaryAfterRemoval(5, removed))
        assertEquals(3, boundaryAfterRemoval(8, removed))
        assertEquals(3, boundaryAfterRemoval(10, removed))
    }

    @Test fun noChangesDoNotTriggerTimeline() {
        assertEquals(emptyList<IntRange>(), removalRuns(1, 20) { false })
        assertEquals(emptyList<IntRange>(), removalRuns(4, 4) { true })
    }
}
