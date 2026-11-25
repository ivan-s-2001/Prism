package com.prism.music.data.foryou

import com.prism.music.data.model.Song
import kotlin.random.Random

/** A personal station, not a shuffled playlist. The existing radio engine replenishes it. */
internal object MyWave {
    const val ORIGIN = "prism:my-wave"

    /**
     * The personalized picks already include listening affinity and discoveries.
     * Mix in favorites, prefer songs not recently heard, and vary each new session.
     * Never relax exclusions, explicit-content restrictions, or offline availability.
     */
    fun start(
        recommendations: List<Song>,
        favorites: List<Song>,
        recent: List<Song>,
        heard: (Song) -> Boolean,
        allowed: (Song) -> Boolean,
        seed: Long,
        count: Int = 12,
    ): List<Song> {
        if (count <= 0) return emptyList()
        val recentKeys = recent.mapTo(hashSetOf(), LocalRadio::sameSong)
        val random = Random(seed)
        fun pool(songs: List<Song>) = songs.filter(allowed)
            .distinctBy(LocalRadio::sameSong)
            .map { it to random.nextDouble() }
            .sortedWith(compareBy<Pair<Song, Double>> { heard(it.first) || LocalRadio.sameSong(it.first) in recentKeys }.thenBy { it.second })
            .map { it.first }.toMutableList()
        val picks = pool(recommendations)
        val known = pool(favorites)
        val last = pool(recent)
        val seen = hashSetOf<String>()
        val out = mutableListOf<Song>()
        val artists = hashMapOf<String, Int>()
        var previous = ""
        fun take(from: MutableList<Song>, strict: Boolean): Song? {
            val i = from.indexOfFirst { s ->
                val artist = s.primaryArtist.lowercase()
                LocalRadio.sameSong(s) !in seen &&
                    (!strict || ((artist.isBlank() || artist != previous) && (artists[artist] ?: 0) < 3))
            }
            return if (i < 0) null else from.removeAt(i)
        }
        while (out.size < count) {
            val preferred = if (out.size % 3 == 1) listOf(known, picks, last) else listOf(picks, known, last)
            val s = preferred.firstNotNullOfOrNull { take(it, true) }
                ?: preferred.firstNotNullOfOrNull { take(it, false) } ?: break
            seen += LocalRadio.sameSong(s)
            previous = s.primaryArtist.lowercase()
            artists[previous] = (artists[previous] ?: 0) + 1
            out += s
        }
        return out
    }
}
