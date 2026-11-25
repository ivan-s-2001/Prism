package com.prism.music.data

import android.content.SharedPreferences
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private fun normalized(value: String): String = value.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "").let {
        // Verified historical spelling of the same artist (2022 releases).
        if (it == "heroinwater") "heronwater" else it
    }
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

    // Uploaded videos often use "Artist - Track (без цензуры)" while uploader is a channel,
    // not the performer. Accept these in the review list; require additional evidence for auto picks.
    private fun performerNames(song: Song): Set<String> = song.artists.map { normalized(it.name) }
        .filter { it.isNotEmpty() }.toSet()

    private fun titleWithArtist(source: Song, candidate: Song): Boolean {
        val parts = candidate.title.split(Regex("""\s*[-–—|:]\s*"""), limit = 2)
        return parts.size == 2 && normalized(parts[0]) in performerNames(source) && base(parts[1]) == base(source.title)
    }

    private fun titleMatches(source: Song, candidate: Song): Boolean =
        base(candidate.title) == base(source.title) || titleWithArtist(source, candidate)

    fun markedOriginal(song: Song): Boolean = uncensored.containsMatchIn(song.title)
    private fun markedClean(song: Song): Boolean = clean.containsMatchIn(song.title)

    /** Broad review results. Unverified channel uploads must remain user-selectable, not silently substituted. */
    fun review(source: Song, candidates: List<Song>): List<Song> {
        val sourceEdits = edit.findAll(source.title).map { it.value.lowercase() }.toSet()
        return candidates.asSequence()
            .filter { it.id != source.id && !it.isLocal && !it.isUpload && !it.isLive && !it.isEpisode }
            .filter { titleMatches(source, it) }
            .filter { source.durationSec <= 0 || it.durationSec <= 0 || abs(it.durationSec - source.durationSec) <= 15 }
            .filter { !markedClean(it) && edit.findAll(it.title).all { m -> m.value.lowercase() in sourceEdits } }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Song> { markedOriginal(it) }
                .thenByDescending { sameArtist(source, it) }
                .thenByDescending { titleWithArtist(source, it) }
                .thenByDescending { it.explicit }
                .thenBy { if (it.durationSec > 0 && source.durationSec > 0) abs(it.durationSec - source.durationSec) else 1000 })
            .take(30).toList()
    }

    fun best(source: Song, candidates: List<Song>): Song? {
        if (!eligible(source)) return null
        return review(source, candidates).firstOrNull {
            // No automatic replacement by an unverified third-party upload or external streaming provider.
            !OriginalSources.isSoundCloud(it.id) && sameArtist(source, it) &&
                it.durationSec > 0 && abs(it.durationSec - source.durationSec) <= 5 &&
                (markedOriginal(it) || (!source.explicit && it.explicit && !it.isVideo))
        }
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
                    val hint = if (song.title.any { it in '\u0400'..'\u04ff' }) "без цензуры" else "uncensored"
                    val queries = buildList {
                        add(query to SearchFilter.SONGS)
                        add(query to SearchFilter.VIDEOS)
                        add("$query $hint" to SearchFilter.VIDEOS)
                        if (song.primaryArtist.equals("Heronwater", true)) {
                            add("${song.title} Heroinwater $hint" to SearchFilter.VIDEOS)
                        }
                    }
                    val results = kotlinx.coroutines.coroutineScope {
                        queries.map { (q, filter) ->
                            async {
                                try {
                                    search(q, filter)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                    }
                    val candidate = UncensoredRules.best(song, results)
                    if (results.isNotEmpty() && candidate == null) {
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
