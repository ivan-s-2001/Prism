package com.prism.music

import com.prism.music.data.stream.CatalogService
import com.prism.music.data.stream.ExternalCatalogDiscovery
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.ExplicitContent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExternalCatalogDiscoveryTest {
    @Test fun exactAppleReleaseAndSafeLink() {
        val obj = buildJsonObject {
            put("kind", "song"); put("trackId", 123); put("trackName", "Hokage")
            put("artistName", "Big Baby Tape"); put("trackTimeMillis", 175000)
            put("trackExplicitness", "explicit"); put("trackViewUrl", "https://music.apple.com/us/song/123")
            put("previewUrl", "https://example.com/preview.mp3")
        }
        val song = Song("yt", "Hokage", listOf(ArtistRef("Big Baby Tape")), durationSec = 175)
        val match = ExternalCatalogDiscovery.appleTrack(obj, "US")!!
        assertEquals("https://music.apple.com/us/song/123", match.url)
        assertTrue(ExternalCatalogDiscovery.compatible(song, match, ExplicitContent.SHOW))
        assertFalse(ExternalCatalogDiscovery.compatible(song.copy(artists = listOf(ArtistRef("Other"))), match, ExplicitContent.SHOW))
        assertFalse(CatalogService.APPLE.accepts("https://music.apple.com.evil.example/song/123"))
    }
}
