package com.prism.music.playback

/** Bounded startup reserve and retry policy, shared by all Vibe sessions. */
internal object VibeQueuePolicy {
    const val REFILL_AT = 5
    const val MAX_RETRIES = 3

    fun retryDelay(attempt: Int): Long? = when (attempt) {
        0 -> 1500L
        1 -> 4000L
        2 -> 10000L
        else -> null
    }

    fun <T> initial(anchors: List<T>, heard: (T) -> Boolean): List<T> {
        val fresh = anchors.filterNot(heard)
        return fresh.ifEmpty { anchors.take(1) }.take(4)
    }
}
