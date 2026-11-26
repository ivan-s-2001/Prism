package com.prism.music

import com.prism.music.playback.removalRuns
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRemovalRunsTest {
    @Test fun longQueueRemovedInOneBatch() {
        assertEquals(listOf(1..9999), removalRuns(1, 10000) { true })
    }
    @Test fun manualTracksRemainInOrder() {
        assertEquals(listOf(8..9, 4..6, 1..2), removalRuns(1, 10) { it !in setOf(3, 7) })
    }
    @Test fun noChangesDoNotTriggerTimeline() {
        assertEquals(emptyList<IntRange>(), removalRuns(1, 20) { false })
        assertEquals(emptyList<IntRange>(), removalRuns(4, 4) { true })
    }
}
