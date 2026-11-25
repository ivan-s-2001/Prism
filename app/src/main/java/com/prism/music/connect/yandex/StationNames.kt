package com.prism.music.connect.yandex

import java.util.Locale

internal object StationNames {
    fun model(platform: String): String = when (platform.trim().lowercase(Locale.ROOT)) {
        "yandexstation" -> "Яндекс Станция 1"
        "yandexmidi" -> "Яндекс Станция 2"
        "orion" -> "Яндекс Станция 3"
        "cucumber" -> "Яндекс Станция Миди"
        "yandexmicro" -> "Яндекс Станция Lite"
        "yandexplum", "plum" -> "Яндекс Станция Lite 2"
        "yandexmini" -> "Яндекс Станция Мини"
        "yandexmini_2" -> "Яндекс Станция Мини 2"
        "bergamot" -> "Яндекс Станция Мини 3"
        "pickle" -> "Яндекс Станция Мини 3 Про"
        "yandexstation_2" -> "Яндекс Станция Макс"
        "mango" -> "Яндекс Станция Стрит"
        else -> "Яндекс Станция"
    }

    fun display(platform: String, accountName: String?): String {
        val name = accountName?.trim()?.takeIf {
            it.isNotBlank() && it.length <= 100 &&
                !it.contains("receiver", ignoreCase = true) &&
                !it.startsWith("yandexio", ignoreCase = true)
        }
        val model = model(platform)
        return if (name == null || name.equals(model, ignoreCase = true)) model
        else "$model · $name"
    }
}
