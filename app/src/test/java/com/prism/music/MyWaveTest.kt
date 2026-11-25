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

    @Test fun diversifiesArtistsWhenPossible() {
        val songs = listOf(song("a1", "A"), song("a2", "A"), song("b", "B"), song("c", "C"))
        val result = MyWave.start(songs, emptyList(), emptyList(), { false }, { true }, 13L, 4)
        assertEquals(4, result.size)
        assertFalse(result.zipWithNext().any { it.first.primaryArtist == it.second.primaryArtist })
    }
}
