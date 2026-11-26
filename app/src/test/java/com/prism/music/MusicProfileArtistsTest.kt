package com.prism.music

import com.prism.music.data.FavoriteArtist
import com.prism.music.data.mergeProfileArtists
import com.prism.music.data.foryou.Excluded
import com.prism.music.data.model.ArtistItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicProfileArtistsTest {
    @Test fun mergesRemoteSubscriptionsAndLocalStarsWithoutDuplicates() {
        val local = FavoriteArtist("a", "Artist A", channelId = "channelA")
        val result = mergeProfileArtists(
            listOf(local),
            listOf(ArtistItem("a", "Artist A", "", null), ArtistItem("b", "Artist B", "", null)),
            emptyList(),
        )
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals("channelA", result.first().channelId)
    }

    @Test fun exclusionsApplyToBothSources() {
        val result = mergeProfileArtists(
            listOf(FavoriteArtist("a", "Artist A"), FavoriteArtist("c", "Artist C")),
            listOf(ArtistItem("b", "Artist B", "", null)),
            listOf(Excluded("a", "Old name"), Excluded("other", "ARTIST B")),
        )
        assertEquals(listOf("c"), result.map { it.id })
    }
}
