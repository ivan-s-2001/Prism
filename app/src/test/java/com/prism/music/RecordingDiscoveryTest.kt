package com.prism.music

import com.prism.music.data.stream.*
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.ExplicitContent
import org.junit.Assert.*
import org.junit.Test

class RecordingDiscoveryTest {
    private val source = RecordingSource.BANDCAMP
    @Test fun orderRestoresAndTogglesWithoutDuplicates() {
        val plan = RecordingSourcePlan.restore("BANDCAMP,BANDCAMP,UNKNOWN", setOf("AUDIUS"))
        assertEquals(source, plan.order.first())
        assertEquals(RecordingSource.entries.size, plan.order.size)
        assertFalse(RecordingSource.AUDIUS in plan.enabled)
        assertEquals(source, plan.move(source, 1).order[1])
        assertTrue(RecordingSource.AUDIUS in plan.toggle(RecordingSource.AUDIUS).enabled)
    }

    @Test fun sourceFailuresAndWrongArtistsNeverHideGoodMatches() {
        val original = Song("yt", "Hokage", listOf(ArtistRef("Big Baby Tape")), durationSec = 175)
        val good = RecordingHit(original.copy(id = "bc:artist:hokage"), source)
        val bad = RecordingHit(original.copy(id = "bc:other:hokage", artists = listOf(ArtistRef("Other"))), source)
        val plan = RecordingSourcePlan.restore(null, emptySet())
        val results = listOf(
            RecordingSearchResult(source, listOf(bad, good, good)),
            RecordingSearchResult(RecordingSource.AUDIUS, failed = true),
        )
        assertEquals(listOf(good), RecordingSearchRank.rank(original, plan, results, ExplicitContent.SHOW))
        assertTrue(RecordingSearchRank.rank(original, plan.toggle(source), results, ExplicitContent.SHOW).isEmpty())
    }
}
