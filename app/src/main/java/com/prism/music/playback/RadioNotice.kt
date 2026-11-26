package com.prism.music.playback

/**
 * "Vibe started" (Reddit, 1.8.8: the Vibe buttons gave only a small vibration). A Vibe button asks for the notice
 * ([asked]) with the radio's queue; it's said once that radio's first songs are in the queue ([take]), so a radio that
 * never gets going (no connection, the explicit filter, another queue started meanwhile) says nothing new.
 */
class VibeNotice {
    private val waiting = java.util.concurrent.atomic.AtomicReference<QueueSource?>(null)

    fun asked(source: QueueSource) { waiting.set(source) }

    /** Consume the notice exactly once, without clearing a newer request. */
    fun take(source: QueueSource, added: Int): Boolean =
        added > 0 && waiting.compareAndSet(source, null)
}

@Deprecated("Use VibeNotice")
typealias RadioNotice = VibeNotice
