package com.prism.music.connect.yandex

/** Only receiver-reported positions drive the UI. Never extrapolate from the phone's decoder. */
internal data class StationClock(
    val songId: String?,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val playing: Boolean? = null,
    val loading: Boolean = true,
) {
    fun withObservation(song: String, position: Long?, duration: Long?, isPlaying: Boolean?, waiting: Boolean): StationClock {
        val last = if (songId == song) positionMs else 0L
        val length = duration?.takeIf { it > 0 } ?: durationMs?.takeIf { songId == song }
        val reported = (position?.takeIf { it >= 0 } ?: last).let { if (length != null) it.coerceAtMost(length) else it }
        return StationClock(song, reported, length, isPlaying, waiting)
    }
}

internal class StationEndGate {
    private var lastPlayingAt: Long? = null
    private var lastPosition: Long? = null
    private var duration: Long? = null
    private var consumed = false

    fun reset() {
        lastPlayingAt = null
        lastPosition = null
        duration = null
        consumed = false
    }

    /** A stopped frame alone is not proof of natural completion. A foreign stream never advances the queue. */
    fun observe(state: StationPlaybackState, url: String, fallbackDuration: Long, allowAdvance: Boolean): Boolean {
        if (consumed) return false
        if (state.playing == true) {
            if (!state.matches(url)) {
                reset()
                return false
            }
            if (state.position != null) {
                lastPlayingAt = state.at
                lastPosition = state.position
                duration = state.duration?.takeIf { it > 0 } ?: fallbackDuration.takeIf { it > 0 }
            }
            return false
        }
        if (state.playing != false || !allowAdvance) {
            reset()
            return false
        }
        val previousAt = lastPlayingAt ?: return false
        val previousPosition = lastPosition ?: return false
        val length = duration ?: return false
        if (state.at <= previousAt || state.at - previousAt > 3500) return false
        if (state.id != null && !state.matches(url)) return false
        val reported = if (state.matches(url)) state.position ?: previousPosition else previousPosition
        val nearEnd = maxOf(previousPosition, reported) >= (length - 1200).coerceAtLeast(0) && length > 1200
        consumed = nearEnd
        return nearEnd
    }
}
