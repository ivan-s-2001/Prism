package com.prism.music

import com.prism.music.data.stream.BandcampVersion
import com.prism.music.data.stream.PublicRecording
import org.junit.Assert.*
import org.junit.Test

class BandcampVersionTest {
    @Test fun publicTrackRoundTrip() {
        val url = "https://artist.bandcamp.com/track/song"
        assertEquals("bc:artist:song", BandcampVersion.id(url))
        assertEquals(url, PublicRecording.url("bc:artist:song"))
        assertNull(BandcampVersion.id("https://artist.bandcamp.com/album/song"))
        assertNull(BandcampVersion.id("https://artist.bandcamp.com.evil.example/track/song"))
        assertNull(BandcampVersion.id(url + "?token=private"))
    }
}
