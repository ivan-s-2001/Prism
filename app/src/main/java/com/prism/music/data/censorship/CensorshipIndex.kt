package com.prism.music.data.censorship

import kotlinx.serialization.json.*

/** Community reports identify Yandex recordings, not the sound of a YouTube result.
 * No replacement file URLs are read or executed. */
internal object CensorshipIndex {
    private val idPattern = Regex("[0-9]{1,24}")
    private fun JsonElement?.id(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { idPattern.matches(it) }
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray).orEmpty()

    fun yandexId(sourceId: String?): String? = sourceId?.takeIf { it.startsWith("yandex:") }
        ?.removePrefix("yandex:")?.takeIf { idPattern.matches(it) }

    /** FckCensor v2 distinguishes metadata edits from actual audio replacement entries. */
    fun fckIds(raw: String): Set<String> {
        val root = Json.parseToJsonElement(raw).obj() ?: error("Invalid index")
        val sources = root["sources"] as? JsonArray ?: error("Missing sources")
        return buildSet {
            for (source in sources) {
                val s = source.obj() ?: continue
                for (storage in s["tracksStorages"].arr()) {
                    val t = storage.obj() ?: continue
                    for (entry in t["trackIds"].arr()) {
                        (entry.id() ?: entry.obj()?.get("id").id())?.let { add(it) }
                    }
                    t["tracks"].obj()?.keys?.filter { idPattern.matches(it) }?.let { addAll(it) }
                }
                s["tracks"].obj()?.forEach { (key, value) ->
                    if (idPattern.matches(key) && value.obj()?.get("__fckCensor").obj()?.get("audioSourceId").id() != null) add(key)
                }
            }
        }
    }

    fun libertyIds(raw: String): Set<String> {
        val root = Json.parseToJsonElement(raw).obj() ?: error("Invalid index")
        val tracks = root["tracks"].obj() ?: error("Missing tracks")
        return tracks.keys.filter { idPattern.matches(it) }.toSet()
    }
}
