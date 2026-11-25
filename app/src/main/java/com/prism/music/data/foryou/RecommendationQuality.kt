package com.prism.music.data.foryou

import com.prism.music.data.model.Song
import java.util.concurrent.ConcurrentHashMap

/** Automatic recommendations only. Explicit user selections and saved recordings are never replaced. */
internal object RecommendationQuality {
    private val alternate = Regex("""(?iu)(?:^|[^\p{L}\p{N}])(?:remix(?:ed)?|bootleg|mash[ -]?up|nightcore|sped[ -]?up|slowed(?:[ -]+down)?|reverb(?:ed)?|karaoke|instrumental version|cover version|fan edit|8d audio|tiktok version|live version|live at|ремикс|мэшап|кавер|замедленная версия|ускоренная версия)(?=$|[^\p{L}\p{N}])""")
    private val failures = ConcurrentHashMap<String, Long>()
    private const val RETRY_AFTER_MS = 15 * 60 * 1000L

    fun alternateRecording(song: Song): Boolean = alternate.containsMatchIn(song.title) ||
        song.album?.let { alternate.containsMatchIn(it.title) } == true

    fun failed(id: String) {
        val now = System.currentTimeMillis()
        if (failures.size >= 512) failures.entries.removeIf { now - it.value !in 0 until RETRY_AFTER_MS }
        if (failures.size >= 512) failures.keys.firstOrNull()?.let { failures.remove(it) }
        failures[id] = now
    }

    fun recentlyFailed(id: String): Boolean {
        val at = failures[id] ?: return false
        if (System.currentTimeMillis() - at in 0 until RETRY_AFTER_MS) return true
        failures.remove(id, at)
        return false
    }

    fun allowed(song: Song, liked: Boolean): Boolean =
        !song.isVideo && !song.isLive && !song.isEpisode && !recentlyFailed(song.id) &&
            (liked || song.isLocal || song.isUpload || !alternateRecording(song))
}
