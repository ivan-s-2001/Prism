package com.prism.music.connect.yandex

import kotlinx.serialization.json.*

/** Only explicit playback state and an echoed stream ID identify a Prism track. */
internal data class StationPlaybackState(
    val type: String?,
    val id: String?,
    val title: String?,
    val playing: Boolean?,
    val position: Long?,
    val duration: Long?,
    val volume: Int?,
    val at: Long,
) {
    companion object {
        fun parse(text: String, at: Long): StationPlaybackState? = runCatching {
            val root = Json.parseToJsonElement(text).jsonObject["state"] as? JsonObject
                ?: return@runCatching null
            val p = root["playerState"] as? JsonObject
            fun JsonObject?.field(k: String) = this?.get(k) as? JsonPrimitive
            fun seconds(v: JsonPrimitive?, allowZero: Boolean): Long? =
                v?.doubleOrNull?.takeIf { it.isFinite() && it <= 31_536_000 &&
                    (if (allowZero) it >= 0 else it > 0) }?.let { (it * 1000).toLong() }
            StationPlaybackState(
                p.field("playerType")?.contentOrNull,
                p.field("id")?.contentOrNull,
                p.field("title")?.contentOrNull,
                root.field("playing")?.takeUnless { it.isString }?.booleanOrNull,
                seconds(p.field("progress"), true),
                seconds(p.field("duration"), false),
                root.field("volume")?.doubleOrNull?.takeIf { it in 0.0..1.0 }
                    ?.let { kotlin.math.round(it * 100).toInt() },
                at,
            )
        }.getOrNull()
    }

    fun matches(url: String?): Boolean = url != null && id == url && type != "bluetooth"

    /** Only used with independent delivery evidence from the session's private HLS URL.
     * Modern external audio may explicitly report id="" instead of echoing the URL.
     * An absent id, unrelated track id, stale state or Bluetooth cannot confirm it.
     */
    fun confirmsLive(url: String, expectedTitle: String, requestedAt: Long, allowEmptyId: Boolean): Boolean =
        at >= requestedAt && playing == true && type != "bluetooth" &&
            (matches(url) || (id.isNullOrEmpty() && title == expectedTitle) || (allowEmptyId && id == ""))
}
