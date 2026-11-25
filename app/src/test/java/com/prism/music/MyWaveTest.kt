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

    @Test fun diversifiesArtistsWhenPossible() {
        val songs = listOf(song("a1", "A"), song("a2", "A"), song("b", "B"), song("c", "C"))
        val result = MyWave.start(songs, emptyList(), emptyList(), { false }, { true }, 13L, 4)
        assertEquals(4, result.size)
        assertFalse(result.zipWithNext().any { it.first.primaryArtist == it.second.primaryArtist })
    }
}
