package com.prism.music.data.foryou

import com.prism.music.data.model.Song

/** Stable, bounded sample of the original collection. Rotate local recall across it, never across generated tracks. */
internal object VibeContext {
    fun anchors(songs: List<Song>, limit: Int = 12): List<Song> {
        val pool = songs.filter { !it.isEpisode && !it.isLive && !it.isVideo }.distinctBy(LocalRadio::sameSong)
        if (limit <= 0) return emptyList()
        if (pool.size <= limit) return pool
        if (limit == 1) return pool.take(1)
        return List(limit) { i -> pool[(i.toLong() * (pool.size - 1) / (limit - 1)).toInt()] }
    }
    fun rotated(songs: List<Song>, offset: Int): List<Song> {
        if (songs.size <= 3) return songs
        val turn = (offset.coerceAtLeast(0) / MyWave.HORIZON) % songs.size
        return (songs.drop(turn) + songs.take(turn)).take(3)
    }

    fun sparseProfileFallback(style: MyWave.Style, noLikes: Boolean): Boolean =
        noLikes && style.discoveryPercent in 1..99

    fun alternativeSeeds(original: List<Song>, selected: List<Song>): List<Song> {
        val keys = original.mapTo(hashSetOf(), LocalRadio::sameSong)
        return selected.asReversed().filter { !it.isLocal && !it.isEpisode && !it.isLive && !it.isVideo &&
            LocalRadio.sameSong(it) !in keys }.distinctBy { it.primaryArtist.lowercase().ifBlank { it.id } }.take(3)
    }

    fun recycleCandidates(past: List<Song>, recent: List<Song>, upcoming: List<Song>, current: Song?): List<Song> {
        val protected = (upcoming + listOfNotNull(current)).mapTo(hashSetOf(), LocalRadio::sameSong)
        val eligible = (recent + past).distinctBy(LocalRadio::sameSong).filter {
            LocalRadio.sameSong(it) !in protected && !it.isLive && !it.isEpisode && !it.isVideo
        }
        return eligible.take((eligible.size + 1) / 2)
    }
}
