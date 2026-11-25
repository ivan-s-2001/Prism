package com.prism.music.connect.yandex

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
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
    private var written = 0L
    private var defaultCursor = 0L
    private val readers = mutableSetOf<Reader>()

    /** Each station sees the same PCM, with an independent non-destructive read cursor. */
    class Reader internal constructor(internal var cursor: Long) : java.io.Closeable {
        fun read(length: Int): ByteArray = StationAudioTap.readFor(this, length)
        override fun close() = StationAudioTap.removeReader(this)
    }

    @Synchronized fun openReader(): Reader {
        // First station retains existing startup behavior. Joining stations start near the live edge.
        val start = if (readers.isEmpty()) written - size else maxOf(written - size, written - RATE * 4 / 5)
        return Reader(start).also { readers.add(it) }
    }

    @Synchronized private fun removeReader(reader: Reader) { readers.remove(reader) }
    @Synchronized private fun readFor(reader: Reader, length: Int): ByteArray {
        if (reader !in readers) return ByteArray(length)
        return readAt(length, reader.cursor) { reader.cursor = it }
    }
    private var inputRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var phase = 0L
    @Volatile private var active = false
    @Volatile var muteOutput = false
    private var consumed = 0L
    private var padded = 0L
    private var dropped = 0L
    private var running = false
    @Volatile var captured = 0L
        private set

    @Synchronized fun formatSummary(): String =
        "rate=$inputRate channels=$channels encoding=$encoding running=$running muted=$muteOutput queuedBytes=$size captured=$captured consumed=$consumed padded=$padded dropped=$dropped"

    @Synchronized fun start(playing: Boolean) {
        clear()
        captured = 0
        consumed = 0
        padded = 0
        dropped = 0
        muteOutput = false
        running = playing
        active = true
    }

    @Synchronized fun stop() {
        muteOutput = false
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
        defaultCursor = written
        readers.forEach { it.cursor = written }
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
                    if (size == ring.size) { head = (head + 4) % ring.size; size -= 4; dropped += 4 }
                    val end = (head + size) % ring.size
                    ring[end] = left.toByte()
                    ring[end + 1] = (left shr 8).toByte()
                    ring[end + 2] = right.toByte()
                    ring[end + 3] = (right shr 8).toByte()
                    size += 4
                    captured += 4
                    written += 4
                }
            }
        }
    }

    /** Legacy/probe reader, independent of all station readers. */
    @Synchronized fun read(length: Int): ByteArray = readAt(length, defaultCursor) { defaultCursor = it }

    private fun readAt(length: Int, cursor: Long, advance: (Long) -> Unit): ByteArray {
        require(length >= 0 && length % 4 == 0)
        val out = ByteArray(length)
        if (!active || !running) return out
        val oldest = written - size
        val at = maxOf(cursor, oldest)
        val available = minOf(length.toLong(), written - at).toInt()
        val index = (head + (at - oldest).toInt()) % ring.size
        val first = minOf(available, ring.size - index)
        ring.copyInto(out, 0, index, index + first)
        if (available > first) ring.copyInto(out, first, 0, available - first)
        consumed += available
        padded += length - available
        advance(at + available)
        return out
    }
}

/** Mute only the local sink, AFTER copying its real PCM. Unlike setting the
 * player's volume to zero, this keeps the audio renderer/clock configured
 * normally throughout a Live session. No I/O on the playback thread.
 */
@UnstableApi
internal class StationLiveOutputProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
            inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush() {
        StationAudioTap.flush(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        StationAudioTap.handleBuffer(inputBuffer)
        val out = replaceOutputBuffer(bytes)
        if (StationAudioTap.muteOutput) {
            while (out.remaining() >= 8) out.putLong(0L)
            while (out.hasRemaining()) out.put(0.toByte())
            inputBuffer.position(inputBuffer.limit())
        } else out.put(inputBuffer)
        out.flip()
    }
}
