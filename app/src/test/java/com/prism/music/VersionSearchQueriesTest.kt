package com.prism.music

import com.prism.music.data.SongVersions
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class VersionSearchQueriesTest {
    @Test fun explicitSearchRetainsArtistAndStripsCleanSuffix() {
        val song = Song("yt", "Песня (Clean)", listOf(ArtistRef("Артист")), durationSec = 180)
        val queries = SongVersions.searchQueries(song, true)
        assertEquals("Артист Песня", queries.first())
        assertTrue(queries.contains("Артист Песня без цензуры"))
        assertTrue(queries.contains("Артист Песня explicit"))
        assertTrue(queries.all { it.contains("Артист") })
        assertEquals(queries.distinct(), queries)
    }
}
