package com.prism.music.data.foryou

import org.junit.Assert.*
import org.junit.Test

class VibeGraphTest {
    private fun e(track: String, artist: String, at: Long, w: Double = 1.0) =
        VibeGraph.Event(track, artist, at, w)

    @Test fun oneRepeatedSessionDoesNotBecomeManyIndependentObservations() {
        val repeated = (0 until 20).flatMap { i ->
            listOf(e("a", "x", i * 1000L), e("b", "y", i * 1000L + 500))
        }
        val graph = VibeGraph.build(repeated)
        val score = requireNotNull(graph.affinity("a", "b", "x", "y"))
        assertEquals(1.0 / 3.0, score, 1e-9)
        val later = repeated.map { it.copy(at = it.at + 3_600_000L) }
        val two = VibeGraph.build(repeated + later)
        assertTrue(requireNotNull(two.affinity("a", "b", "x", "y")) > score)
    }

    @Test fun frequentlyPlayedTracksDoNotDominateMerelyByExposure() {
        val related = listOf(e("seed", "a", 0), e("match", "b", 1000),
            e("seed", "a", 3_600_000), e("match", "b", 3_601_000),
            e("seed", "a", 7_200_000), e("popular", "c", 7_201_000))
        val unrelated = (3..40).map { e("popular", "c", it * 3_600_000L) }
        val graph = VibeGraph.build(related + unrelated)
        assertTrue(requireNotNull(graph.affinity("seed", "match", "a", "b")) >
            requireNotNull(graph.affinity("seed", "popular", "a", "c")))
    }

    @Test fun artistLinksGeneralizeToUnheardRecordingsWithoutInventingOtherLinks() {
        val graph = VibeGraph.build(listOf(e("a", "x", 0), e("b", "y", 1000),
            e("a", "x", 3_600_000), e("b", "y", 3_601_000)))
        assertTrue(requireNotNull(graph.affinity("new-x", "new-y", "x", "y")) > 0)
        assertNull(graph.affinity("new-x", "unrelated", "x", "z"))
        assertNull(graph.affinity("new-x", "other-x", "x", "x"))
    }

    @Test fun skipsAndSessionGapsDoNotCreatePositiveEdges() {
        val graph = VibeGraph.build(listOf(e("a", "x", 0), e("b", "y", 1000, 0.0),
            e("c", "z", 3_600_000)))
        assertNull(graph.affinity("a", "b", "x", "y"))
        assertNull(graph.affinity("a", "c", "x", "z"))
        assertEquals(0.0, VibeGraph.listeningWeight(15_000, 180_000), 1e-9)
        assertEquals(0.8, VibeGraph.listeningWeight(144_000, 180_000), 1e-9)
    }
}
