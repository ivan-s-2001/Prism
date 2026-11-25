package com.prism.music.connect.yandex

import java.util.Locale

internal object StationNames {
    fun model(platform: String): String = when (platform.trim().lowercase(Locale.ROOT)) {
        "yandexstation" -> "Яндекс Станция 1"
        "yandexmidi" -> "Яндекс Станция 2"
        "orion" -> "Яндекс Станция 3"
        "cucumber" -> "Яндекс Станция Миди"
        "yandexmicro" -> "Яндекс Станция Лайт"
        "yandexplum", "plum" -> "Яндекс Станция Лайт 2"
        "yandexmini" -> "Яндекс Станция Мини 1"
        "yandexmini_2" -> "Яндекс Станция Мини 2"
        "bergamot" -> "Яндекс Станция Мини 3"
        "pickle" -> "Яндекс Станция Мини 3 Про"
        "yandexstation_2" -> "Яндекс Станция Макс"
        "mango" -> "Яндекс Станция Стрит"
        else -> "Яндекс Станция"
    }

    // Account labels and mDNS names must never produce a second model name.
    fun display(platform: String, accountName: String?): String = model(platform)
}
