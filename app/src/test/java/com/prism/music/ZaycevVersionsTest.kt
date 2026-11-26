package com.prism.music

import com.prism.music.data.stream.ZaycevVersions
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ZaycevVersionsTest {
    @Test fun publicPageRoundTrip() {
        val url = "https://zaycev.net/pages/246111/24611123.shtml"
        assertEquals("zc:246111:24611123", ZaycevVersions.id(url))
        assertEquals(url, ZaycevVersions.url("zc:246111:24611123"))
        assertNull(ZaycevVersions.id("https://zaycev.net.evil.example/pages/246111/24611123.shtml"))
    }

    @Test fun onlyExplicitPublicAudio() {
        val page = """<h1>MATRANG - Медуза</h1><h2>Исполнитель: <a>MATRANG</a></h2><p>Длительность: 02:43</p>"""
        val id = "zc:246111:24611123"
        assertNull(ZaycevVersions.parse(Jsoup.parse(page), id))
        assertNull(ZaycevVersions.parse(Jsoup.parse(page + """<audio src="https://evil.example/a.mp3"></audio>"""), id))
        val track = ZaycevVersions.parse(Jsoup.parse(page + """<audio src="https://cdn.zaycev.net/a.mp3"></audio>"""), id)
        assertEquals(163, track?.song?.durationSec)
        assertEquals("MATRANG", track?.song?.primaryArtist)
    }
}
