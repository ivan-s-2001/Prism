package com.prism.music.connect.yandex

/** One writer per room. Bytes already generated are immutable until evicted.
 * Retrying a HTTP range must never advance the audio reader twice.
 * Offsets are relative to the PCM data (not the WAV header).
 */
internal class LivePcmReplay(capacity: Int) {
    private val ring = ByteArray(capacity.also { require(it > 0) })
    var end: Long = 0
        private set
    val start: Long get() = (end - ring.size).coerceAtLeast(0)
    fun contains(offset: Long): Boolean = offset in start..end

    fun append(bytes: ByteArray) {
        require(bytes.size <= ring.size)
        val index = (end % ring.size).toInt()
        val first = minOf(bytes.size, ring.size - index)
        bytes.copyInto(ring, index, 0, first)
        if (first < bytes.size) bytes.copyInto(ring, 0, first, bytes.size)
        end += bytes.size
    }

    fun read(offset: Long, length: Int): ByteArray {
        require(contains(offset) && length >= 0 && length.toLong() <= end - offset)
        val out = ByteArray(length)
        val index = (offset % ring.size).toInt()
        val first = minOf(length, ring.size - index)
        ring.copyInto(out, 0, index, index + first)
        if (first < length) ring.copyInto(out, first, 0, length - first)
        return out
    }
}
