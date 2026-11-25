package com.prism.music.connect.yandex

import com.prism.music.connect.HttpServer
import com.prism.music.connect.HttpRequest
import com.prism.music.connect.HttpResponse
import com.prism.music.connect.Wav
import com.prism.music.connect.ByteRange
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The live clock produces PCM independently of TCP writes. HTTP readers only
 * copy immutable bytes, so a retry or a slow socket cannot change the audio. */
internal class StationLivePcm(phone: Inet4Address, station: Inet4Address,
    private val source: (Int) -> ByteArray) : Closeable {
    companion object {
        private const val RATE = 44100
        private const val FRAME_BYTES = 4
        private const val CHUNK = RATE * FRAME_BYTES / 50
        private const val STARTUP_BYTES = RATE * FRAME_BYTES * 600 / 1000
        private const val REPLAY_SECONDS = 30
        private const val MAX_READERS = 4
        private const val PROGRESS_TIMEOUT = 15_000L
        // RIFF sizes must match the advertised HTTP entity.
        internal const val DATA_LENGTH = 3600L * RATE * FRAME_BYTES
        internal const val LENGTH = DATA_LENGTH + 44
        internal fun header(): ByteArray = Wav.header(RATE, 2, DATA_LENGTH)
    }
    private val closed = AtomicBoolean()
    private val lock = java.lang.Object()
    private val consumers = mutableSetOf<HttpResponse>()
    private val requests = AtomicLong()
    private val delivered = AtomicLong()
    private val replay = LivePcmReplay(RATE * FRAME_BYTES * REPLAY_SECONDS)
    @Volatile private var producer: Thread? = null
    @Volatile private var producerError: String? = null
    @Volatile private var lastWrite = 0L
    @Volatile private var lastProduced = 0L
    private val path = "/live/${UUID.randomUUID()}/stream.wav"
    private val http = HttpServer("prism-live-pcm", 0, maxBody = 0,
        allow = { it == station }, handler = ::handle)
    val url: String
    init {
        check(http.start()) { "PCM HTTP bind failed" }
        url = "http://${phone.hostAddress}:${http.port}$path"
        android.util.Log.i("PrismLive", "Direct PCM listening port=${http.port}")
    }

    val receiving: Boolean get() = !closed.get() && producerError == null &&
        delivered.get() >= RATE * FRAME_BYTES / 2 &&
        android.os.SystemClock.elapsedRealtime() - lastWrite < PROGRESS_TIMEOUT

    private fun startProducerLocked() {
        if (producer != null) return
        producer = Thread(::produce, "Prism-Live-PCM").apply { isDaemon = true; start() }
    }

    private fun produce() {
        val start = System.nanoTime()
        val clock = LivePacer(RATE, start)
        var nextLog = start + 5_000_000_000L
        try {
            while (!closed.get()) {
                val fresh = source(CHUNK)
                check(fresh.size == CHUNK) { "Unexpected PCM chunk size" }
                synchronized(lock) {
                    if (closed.get()) return
                    if (replay.end >= DATA_LENGTH) return
                    replay.append(fresh)
                    lastProduced = android.os.SystemClock.elapsedRealtime()
                    lock.notifyAll()
                }
                clock.waitAfter(CHUNK / FRAME_BYTES)
                val now = System.nanoTime()
                if (now >= nextLog) {
                    android.util.Log.i("PrismLive", "PCM clock late=${clock.late}; ${summary()}; ${StationAudioTap.formatSummary()}")
                    nextLog = now + 5_000_000_000L
                }
            }
        } catch (e: InterruptedException) {
            if (!closed.get()) producerError = "interrupted"
        } catch (e: Exception) {
            if (!closed.get()) {
                producerError = e.javaClass.simpleName
                android.util.Log.w("PrismLive", "PCM producer failed", e)
            }
        } finally {
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** Wait on the producer without holding its lock during a TCP write.
     * A lagged socket must reconnect, never skip bytes inside a WAV entity. */
    private fun awaitBytes(offset: Long, length: Int): ByteArray? = synchronized(lock) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!closed.get() && producerError == null) {
            if (offset < replay.start) return@synchronized null
            if (offset + length <= replay.end) return@synchronized replay.read(offset, length)
            val wait = deadline - System.nanoTime()
            if (wait <= 0) return@synchronized null
            lock.wait((wait / 1_000_000L).coerceAtLeast(1L))
        }
        null
    }

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (closed.get() || req.path != path || req.method !in listOf("GET", "HEAD")) {
            res.text(404, "Not found"); return
        }
        requests.incrementAndGet()
        val headers = mapOf("Content-Type" to "audio/wav", "Cache-Control" to "no-store",
            "Accept-Ranges" to "bytes")
        val range = ByteRange.parse(req.header("range"), LENGTH)
        if (range == ByteRange.Unsatisfiable) {
            res.text(416, "Invalid range", mapOf("Content-Range" to "bytes */$LENGTH")); return
        }
        val first = if (range is ByteRange.Part) range.start else 0L
        val last = if (range is ByteRange.Part) range.end else LENGTH - 1
        val length = last - first + 1
        val responseHeaders = if (range is ByteRange.Part)
            headers + ("Content-Range" to "bytes $first-$last/$LENGTH") else headers
        val status = if (range is ByteRange.Part) 206 else 200
        if (req.method == "HEAD") { res.start(status, responseHeaders, length); return }
        val wav = header()
        if (last < 44) {
            res.send(status, "audio/wav", wav.copyOfRange(first.toInt(), (last + 1).toInt()), responseHeaders)
            return
        }
        val pcmStart = maxOf(first, 44L) - 44
        val rejection = synchronized(lock) {
            when {
                closed.get() -> 503
                !replay.contains(pcmStart) -> 416
                consumers.size >= MAX_READERS -> 503
                else -> { consumers.add(res); startProducerLocked(); 0 }
            }
        }
        if (rejection != 0) {
            res.text(rejection, if (rejection == 416) "Live range unavailable" else "Live busy",
                if (rejection == 416) mapOf("Content-Range" to "bytes */$LENGTH") else emptyMap())
            android.util.Log.w("PrismLive", "PCM rejected status=$rejection range=$first-$last; ${summary()}")
            return
        }
        try {
            val out = res.start(status, responseHeaders, length)
            var pos = first
            if (pos < wav.size) {
                val n = (wav.size - pos).toInt()
                out.write(wav, pos.toInt(), n)
                pos += n
            }
            out.flush()
            // Give the receiver a genuine initial reservoir, then allow it to
            // fetch available bytes immediately instead of throttling every GET.
            if (pcmStart == 0L) {
                val warm = minOf(STARTUP_BYTES.toLong(), last - pos + 1).toInt()
                if (warm > 0 && awaitBytes(0, warm) == null) throw IOException("PCM startup buffer unavailable")
            }
            android.util.Log.i("PrismLive", "PCM HTTP $status bytes=$first-$last/$LENGTH; ${summary()}")
            while (!closed.get() && pos <= last) {
                val n = minOf(CHUNK.toLong(), last - pos + 1).toInt()
                val pcm = awaitBytes(pos - 44, n) ?: throw IOException("PCM replay expired or producer stalled at $pos")
                out.write(pcm)
                out.flush()
                pos += n
                delivered.addAndGet(n.toLong())
                lastWrite = android.os.SystemClock.elapsedRealtime()
            }
        } catch (e: IOException) {
            android.util.Log.w("PrismLive", "PCM HTTP ended: ${e.message}; ${summary()}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) { consumers.remove(res) }
            android.util.Log.i("PrismLive", "Direct PCM request closed; ${summary()}")
        }
    }

    fun summary(): String {
        val window = synchronized(lock) { "readers=${consumers.size} replay=${replay.start}..${replay.end}" }
        val now = android.os.SystemClock.elapsedRealtime()
        return "PCM requests=${requests.get()} bytes=${delivered.get()} $window " +
            "writeAgeMs=${if (lastWrite == 0L) -1 else now - lastWrite} producerAgeMs=${if (lastProduced == 0L) -1 else now - lastProduced} " +
            "producer=${producerError ?: "ok"} receiving=$receiving"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val sockets = synchronized(lock) { lock.notifyAll(); consumers.toList() }
        sockets.forEach { it.abort() }
        http.stop()
        producer?.let { thread ->
            thread.interrupt()
            if (thread !== Thread.currentThread()) try { thread.join(350) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}
