package com.prism.music.data

import android.content.SharedPreferences
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Conservative, metadata-only alternative recording finder. Explicit is evidence, not proof that
 * an audio recording is uncensored. Never changes track identity or overrides a listener's choice.
 */
object UncensoredRules {
    private val uncensored = Regex("""(?iu)\b(?:uncensored|uncut|без цензуры|нецензурированная версия)\b""")
    private val clean = Regex("""(?iu)\b(?:clean(?: version| edit)?|censored|radio edit|без мата|цензурированная версия)\b""")
    private val edit = Regex("""(?iu)\b(?:remix|cover|karaoke|instrumental|live|sped up|slowed|nightcore|acoustic|8d|ремикс|кавер|караоке|инструментал)\b""")
    private fun normalized(value: String): String = value.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")
    private fun base(title: String): String =
        SongVersions.bare(title.replace(uncensored, "").replace(clean, "").replace(Regex("""(?iu)\bexplicit(?: version)?\b"""), ""))

    fun eligible(song: Song): Boolean =
        !song.isLocal && !song.isUpload && !song.isEpisode && !song.isLive &&
            song.durationSec > 0 && song.primaryArtist.isNotBlank() && !uncensored.containsMatchIn(song.title)

    private fun sameArtist(a: Song, b: Song): Boolean {
        val x = a.artists.firstOrNull() ?: return false
        val y = b.artists.firstOrNull() ?: return false
        return (x.id != null && x.id == y.id) ||
            (normalized(x.name).isNotEmpty() && normalized(x.name) == normalized(y.name))
    }

    fun best(source: Song, candidates: List<Song>): Song? {
        if (!eligible(source)) return null
        val title = base(source.title)
        if (title.isBlank()) return null
        val sourceEdits = edit.findAll(source.title).map { it.value.lowercase() }.toSet()
        return candidates.asSequence()
            .filter { it.id != source.id && !it.isLocal && !it.isUpload && !it.isLive && !it.isEpisode }
            .filter { base(it.title) == title && sameArtist(source, it) }
            .filter { it.durationSec > 0 && abs(it.durationSec - source.durationSec) <= 5 }
            .filter { !clean.containsMatchIn(it.title) && edit.findAll(it.title).all { m -> m.value.lowercase() in sourceEdits } }
            .filter {
                // An already-Explicit recording needs stronger evidence than another E badge.
                uncensored.containsMatchIn(it.title) || (!source.explicit && it.explicit && !it.isVideo)
            }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Song> { uncensored.containsMatchIn(it.title) }
                .thenByDescending { source.album?.title != null && source.album.title.equals(it.album?.title, ignoreCase = true) }
                .thenBy { abs(it.durationSec - source.durationSec) }
                .thenBy { it.isVideo })
            .firstOrNull()
    }
}

class UncensoredVersions(
    private val prefs: SharedPreferences,
    private val search: suspend (String, SearchFilter) -> List<Song>,
) {
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled
    private val checked = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        _enabled.value = value
    }

    fun manualChoice(id: String) {
        prefs.edit().putBoolean("manual:$id", true).apply()
    }

    fun wasManual(id: String): Boolean = prefs.getBoolean("manual:$id", false)

    suspend fun find(song: Song): Song? {
        if (!enabled.value || wasManual(song.id) || !UncensoredRules.eligible(song)) return null
        val now = System.currentTimeMillis()
        if (checked[song.id]?.let { now - it in 0 until 86_400_000L } == true) return null
        if (!inFlight.add(song.id)) return null
        try {
            return withTimeoutOrNull(4_000) {
                withContext(Dispatchers.IO) {
                    val query = "${song.title} ${song.primaryArtist}".trim()
                    val songs = search(query, SearchFilter.SONGS)
                    val videos = search(query, SearchFilter.VIDEOS)
                    val candidate = UncensoredRules.best(song, songs + videos)
                        ?: UncensoredRules.best(song, search("$query uncensored", SearchFilter.VIDEOS))
                    if (songs.isNotEmpty() || videos.isNotEmpty() || candidate != null) {
                        if (checked.size >= 2048) checked.clear()
                        checked[song.id] = System.currentTimeMillis()
                    }
                    candidate
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            inFlight.remove(song.id)
        }
    }
}
