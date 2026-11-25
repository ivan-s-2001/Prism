package com.prism.music.connect.yandex

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio-thread tap; never performs I/O or waits on a receiver. Converts PCM to 44.1 kHz stereo 16-bit.
 * The buffer is bounded to avoid old queued audio accumulating during network backpressure.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object StationAudioTap : TeeAudioProcessor.AudioBufferSink {
    private const val RATE = 44100
    private val ring = ByteArray(RATE * 4 * 2)
    private var head = 0
    private var size = 0
    private var inputRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var phase = 0L
    @Volatile private var active = false
    private var running = false
    @Volatile var captured = 0L
        private set

    @Synchronized fun formatSummary(): String =
        "rate=$inputRate channels=$channels encoding=$encoding running=$running queuedBytes=$size"

    @Synchronized fun start(playing: Boolean) {
        clear()
        captured = 0
        running = playing
        active = true
    }

    @Synchronized fun stop() {
        active = false
        running = false
        clear()
    }

    @Synchronized fun playing(value: Boolean) {
        if (running != value) clear()
        running = value
    }

    @Synchronized fun clear() {
        head = 0
        size = 0
        phase = 0
    }

    @Synchronized override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        inputRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!active) return
        synchronized(this) {
            if (!active || !running || inputRate <= 0 || channels !in 1..8) return
            val sampleBytes = when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            fun sample(): Int = if (sampleBytes == 2) input.short.toInt() else
                (input.float.coerceIn(-1f, 1f) * 32767f).toInt()
            while (input.remaining() >= sampleBytes * channels) {
                val left = sample()
                val right = if (channels == 1) left else sample()
                repeat((channels - 2).coerceAtLeast(0)) { sample() }
                phase += RATE
                while (phase >= inputRate) {
                    phase -= inputRate
                    if (size == ring.size) { head = (head + 4) % ring.size; size -= 4 }
                    val end = (head + size) % ring.size
                    ring[end] = left.toByte()
                    ring[end + 1] = (left shr 8).toByte()
                    ring[end + 2] = right.toByte()
                    ring[end + 3] = (right shr 8).toByte()
                    size += 4
                    captured += 4
                }
            }
        }
    }

    /** Always produces the requested duration: a paused or starved player contributes silence. */
    @Synchronized fun read(length: Int): ByteArray {
        require(length >= 0 && length % 4 == 0)
        val out = ByteArray(length)
        if (!active || !running) return out
        val available = minOf(length, size)
        val first = minOf(available, ring.size - head)
        ring.copyInto(out, 0, head, head + first)
        if (available > first) ring.copyInto(out, first, 0, available - first)
        head = (head + available) % ring.size
        size -= available
        return out
    }
}
