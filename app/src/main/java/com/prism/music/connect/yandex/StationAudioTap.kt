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
        internal var underrunBytes = 0L
        internal var skippedBytes = 0L
        internal var adjustedFrames = 0L
        internal var recovering = true
        internal var targetBytes = RATE * 4 * 350 / 1000
        internal var lastLeft = 0
        internal var lastRight = 0
        internal var fadingIn = true
        internal var recoveries = 0L
        fun read(length: Int): ByteArray = StationAudioTap.readFor(this, length)
        fun summary(): String = StationAudioTap.readerSummary(this)
        fun rejoinLiveEdge() = StationAudioTap.rejoin(this)
        override fun close() = StationAudioTap.removeReader(this)
    }

    @Synchronized fun openReader(): Reader {
        // First station retains existing startup behavior. Joining stations start near the live edge.
        val start = if (readers.isEmpty()) written - size else maxOf(written - size, written - RATE * 4 * 350 / 1000)
        return Reader(start).also { readers.add(it) }
    }

    @Synchronized private fun rejoin(reader: Reader) {
        if (reader !in readers) return
        val target = maxOf(written - size, written - reader.targetBytes)
        reader.skippedBytes += (target - reader.cursor).coerceAtLeast(0)
        reader.cursor = maxOf(reader.cursor, target)
        reader.recovering = true
        reader.fadingIn = true
    }

    @Synchronized private fun removeReader(reader: Reader) { readers.remove(reader) }
    @Synchronized private fun readerSummary(reader: Reader): String =
        "queuedMs=${(written - reader.cursor).coerceAtMost(size.toLong()) * 1000 / (RATE * 4)} underrunBytes=${reader.underrunBytes} skippedBytes=${reader.skippedBytes} adjustedFrames=${reader.adjustedFrames} recoveries=${reader.recoveries} targetMs=${reader.targetBytes * 1000 / (RATE * 4)}"

    // Only the HTTP/encoder worker waits, never the audio thread. Short decoder
    // scheduling gaps should not immediately turn into zero-filled PCM packets.
    private fun readFor(reader: Reader, length: Int): ByteArray {
        require(length >= 0 && length % 4 == 0)
        val deadline = System.nanoTime() + 45_000_000L
        while (true) {
            val result = synchronized(this) {
                if (reader !in readers || !active || !running || reader.recovering ||
                    written - maxOf(reader.cursor, written - size) >= length ||
                    System.nanoTime() >= deadline) readForLocked(reader, length) else null
            }
            if (result != null) return result
            Thread.sleep(4)
        }
    }

    private fun readForLocked(reader: Reader, length: Int): ByteArray {
        require(length >= 0 && length % 4 == 0)
        if (reader !in readers || !active || !running) return smooth(reader, ByteArray(length), false)
        val oldest = written - size
        // A stalled room must not resume two seconds behind the other rooms.
        // Ordinary decoder bursts fit in the reservoir; only recover when the
        // ring has actually overwritten that receiver's unread samples.
        if (reader.cursor < oldest) {
            val resume = maxOf(oldest, written - maxOf(reader.targetBytes, length))
            reader.skippedBytes += resume - reader.cursor
            reader.cursor = resume
            reader.recoveries++
            reader.recovering = true
            reader.fadingIn = true
        }
        val available = written - reader.cursor
        if (reader.recovering) {
            if (available < maxOf(reader.targetBytes.toLong(), length.toLong())) {
                reader.underrunBytes += length
                padded += length
                return smooth(reader, ByteArray(length), false)
            }
            reader.recovering = false
            reader.fadingIn = true
        }
        if (available < length) {
            // A short decoder scheduling gap should not trigger a 350–650 ms
            // rebuffer. Conceal at most 5 ms by stretching the remaining PCM;
            // longer gaps still take the normal bounded recovery path.
            val missing = length - available
            if (length == RATE * 4 / 50 && missing <= RATE * 4 / 200 &&
                available >= 8 && available % 4L == 0L) {
                val input = readAt(available.toInt(), reader.cursor) { reader.cursor = it }
                reader.underrunBytes += missing
                reader.adjustedFrames -= missing / 4
                return smooth(reader, interpolate(input, length), true)
            }
            reader.recoveries++
            reader.targetBytes = (reader.targetBytes + RATE * 4 / 10).coerceAtMost(RATE * 4 * 650 / 1000)
            reader.recovering = true
            reader.underrunBytes += length
            padded += length
            return smooth(reader, ByteArray(length), false)
        }
        // Correct only persistent clock drift with at most one stereo frame per
        // 20 ms. A tiny linear interpolation avoids a discontinuous sample drop.
        val adjust = if (length == RATE * 4 / 50 && available >= length + 4) {
            when {
                available < reader.targetBytes -> -1
                available > reader.targetBytes + RATE * 4 / 4 -> 1
                else -> 0
            }
        } else 0
        if (adjust == 0) {
            reader.underrunBytes += (length - available).coerceAtLeast(0L)
            return smooth(reader, readAt(length, reader.cursor) { reader.cursor = it }, true)
        }
        val input = readAt(length + adjust * 4, reader.cursor) { reader.cursor = it }
        reader.adjustedFrames += adjust.toLong()
        return smooth(reader, interpolate(input, length), true)
    }

    private fun interpolate(input: ByteArray, length: Int): ByteArray {
        val inFrames = input.size / 4
        val outFrames = length / 4
        val out = ByteArray(length)
        fun sample(frame: Int, ch: Int): Int {
            val index = frame * 4 + ch * 2
            return (input[index].toInt() and 255) or (input[index + 1].toInt() shl 8)
        }
        for (frame in 0 until outFrames) {
            val at = frame.toDouble() * (inFrames - 1) / (outFrames - 1)
            val a = at.toInt()
            val b = minOf(a + 1, inFrames - 1)
            val fraction = at - a
            for (ch in 0..1) {
                val value = (sample(a, ch) * (1 - fraction) + sample(b, ch) * fraction).toInt()
                val index = frame * 4 + ch * 2
                out[index] = value.toByte()
                out[index + 1] = (value shr 8).toByte()
            }
        }
        return out
    }

    // Fade only discontinuities (short ~5ms ramps). Ordinary PCM is untouched.
    private fun smooth(reader: Reader, out: ByteArray, music: Boolean): ByteArray {
        val frames = out.size / 4
        if (frames == 0) return out
        val ramp = minOf(frames, RATE / 200)
        fun sample(at: Int): Int = (out[at].toInt() and 255) or (out[at + 1].toInt() shl 8)
        fun put(at: Int, value: Int) {
            out[at] = value.toByte()
            out[at + 1] = (value shr 8).toByte()
        }
        if (!music || reader.fadingIn) {
            for (i in 0 until ramp) {
                val f = (i + 1).toDouble() / ramp
                val at = i * 4
                put(at, (reader.lastLeft * (1 - f) + sample(at) * f).toInt())
                put(at + 2, (reader.lastRight * (1 - f) + sample(at + 2) * f).toInt())
            }
        }
        reader.lastLeft = sample(out.size - 4)
        reader.lastRight = sample(out.size - 2)
        reader.fadingIn = !music
        return out
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
        readers.forEach { it.cursor = written; it.recovering = true; it.fadingIn = true }
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
