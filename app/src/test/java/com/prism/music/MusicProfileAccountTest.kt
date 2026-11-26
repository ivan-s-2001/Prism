package com.prism.music

import android.content.Context
import com.prism.music.data.ArtistPrefs
import com.prism.music.data.FavoriteArtist
import com.prism.music.data.foryou.Excluded
import com.prism.music.data.foryou.Exclusions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MusicProfileAccountTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test fun favoritesAreMigratedOnceAndRestoredPerAccount() {
        app.getSharedPreferences("artist_prefs", Context.MODE_PRIVATE).edit().clear()
            .putString("favorites", """[{"id":"old","name":"Legacy artist"}]""").commit()
        var owner: String? = "account-a"
        val prefs = ArtistPrefs(app, { owner })
        assertTrue(prefs.isFavorite("old"))
        owner = "account-b"
        prefs.refreshAccount()
        assertTrue(prefs.favorites.value.isEmpty())
        prefs.setFavorite(FavoriteArtist("new", "Another artist"), true)
        owner = "account-a"
        prefs.refreshAccount()
        assertEquals(listOf("old"), prefs.favorites.value.map { it.id })
        assertEquals(listOf("old"), ArtistPrefs(app, { owner }).favorites.value.map { it.id })
        owner = null
        prefs.refreshAccount()
        assertTrue(prefs.favorites.value.isEmpty())
    }

    @Test fun allExclusionTypesAreIsolatedAndPersisted() {
        app.getSharedPreferences("exclusions", Context.MODE_PRIVATE).edit().clear().commit()
        var owner: String? = "account-a"
        val exclusions = Exclusions(app, { owner })
        exclusions.setArtist(Excluded("artist", "Artist"), true)
        exclusions.setSong(Excluded("song", "Song"), true)
        exclusions.setPlaylist(Excluded("playlist", "Playlist"), true)
        owner = "account-b"
        exclusions.refreshAccount()
        assertFalse(exclusions.isArtistExcluded("artist", "Artist"))
        assertFalse(exclusions.isSongExcluded("song"))
        assertFalse(exclusions.isPlaylistExcluded("playlist"))
        owner = "account-a"
        exclusions.refreshAccount()
        assertTrue(exclusions.isArtistExcluded("artist", "Artist"))
        assertTrue(exclusions.isSongExcluded("song"))
        assertTrue(Exclusions(app, { owner }).isPlaylistExcluded("playlist"))
    }
}
