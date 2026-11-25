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
}
