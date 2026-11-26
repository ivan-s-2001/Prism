package com.prism.music

import com.prism.music.data.censorship.CensorshipIndex
import org.junit.Assert.*
import org.junit.Test

class CensorshipIndexTest {
    @Test fun onlyAudioReplacementsAreReports() {
        val raw = """{"sources":[{"tracks":{"1":{"title":"metadata only"},"2":{"__fckCensor":{"audioSourceId":"99"}}},"tracksStorages":[{"trackIds":[3,{"id":4,"durationMs":10000},"bad"],"tracks":{"5":42,"bad":"ignore"}}]}]}"""
        assertEquals(setOf("2","3","4","5"), CensorshipIndex.fckIds(raw))
    }
    @Test fun libertyReadsIdsNotAudioUrls() {
        assertEquals(setOf("123"), CensorshipIndex.libertyIds("""{"tracks":{"123":"https://example.invalid/file","bad":"ignore"}}"""))
    }
    @Test fun onlyYandexNamespaceMatches() {
        assertEquals("123", CensorshipIndex.yandexId("yandex:123"))
        assertNull(CensorshipIndex.yandexId("123"))
        assertNull(CensorshipIndex.yandexId("spotify:123"))
        assertNull(CensorshipIndex.yandexId("yandex:123:4"))
    }
}
