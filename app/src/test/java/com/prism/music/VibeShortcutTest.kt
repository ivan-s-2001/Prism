package com.prism.music

import com.prism.music.data.foryou.MyWave
import com.prism.music.ui.screens.personalVibe
import org.junit.Assert.assertEquals
import org.junit.Test

class VibeShortcutTest {
    @Test fun legacyContextRadioRestoresAsVibe() {
        val origin = com.prism.music.playback.VibeOrigin(com.prism.music.playback.VibeOriginKind.ALBUM, "Album", artist = "Artist")
        val legacy = com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.RADIO, "Old radio", seedId = "seed", vibeOrigin = origin)
        val migrated = legacy.normalizedVibe()
        assertEquals(com.prism.music.playback.QueueKind.VIBE, migrated.kind)
        assertEquals("Album", migrated.title)
        assertEquals(origin, migrated.vibeOrigin)
        assertEquals(true, migrated.isContextVibe)
        assertEquals(false, migrated.allowsOrderModes)
    }

    @Test fun oldUnseededRadioIsNotAContinuousSession() {
        val source = com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.RADIO, "Legacy")
        assertEquals(com.prism.music.playback.QueueKind.OTHER, source.normalizedVibe().kind)
    }

    @Test fun newTrackVibeHasStableIdentity() {
        val origin = com.prism.music.playback.VibeOrigin(com.prism.music.playback.VibeOriginKind.TRACK, "Track")
        val source = com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.VIBE, "Track", seedId = "seed", vibeOrigin = origin)
        assertEquals(true, source.matchesTrackStream("seed", "Track"))
        assertEquals(false, source.matchesTrackStream("other", "Track"))
        assertEquals(true, source.continuousRecommendations)
    }

    @Test fun artistLineOnlyDescribesTrackOrAlbum() {
        val kind = com.prism.music.playback.VibeOriginKind.TRACK
        val origin = com.prism.music.playback.VibeOrigin(kind, "Title", artist = "Artist")
        assertEquals("Artist", com.prism.music.ui.screens.vibeOriginArtist(origin))
        assertEquals("Artist", com.prism.music.ui.screens.vibeOriginArtist(origin.copy(kind = com.prism.music.playback.VibeOriginKind.ALBUM)))
        assertEquals(null, com.prism.music.ui.screens.vibeOriginArtist(origin.copy(kind = com.prism.music.playback.VibeOriginKind.PLAYLIST)))
        assertEquals(null, com.prism.music.ui.screens.vibeOriginArtist(origin.copy(artist = "Title")))
        assertEquals(null, com.prism.music.ui.screens.vibeOriginArtist(origin.copy(artist = " ")))
    }

    @Test fun originalArtistIsNotReplacedByRecommendedArtist() {
        val origin = com.prism.music.playback.VibeOrigin(com.prism.music.playback.VibeOriginKind.TRACK, "Original", artist = "Original artist")
        val source = com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.RADIO, "Vibe · Original", seedId = "seed", vibeOrigin = origin)
        val next = com.prism.music.data.model.Song("next", "Next", artists = listOf(com.prism.music.data.model.ArtistRef("Another artist")))
        assertEquals(origin, com.prism.music.ui.screens.activeVibeOrigin(source, next))
    }

    @Test fun contextOriginSurvivesSeedTrackChange() {
        val source = com.prism.music.playback.QueueSource(
            com.prism.music.playback.QueueKind.RADIO, "Vibe · Original track", seedId = "seed")
        val origin = com.prism.music.ui.screens.activeVibeOrigin(source, null)
        assertEquals(com.prism.music.playback.VibeOriginKind.TRACK, origin?.kind)
        assertEquals("Original track", origin?.name)
    }

    @Test fun contextKindsHaveDistinctLabels() {
        val labels = com.prism.music.playback.VibeOriginKind.entries.map {
            com.prism.music.ui.screens.vibeOriginLabel(it)
        }
        assertEquals(6, labels.distinct().size)
    }

    @Test fun contextStreamKeepsItsOriginalTitle() {
        val source = com.prism.music.playback.QueueSource(
            com.prism.music.playback.QueueKind.RADIO, "Vibe · Original track", seedId = "seed")
        assertEquals(true, source.continuousRecommendations)
        assertEquals(false, source.isMyWave)
        assertEquals("Vibe · Original track",
            com.prism.music.ui.screens.activeVibeTitle(source, null, null))
    }

    @Test fun aRegularAlbumIsNotAnActiveVibe() {
        val source = com.prism.music.playback.QueueSource(
            com.prism.music.playback.QueueKind.ALBUM, "Album", seedId = "track")
        assertEquals(false, source.continuousRecommendations)
    }

    @Test fun personalRestoresDefaultMixButPreservesLanguage() {
        val custom = MyWave.Style(character = MyWave.Character.FAMILIAR, mood = MyWave.Mood.CALM,
            depth = MyWave.Depth.DEEP, activity = MyWave.Activity.SLEEP,
            diversity = 10, discoveryPercent = 15, russianPercent = 80)
        assertEquals(MyWave.Style(russianPercent = 80), personalVibe(custom))
    }
    @Test fun personalKeepsAutomaticLanguage() {
        assertEquals(MyWave.Style(), personalVibe(MyWave.Style()))
    }
}
