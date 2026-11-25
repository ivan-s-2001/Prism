package com.prism.music.connect.yandex

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.prism.music.connect.HttpServer
import com.prism.music.connect.HttpRequest
import com.prism.music.connect.HttpResponse
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A real live HLS presentation. Every HTTP response is a complete finite object;
 * the playlist is refreshed, not a single WAV with a fictitious future length.
 * Packed AAC segments carry the transport-stream timestamp required by HLS.
 */
internal class StationLiveHls(
    phone: Inet4Address,
    station: Inet4Address,
    private val source: (Int) -> ByteArray,
) : Closeable {
    companion object {
        const val RATE = 44100
        const val FRAME = 1024
        // ~743 ms packed AAC segments; TARGETDURATION rounds up to one second.
        // Keep >3 target durations in the sliding playlist, plus an unpublished
        // retention window for in-flight segment requests.
        const val FRAMES = 32
        private const val KEEP = 18
        private const val WINDOW = 5
        private const val TARGET_DURATION = 1
        private const val READY_SEGMENTS = 5
        private const val FRAME_BYTES = FRAME * 4
        private const val MIME = "audio/mp4a-latm"
        private val DURATION = FRAMES * FRAME.toDouble() / RATE

        /** MPEG-4 AAC-LC, 44.1 kHz, stereo, no CRC. */
        internal fun adts(size: Int): ByteArray {
            val n = size + 7
            require(n in 7..8191)
            return byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, (0x80 or (n shr 11)).toByte(),
                (n shr 3).toByte(), (((n and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte())
        }

        /** RFC 8216 packed audio ID3 PRIV; 33-bit MPEG timestamp, in a big-endian 8-byte value. */
        internal fun timestamp(pts90k: Long): ByteArray {
            val owner = "com.apple.streaming.transportStreamTimestamp".toByteArray(Charsets.US_ASCII)
            val data = owner + byteArrayOf(0) + java.nio.ByteBuffer.allocate(8).putLong(pts90k and 0x1ffffffffL).array()
            fun sync(n: Int) = byteArrayOf(((n shr 21) and 127).toByte(), ((n shr 14) and 127).toByte(),
                ((n shr 7) and 127).toByte(), (n and 127).toByte())
            val frame = "PRIV".toByteArray(Charsets.US_ASCII) + sync(data.size) + byteArrayOf(0, 0) + data
            return byteArrayOf(73, 68, 51, 4, 0, 0) + sync(frame.size) + frame
        }
    }
    private data class Segment(val seq: Long, val bytes: ByteArray)
    private val segments = ArrayDeque<Segment>()
    private val closed = AtomicBoolean()
    private val prefix = "/live/${UUID.randomUUID()}"
    private val requests = AtomicLong()
    private val delivered = AtomicLong()
    @Volatile private var lastDelivery = 0L
    @Volatile var failure: String? = null
        private set
    val ready: Boolean get() = synchronized(segments) { segments.size >= READY_SEGMENTS }
    val receiving: Boolean get() = delivered.get() > 0 && android.os.SystemClock.elapsedRealtime() - lastDelivery < 15000L
    private val http = HttpServer("prism-live-hls", 0, maxBody = 0, allow = { it == station }, handler = ::handle)
    val url: String
    private val worker: Thread

    init {
        check(http.start()) { "Live HTTP bind failed" }
        url = "http://${phone.hostAddress}:${http.port}$prefix/index.m3u8"
        worker = Thread(::encode, "Prism-Live-AAC").apply { isDaemon = true; start() }
        android.util.Log.i("PrismLive", "HLS server started port=${http.port} segmentMs=${(DURATION * 1000).toInt()} window=$WINDOW")
    }

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (closed.get() || req.method !in listOf("GET", "HEAD")) { res.text(404, "Not found"); return }
        requests.incrementAndGet()
        val headers = mapOf("Cache-Control" to "no-store")
        if (req.path == "$prefix/index.m3u8") {
            val current = synchronized(segments) { segments.toList().takeLast(WINDOW) }
            if (current.isEmpty()) { res.text(503, "Preparing"); return }
            val playlist = buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$TARGET_DURATION\n")
                append("#EXT-X-MEDIA-SEQUENCE:${current.first().seq}\n")
                for (seg in current) {
                    append("#EXTINF:${String.format(java.util.Locale.US, "%.6f", DURATION)},\n")
                    append("${seg.seq}.aac\n")
                }
            }
            android.util.Log.i("PrismLive", "HLS ${req.method} playlist first=${current.first().seq} last=${current.last().seq}")
            res.send(200, "application/vnd.apple.mpegurl", playlist.toByteArray(), headers)
            return
        }
        val seq = req.path.takeIf { it.startsWith("$prefix/") && it.endsWith(".aac") }
            ?.substringAfterLast('/')?.removeSuffix(".aac")?.toLongOrNull()
        val (seg, newest) = synchronized(segments) { segments.firstOrNull { it.seq == seq } to segments.lastOrNull()?.seq }
        if (seg == null) { android.util.Log.i("PrismLive", "HLS missing segment=$seq"); res.text(404, "Not found"); return }
        res.send(200, "audio/aac", seg.bytes, headers)
        if (req.method == "GET") {
            delivered.incrementAndGet()
            lastDelivery = android.os.SystemClock.elapsedRealtime()
            val lagMs = ((newest?.minus(seg.seq) ?: 0L) * DURATION * 1000).toLong()
            android.util.Log.i("PrismLive", "HLS GET segment=$seq bytes=${seg.bytes.size} behindLiveEdgeMs=$lagMs")
        }
    }

    private fun encode() {
        var codec: MediaCodec? = null
        try {
            val enc = MediaCodec.createEncoderByType(MIME)
            codec = enc
            val format = MediaFormat.createAudioFormat(MIME, RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
            }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val info = MediaCodec.BufferInfo()
            var submitted = 0L
            var frames = 0
            var seq = 0L
            var segment = ByteArrayOutputStream()
            val start = System.nanoTime()
            while (!closed.get()) {
                val input = enc.dequeueInputBuffer(5000)
                if (input >= 0) {
                    val pcm = source(FRAME_BYTES)
                    val pts = submitted * FRAME * 1_000_000L / RATE
                    val buffer = enc.getInputBuffer(input) ?: error("AAC input unavailable")
                    buffer.clear()
                    buffer.put(pcm)
                    enc.queueInputBuffer(input, 0, pcm.size, pts, 0)
                    submitted++
                }
                while (!closed.get()) {
                    val index = enc.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (index < 0) break
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val out = enc.getOutputBuffer(index) ?: error("AAC output unavailable")
                            val bytes = ByteArray(info.size)
                            out.position(info.offset); out.limit(info.offset + info.size); out.get(bytes)
                            if (frames == 0) {
                                segment = ByteArrayOutputStream()
                                segment.write(timestamp(info.presentationTimeUs.coerceAtLeast(0) * 90 / 1000))
                            }
                            segment.write(adts(bytes.size))
                            segment.write(bytes)
                            if (++frames == FRAMES) {
                                synchronized(segments) {
                                    segments.addLast(Segment(seq++, segment.toByteArray()))
                                    while (segments.size > KEEP) segments.removeFirst()
                                }
                                frames = 0
                            }
                        }
                    } finally { enc.releaseOutputBuffer(index, false) }
                }
                val due = start + submitted * FRAME * 1_000_000_000L / RATE
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            }
        } catch (e: InterruptedException) {
            // closed
        } catch (e: Exception) {
            if (!closed.get()) {
                failure = e.javaClass.simpleName
                android.util.Log.w("PrismLive", "AAC encoder failed", e)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    fun summary() = "HLS requests=${requests.get()} segmentsDelivered=${delivered.get()} ready=$ready encoder=${failure ?: "ok"}"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        http.stop()
        worker.interrupt()
        synchronized(segments) { segments.clear() }
    }
}
