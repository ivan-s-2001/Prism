package com.prism.music.connect.yandex

/** Monotonic media clock. A stalled encoder/producer must not try to replay
 * seconds of expired deadlines. Fractional nanoseconds carry across chunks. */
internal class LivePacer(private val rate: Int, startNanos: Long) {
    init { require(rate > 0) }
    private var due = startNanos
    private var remainder = 0L
    var late = 0L
        private set
    fun advance(frames: Int, now: Long): Long {
        require(frames > 0)
        val numerator = frames.toLong() * 1_000_000_000L + remainder
        val period = numerator / rate
        remainder = numerator % rate
        due += period
        if (now - due > period * 3) {
            late++
            due = now
            remainder = 0L
        }
        return (due - now).coerceAtLeast(0L)
    }
    fun waitAfter(frames: Int) {
        val wait = advance(frames, System.nanoTime())
        if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
    }
}
