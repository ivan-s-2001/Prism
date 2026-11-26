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

    @Test fun excludesFalseMatchesEvenWithoutExplicitPreference() {
        val a = explicit.copy(id = "original", title = "Night Drive")
        val unrelated = explicit.copy(id = "other", artists = listOf(ArtistRef("Band tribute", "other")))
        val sameNameDifferentId = explicit.copy(id = "other2", artists = listOf(ArtistRef("Band", "other")))
        val missingArtist = explicit.copy(id = "unknown", artists = emptyList())
        val extraArtist = explicit.copy(id = "feature", artists = a.artists + ArtistRef("Guest"))
        val wrongDuration = explicit.copy(id = "length", durationSec = 205)
        val wrongSubtitle = explicit.copy(id = "part", title = "Night Drive (Part 2)")
        val cleanAlbum = explicit.copy(id = "edited", album = com.prism.music.data.model.AlbumRef("Clean"))
        assertEquals(listOf(explicit.id), SongVersions.candidates(a,
            listOf(unrelated, sameNameDifferentId, missingArtist, extraArtist, wrongDuration, wrongSubtitle, cleanAlbum, explicit)
        ).map { it.id })
    }

    @Test fun rejectsUncertainOrModifiedRecordings() {
        listOf(
            explicit.copy(title = "Night Drive (Remix)"),
            explicit.copy(title = "Night Drive (Clean)"),
            explicit.copy(title = "Night Drive (с цензурой)"),
            explicit.copy(title = "Night Drive (без ненормативной лексики)"),
            explicit.copy(title = "Night Drive (ускоренная версия)"),
            explicit.copy(title = "Night Drive (ремикс)"),
            explicit.copy(durationSec = 210),
            explicit.copy(durationSec = 0),
            explicit.copy(artists = listOf(ArtistRef("Band", "other"))),
            explicit.copy(isVideo = true),
            explicit.copy(explicit = false),
        ).forEach { assertFalse(SongVersions.preferredOriginal(clean, it)) }
    }
}
