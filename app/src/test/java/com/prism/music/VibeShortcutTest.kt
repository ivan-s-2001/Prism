package com.prism.music

import com.prism.music.data.foryou.MyWave
import com.prism.music.ui.screens.personalVibe
import org.junit.Assert.assertEquals
import org.junit.Test

class VibeShortcutTest {
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
