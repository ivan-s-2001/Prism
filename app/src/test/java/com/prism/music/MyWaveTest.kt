package com.prism.music

import com.prism.music.data.foryou.MyWave
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class MyWaveTest {
    private fun song(id: String, artist: String, title: String = id) =
        Song(id, title, listOf(ArtistRef(artist)), durationSec = 180)

    @Test fun mixesDiscoveriesAndFavorites() {
        val picks = listOf(song("n1", "A"), song("n2", "B"), song("n3", "C"))
        val favorites = listOf(song("f1", "D"), song("f2", "E"))
        val result = MyWave.start(picks, favorites, emptyList(), { false }, { true }, 42L, 5)
        assertEquals(5, result.size)
        assertTrue(result.first() in picks)
        assertTrue(result[1] in favorites)
        assertEquals(5, result.map { it.id }.distinct().size)
    }

    @Test fun excludesBlockedSongsAndDuplicateUploads() {
        val first = song("a", "Artist", "Title")
        val duplicate = song("b", "Artist", "Title (Remastered)")
        val blocked = song("c", "Other")
        val good = song("d", "Another")
        val result = MyWave.start(listOf(first, blocked, good), listOf(duplicate), emptyList(),
            { false }, { it != blocked }, 1L, 12)
        assertEquals(2, result.size)
        assertFalse(blocked in result)
        assertTrue(good in result)
    }

    @Test fun favorsFreshSongs() {
        val old = song("old", "A")
        val fresh = song("fresh", "B")
        val result = MyWave.start(listOf(old, fresh), emptyList(), listOf(old),
            { it == old }, { true }, 7L, 1)
        assertEquals(listOf(fresh), result)
    }

    @Test fun selectedCharacterChangesFamiliarDiscoveryRatio() {
        val known = (1..12).map { song("f$it", "F$it") }
        val discoveries = (1..12).map { song("n$it", "N$it") }
        fun start(character: MyWave.Character) = MyWave.start(discoveries, known, emptyList(),
            { false }, { true }, 9L, 12, MyWave.Style(character)).count { it in known }
        assertTrue(start(MyWave.Character.FAMILIAR) > start(MyWave.Character.DISCOVERY))
    }

    @Test fun selectedMoodUsesMeasuredEnergyWithoutInventingMissingData() {
        val calm = song("calm", "A")
        val energetic = song("energy", "B")
        val signals = com.prism.music.data.foryou.RadioSignals(energy = { if (it == calm) 0.0 else 1.0 })
        fun first(mood: MyWave.Mood) = MyWave.start(listOf(calm, energetic), emptyList(), emptyList(),
            { false }, { true }, 12L, 1, MyWave.Style(mood = mood), signals).single()
        assertEquals(calm, first(MyWave.Mood.CALM))
        assertEquals(energetic, first(MyWave.Mood.ENERGETIC))
    }

    @Test fun waveStyleSurvivesQueueOriginRoundTrip() {
        val style = MyWave.Style(MyWave.Character.DISCOVERY, MyWave.Mood.ENERGETIC)
        assertTrue(MyWave.isOrigin(style.origin))
        assertEquals(style, MyWave.style(style.origin))
        assertEquals(MyWave.Style(), MyWave.style(MyWave.ORIGIN))
        assertFalse(MyWave.isOrigin("another-playlist"))
    }

    @Test fun agreementBetweenRankedSourcesPromotesTheSameRecording() {
        val first = song("first", "A")
        val consensus = song("consensus", "B", "Recording")
        val alternate = song("alternate", "B", "Recording (Remastered)")
        val second = song("second", "C")
        val fused = MyWave.fuse(listOf(listOf(first, consensus), listOf(second, alternate)))
        assertEquals(consensus, fused.first())
        assertEquals(3, fused.size)
    }

    @Test fun relevantContextAndTasteCanPromoteAnOtherwiseLowerRankedSong() {
        val generic = song("generic", "A")
        val fitting = song("fitting", "B")
        val profile = MyWave.Profile(taste = { if (it == fitting) 1.0 else 0.0 },
            context = { if (it == fitting) 1.0 else 0.0 })
        assertEquals(fitting, MyWave.start(listOf(generic, fitting), emptyList(), emptyList(),
            { false }, { true }, 7L, 1, profile = profile).single())
    }

    @Test fun avoidsPreviousRecordingAndArtistAcrossRefillBoundary() {
        val previous = song("previous", "A", "Recording")
        val duplicate = song("upload", "A", "Recording (Remastered)")
        val sameArtist = song("same", "A")
        val different = song("different", "B")
        val result = MyWave.start(listOf(duplicate, sameArtist, different), emptyList(), emptyList(),
            { false }, { true }, 3L, 2, previousSong = previous,
            avoid = setOf(com.prism.music.data.foryou.LocalRadio.sameSong(previous)))
        assertEquals(listOf(different, sameArtist), result)
    }

    @Test fun depthDoesNotInventPopularityAndOldOriginsStillLoad() {
        val hit = song("hit", "A")
        val deep = song("deep", "B")
        val signals = com.prism.music.data.foryou.RadioSignals(onTopList = { it == hit })
        val style = MyWave.Style(depth = MyWave.Depth.DEEP)
        assertEquals(deep, MyWave.start(listOf(hit, deep), emptyList(), emptyList(),
            { false }, { true }, 6L, 1, style, signals).single())
        assertEquals(style, MyWave.style(style.origin))
        assertEquals(MyWave.Depth.ANY, MyWave.style("prism:my-wave:FAMILIAR:CALM").depth)
    }

    @Test fun activityAndExplicitMoodUseOnlyKnownAudioEnergy() {
        val calm = song("calm", "A")
        val loud = song("loud", "B")
        val signals = com.prism.music.data.foryou.RadioSignals(energy = { if (it == calm) 0.0 else 1.0 })
        val workout = MyWave.Style(activity = MyWave.Activity.TRAIN, diversity = 80)
        assertEquals(loud, MyWave.start(listOf(calm, loud), emptyList(), emptyList(), { false }, { true },
            1L, 1, workout, signals).single())
        assertEquals(calm, MyWave.start(listOf(calm, loud), emptyList(), emptyList(), { false }, { true },
            1L, 1, workout.copy(mood = MyWave.Mood.CALM), signals).single())
        assertEquals(workout, MyWave.style(workout.origin))
        assertEquals(MyWave.Activity.ANY, MyWave.style("prism:my-wave:BALANCED:ANY:HITS").activity)
    }

    @Test fun implicitFeedbackIsShrunkAndExplorationIsBounded() {
        val unknown = MyWave.Evidence()
        val positive = MyWave.Evidence(accepted = 12.0, skipped = 1.0)
        val negative = MyWave.Evidence(accepted = 1.0, skipped = 12.0)
        assertEquals(1.0, unknown.weight(0.0), 1e-9)
        assertTrue(unknown.weight(1.0) > 1.0)
        assertTrue(unknown.weight(1.0) <= 1.12 + 1e-9)
        assertTrue(positive.weight(0.0) > 1.0)
        assertTrue(negative.weight(1.0) < 1.0)
        assertTrue(MyWave.Evidence(accepted = 1.0).weight(0.0) < positive.weight(0.0))
    }

    @Test fun varietyDoesNotOverrideExclusions() {
        val repeated = (1..8).map { song("a$it", "A") }
        val others = (1..6).map { song("b$it", "Artist $it") }
        val blocked = others.first()
        val result = MyWave.start(repeated + others, emptyList(), emptyList(), { false }, { it != blocked },
            4L, 6, MyWave.Style(diversity = 100))
        assertFalse(blocked in result)
        assertTrue(result.count { it.primaryArtist == "A" } <= 2)
    }

    @Test fun diversifiesArtistsWhenPossible() {
        val songs = listOf(song("a1", "A"), song("a2", "A"), song("b", "B"), song("c", "C"))
        val result = MyWave.start(songs, emptyList(), emptyList(), { false }, { true }, 13L, 4)
        assertEquals(4, result.size)
        assertFalse(result.zipWithNext().any { it.first.primaryArtist == it.second.primaryArtist })
    }
}
