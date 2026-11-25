package com.prism.music

import com.prism.music.data.UncensoredRules
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UncensoredRulesTest {
    private val source = Song("clean", "Example", listOf(ArtistRef("Artist", "UC1")), durationSec = 180)

    @Test fun prefersExplicitVersionOfSameTrack() {
        val explicit = source.copy(id = "explicit", explicit = true)
        assertEquals(explicit, UncensoredRules.best(source, listOf(explicit)))
    }

    @Test fun explicitBadgeAloneDoesNotJustifyReplacingExplicitSong() {
        val explicit = source.copy(explicit = true)
        assertNull(UncensoredRules.best(explicit, listOf(explicit.copy(id = "other"))))
    }

    @Test fun checksRussianExplicitTracksAndHistoricalArtistName() {
        val russian = source.copy(title = "Божественный план", explicit = true, artists = listOf(ArtistRef("Heronwater")))
        val old = russian.copy(id = "old", title = "Божественный план (без цензуры)", artists = listOf(ArtistRef("Heroinwater")), isVideo = true)
        assertEquals(old, UncensoredRules.best(russian, listOf(old)))
    }

    @Test fun refusesCoverWrongArtistAndDifferentDuration() {
        val cover = source.copy(id = "cover", title = "Example (cover)", explicit = true)
        val wrong = source.copy(id = "wrong", artists = listOf(ArtistRef("Another artist")), explicit = true)
        val long = source.copy(id = "long", durationSec = 200, explicit = true)
        val clean = source.copy(id = "radio", title = "Example (Clean)", explicit = true)
        assertNull(UncensoredRules.best(source, listOf(cover, wrong, long, clean)))
    }

    @Test fun neverReplacesLocalOrUploadedSongs() {
        val original = source.copy(id = "explicit", explicit = true)
        assertNull(UncensoredRules.best(source.copy(id = "local:42"), listOf(original)))
        assertNull(UncensoredRules.best(source.copy(isUpload = true), listOf(original)))
    }

    @Test fun thirdPartyArtistPrefixedUploadIsShownButNotAutoPicked() {
        val original = source.copy(title = "Божественный план", explicit = true, artists = listOf(ArtistRef("Heronwater")))
        val upload = original.copy(id = "upload", title = "Heroinwater — Божественный план (без цензуры)",
            artists = listOf(ArtistRef("Another uploader")), isVideo = true)
        assertEquals(listOf(upload), UncensoredRules.review(original, listOf(upload)))
        assertNull(UncensoredRules.best(original, listOf(upload)))
    }

    @Test fun officialTopicChannelMaySupplyMarkedOriginal() {
        val original = source.copy(title = "Божественный план", explicit = true, artists = listOf(ArtistRef("Heronwater")))
        val upload = original.copy(id = "upload", title = "Heroinwater - Божественный план (без запикиваний)",
            artists = listOf(ArtistRef("Heroinwater - Topic")), isVideo = true)
        assertEquals(upload, UncensoredRules.best(original, listOf(upload)))
    }

    @Test fun soundCloudRequiresManualVerification() {
        val alternative = source.copy(id = "sc:artist:track", explicit = true)
        assertEquals(listOf(alternative), UncensoredRules.review(source, listOf(alternative)))
        assertNull(UncensoredRules.best(source, listOf(alternative)))
    }
}
