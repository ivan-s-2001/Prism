package com.prism.music.connect.yandex

import com.prism.music.connect.HttpServer
import com.prism.music.connect.HttpRequest
import com.prism.music.connect.HttpResponse
import com.prism.music.connect.Wav
import com.prism.music.connect.ByteRange
import java.io.Closeable
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Sequential live WAV, with a bounded, RIFF-compatible resource size.
 * Honor Station's HTTP range requests. No random access to audio that has not
 * been captured; a new session is required to seek outside this live resource.
 */
internal class StationLivePcm(phone: Inet4Address, station: Inet4Address,
    private val source: (Int) -> ByteArray) : Closeable {
    companion object {
        private const val RATE = 44100
        private const val FRAME_BYTES = 4
        private const val CHUNK = RATE * FRAME_BYTES / 50
        // RIFF's 32-bit sizes must match the advertised HTTP entity.
        internal const val DATA_LENGTH = 3600L * RATE * FRAME_BYTES
        internal const val LENGTH = DATA_LENGTH + 44
        internal fun header(): ByteArray = Wav.header(RATE, 2, DATA_LENGTH)
    }
    private val closed = AtomicBoolean()
    private val consumer = AtomicReference<HttpResponse?>(null)
    private val requests = AtomicLong()
    private val delivered = AtomicLong()
    private val replay = LivePcmReplay(RATE * FRAME_BYTES * 12)
    @Volatile private var lastWrite = 0L
    private val path = "/live/${UUID.randomUUID()}/stream.wav"
    private val http = HttpServer("prism-live-pcm", 0, maxBody = 0,
        allow = { it == station }, handler = ::handle)
    val url: String
    init {
        check(http.start()) { "PCM HTTP bind failed" }
        url = "http://${phone.hostAddress}:${http.port}$path"
        android.util.Log.i("PrismLive", "Direct PCM listening port=${http.port}")
    }

    val receiving: Boolean get() = !closed.get() && delivered.get() >= RATE * FRAME_BYTES / 2 &&
        android.os.SystemClock.elapsedRealtime() - lastWrite < 5000L

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (closed.get() || req.path != path || req.method !in listOf("GET", "HEAD")) {
            res.text(404, "Not found"); return
        }
        requests.incrementAndGet()
        val headers = mapOf("Content-Type" to "audio/wav", "Cache-Control" to "no-store",
            "Accept-Ranges" to "bytes")
        android.util.Log.i("PrismLive", "Direct PCM ${req.method} range=${req.headers["range"] ?: "none"}")
        val range = ByteRange.parse(req.header("range"), LENGTH)
        if (range == ByteRange.Unsatisfiable) {
            res.text(416, "Invalid range", mapOf("Content-Range" to "bytes */$LENGTH"))
            return
        }
        val first = if (range is ByteRange.Part) range.start else 0L
        val last = if (range is ByteRange.Part) range.end else LENGTH - 1
        val length = last - first + 1
        val responseHeaders = if (range is ByteRange.Part)
            headers + ("Content-Range" to "bytes $first-$last/$LENGTH") else headers
        val status = if (range is ByteRange.Part) 206 else 200
        if (req.method == "HEAD") { res.start(status, responseHeaders, length); return }
        val wav = header()
        // Header-only probes never consume the live audio.
        if (last < 44) {
            res.send(status, "audio/wav", wav.copyOfRange(first.toInt(), (last + 1).toInt()), responseHeaders)
            return
        }
        if (!consumer.compareAndSet(null, res)) { res.text(503, "Already streaming"); return }
        try {
            if (closed.get()) return
            val pcmStart = maxOf(first, 44L)
            if (!replay.contains(pcmStart - 44)) {
                res.text(416, "Live range unavailable", mapOf("Content-Range" to "bytes */$LENGTH"))
                android.util.Log.w("PrismLive", "PCM range outside replay window $first-$last available=${44 + replay.start}..${44 + replay.end}")
                return
            }
            val out = res.start(status, responseHeaders, length)
            android.util.Log.i("PrismLive", "PCM HTTP $status bytes=$first-$last/$LENGTH")
            var pos = first
            if (pos < wav.size) {
                val n = (wav.size - pos).toInt()
                out.write(wav, pos.toInt(), n)
                pos += n
            }
            out.flush()
            var deadline = System.nanoTime()
            var lastLog = deadline
            var nonZero = 0L
            while (!closed.get() && pos <= last) {
                val n = minOf(CHUNK.toLong(), last - pos + 1).toInt()
                val offset = pos - 44
                val needed = offset + n - replay.end
                if (needed > 0) {
                    // HTTP may split a stereo frame; the source never does.
                    val aligned = ((needed + FRAME_BYTES - 1) / FRAME_BYTES * FRAME_BYTES).toInt()
                    val fresh = source(aligned)
                    check(fresh.size == aligned) { "Unexpected PCM chunk size" }
                    // Retain before writing: even a failed socket write can be retried byte-exactly.
                    replay.append(fresh)
                }
                val pcm = replay.read(offset, n)
                nonZero += pcm.count { it != 0.toByte() }
                out.write(pcm)
                out.flush()
                pos += n
                delivered.addAndGet(n.toLong())
                lastWrite = android.os.SystemClock.elapsedRealtime()
                val now = System.nanoTime()
                if (now - lastLog >= 1_000_000_000L) {
                    android.util.Log.i("PrismLive", "PCM wire bytes=${delivered.get()} nonZeroBytes=$nonZero; ${StationAudioTap.formatSummary()}")
                    nonZero = 0
                    lastLog = now
                }
                deadline += n.toLong() * 1_000_000_000L / (RATE * FRAME_BYTES)
                if (now - deadline > 40_000_000L) deadline = now
                val wait = deadline - now
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            }
        } catch (e: java.io.IOException) {
            android.util.Log.w("PrismLive", "PCM HTTP ended: ${e.javaClass.simpleName}; bytes=${delivered.get()}")
        } finally {
            consumer.compareAndSet(res, null)
            android.util.Log.i("PrismLive", "Direct PCM request closed; bytes=${delivered.get()} next=${44 + replay.end}")
        }
    }

    fun summary() = "PCM requests=${requests.get()} bytes=${delivered.get()} receiving=$receiving"
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        consumer.get()?.abort()
        http.stop()
    }
}
