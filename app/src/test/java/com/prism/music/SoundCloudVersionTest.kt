package com.prism.music

import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.stream.SoundCloudVersion
import org.junit.Assert.*
import org.junit.Test

class SoundCloudVersionTest {
    private val song = Song("yt", "Ночной город", listOf(ArtistRef("Группа")), durationSec = 200)

    @Test fun namespacedPublicPermalinks() {
        val id = SoundCloudVersion.id("https://soundcloud.com/band/track")
        assertEquals("sc:band:track", id)
        assertEquals("https://soundcloud.com/band/track", SoundCloudVersion.url(id!!))
        assertNull(SoundCloudVersion.url("yt"))
        assertNull(SoundCloudVersion.id("https://soundcloud.com.evil.example/band/track"))
        assertNull(SoundCloudVersion.id("https://soundcloud.com/band/track?secret_token=abc"))
    }

    @Test fun hlsAndProgressiveIdsAreDistinctButPointToSameRecording() {
        val progressive = SoundCloudVersion.id("https://soundcloud.com/band/track")!!
        val hls = SoundCloudVersion.hlsId(progressive)!!
        assertNotEquals(progressive, hls)
        assertFalse(SoundCloudVersion.isHls(progressive))
        assertTrue(SoundCloudVersion.isHls(hls))
        assertEquals(SoundCloudVersion.url(progressive), SoundCloudVersion.url(hls))
        assertNull(SoundCloudVersion.hlsId("youtube"))
    }

    @Test fun candidatesRequireTitleArtistAndDuration() {
        assertTrue(SoundCloudVersion.matches(song, "Ночной город", "Группа", 201))
        assertTrue(SoundCloudVersion.matches(song, "Группа - Ночной город", "Label", 200))
        assertFalse(SoundCloudVersion.matches(song, "Ночной город (ремикс)", "Группа", 200))
        assertFalse(SoundCloudVersion.matches(song, "Ночной город (без мата)", "Группа", 200))
        assertFalse(SoundCloudVersion.matches(song, "Ночной город", "Группа", 30))
        assertFalse(SoundCloudVersion.matches(song, "Ночной город", "Другой", 200))
    }
}
