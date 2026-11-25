package com.prism.music

import com.prism.music.data.foryou.MyWave
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceUxTest {
    @Test fun streamUsesItsOwnContinuationAndOrder() {
        val source = QueueSource(QueueKind.RADIO, "Stream", "seed", MyWave.Style().origin)
        assertTrue(source.isMyWave)
        assertTrue(source.continuousRecommendations)
        assertFalse(source.allowsOrderModes)
    }

    @Test fun radioKeepsNativeOrderControlsButDoesNotNeedAutoplay() {
        val source = QueueSource(QueueKind.RADIO, "Radio", "seed")
        assertFalse(source.isMyWave)
        assertTrue(source.continuousRecommendations)
        assertTrue(source.allowsOrderModes)
    }

    @Test fun finiteQueuesKeepNativeControlsAndOptionalAutoplay() {
        for (kind in listOf(QueueKind.PLAYLIST, QueueKind.ALBUM, QueueKind.LIBRARY, QueueKind.SINGLE)) {
            val source = QueueSource(kind, "Music")
            assertFalse(source.continuousRecommendations)
            assertTrue(source.allowsOrderModes)
        }
    }
}
