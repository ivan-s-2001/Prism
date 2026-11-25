package com.prism.music.connect.yandex

/** A fast poll rate must not shorten the receiver's recovery window. */
internal class StationLinkHealth(private val timeoutMs: Long = 30_000L) {
    private var since: Long? = null

    fun recovered() { since = null }

    fun failed(nowMs: Long, blocked: Boolean): Boolean {
        if (blocked) {
            recovered()
            return false
        }
        val start = since ?: nowMs.also { since = it }
        return nowMs - start >= timeoutMs
    }
}
