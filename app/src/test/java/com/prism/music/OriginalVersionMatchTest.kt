package com.prism.music

import com.prism.music.data.SongVersions
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class OriginalVersionMatchTest {
    private val clean = Song("a", "Night Drive (Clean)", listOf(ArtistRef("Band", "artist")), durationSec = 200)
    private val explicit = clean.copy(id = "b", title = "Night Drive", explicit = true)

    @Test fun prefersStrongExplicitMatch() {
        assertTrue(SongVersions.preferredOriginal(clean, explicit))
        assertEquals("b", SongVersions.candidates(clean, listOf(clean.copy(id = "c"), explicit), preferExplicit = true).first().id)
    }

    @Test fun rejectsUncertainOrModifiedRecordings() {
        listOf(
            explicit.copy(title = "Night Drive (Remix)"),
            explicit.copy(title = "Night Drive (Clean)"),
            explicit.copy(durationSec = 210),
            explicit.copy(durationSec = 0),
            explicit.copy(artists = listOf(ArtistRef("Band", "other"))),
            explicit.copy(isVideo = true),
            explicit.copy(explicit = false),
        ).forEach { assertFalse(SongVersions.preferredOriginal(clean, it)) }
    }
}
