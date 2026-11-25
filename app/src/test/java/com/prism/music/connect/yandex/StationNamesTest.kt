package com.prism.music.connect.yandex

import org.junit.Assert.assertEquals
import org.junit.Test

class StationNamesTest {
    @Test fun knownPlatforms() {
        val models = mapOf(
            "yandexstation" to "Яндекс Станция 1",
            "cucumber" to "Яндекс Станция Миди",
            "yandexmidi" to "Яндекс Станция 2",
            "yandexmicro" to "Яндекс Станция Лайт",
            "yandexmini_2" to "Яндекс Станция Мини 2",
            "yandexstation_2" to "Яндекс Станция Макс",
        )
        models.forEach { (platform, name) -> assertEquals(name, StationNames.model(platform)) }
    }
    @Test fun unknownPlatformDoesNotExposeInternalId() {
        assertEquals("Яндекс Станция", StationNames.model("unknown"))
    }
    @Test fun technicalNamesAreNotShown() {
        assertEquals("Яндекс Станция Миди", StationNames.display("cucumber", "Yandex IO Receiver-123"))
    }
    @Test fun accountLabelNeverDuplicatesCanonicalName() {
        assertEquals("Яндекс Станция Лайт", StationNames.display("yandexmicro", "Кухня"))
        assertEquals("Яндекс Станция Лайт", StationNames.display("yandexmicro", "Яндекс Light"))
    }
}
