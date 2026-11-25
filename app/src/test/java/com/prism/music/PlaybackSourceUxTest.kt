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

    @Test fun contextStreamUsesAlgorithmOrderAndDoesNotNeedAutoplay() {
        val source = QueueSource(QueueKind.RADIO, "Radio", "seed")
        assertFalse(source.isMyWave)
        assertTrue(source.continuousRecommendations)
        assertFalse(source.allowsOrderModes)
        assertTrue(source.matchesTrackStream("seed", "Radio"))
        assertFalse(source.matchesTrackStream("another", "Radio"))
        assertFalse(source.matchesTrackStream("seed", "Artist"))
    }

    @Test fun finiteQueuesKeepNativeControlsAndOptionalAutoplay() {
        for (kind in listOf(QueueKind.PLAYLIST, QueueKind.ALBUM, QueueKind.LIBRARY, QueueKind.SINGLE)) {
            val source = QueueSource(kind, "Music")
            assertFalse(source.continuousRecommendations)
            assertTrue(source.allowsOrderModes)
        }
    }

    @Test fun collectionVibeIsContinuousWithoutASingleSeed() {
        val songs = (1..30).map { com.prism.music.data.model.Song("id$it", "Track $it") }
        val anchors = com.prism.music.data.foryou.VibeContext.anchors(songs)
        val source = QueueSource(QueueKind.RADIO, "Vibe", vibeAnchors = anchors)
        assertTrue(source.continuousRecommendations)
        assertFalse(source.allowsOrderModes)
        assertEquals(12, anchors.size)
        assertEquals(songs.first(), anchors.first())
        assertEquals(songs.last(), anchors.last())
        assertNotEquals(com.prism.music.data.foryou.VibeContext.rotated(anchors, 0),
            com.prism.music.data.foryou.VibeContext.rotated(anchors, MyWave.HORIZON))
    }
}
