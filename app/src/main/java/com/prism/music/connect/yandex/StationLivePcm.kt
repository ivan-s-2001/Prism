package com.prism.music.connect.yandex

import com.prism.music.connect.HttpServer
import com.prism.music.connect.HttpRequest
import com.prism.music.connect.HttpResponse
import com.prism.music.connect.Wav
import java.io.Closeable
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Direct PCM without encoder or segment buffering. The WAV header marks an unknown
 * duration; fixed HTTP length is needed by Station firmware (Music Assistant live sources).
 * The receiver may still maintain its own playback buffer.
 */
internal class StationLivePcm(phone: Inet4Address, station: Inet4Address,
    private val source: (Int) -> ByteArray) : Closeable {
    companion object {
        private const val RATE = 44100
        private const val FRAME_BYTES = 4
        private const val CHUNK = RATE * FRAME_BYTES / 50
        private const val LENGTH = 12L * 3600 * RATE * FRAME_BYTES + 44
        internal fun header(): ByteArray = Wav.header(RATE, 2, 0L).apply {
            for (i in 4..7) this[i] = 0xff.toByte()
            for (i in 40..43) this[i] = 0xff.toByte()
        }
    }
    private val closed = AtomicBoolean()
    private val consumer = AtomicReference<HttpResponse?>(null)
    private val requests = AtomicLong()
    private val delivered = AtomicLong()
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

    val receiving: Boolean get() = consumer.get() != null && delivered.get() >= RATE * FRAME_BYTES / 2 &&
        android.os.SystemClock.elapsedRealtime() - lastWrite < 5000L

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (closed.get() || req.path != path || req.method !in listOf("GET", "HEAD")) {
            res.text(404, "Not found"); return
        }
        requests.incrementAndGet()
        val headers = mapOf("Content-Type" to "audio/wav", "Cache-Control" to "no-store",
            "Accept-Ranges" to "none", "icy-name" to "Prism Live")
        android.util.Log.i("PrismLive", "Direct PCM ${req.method} range=${req.headers["range"] ?: "none"}")
        if (req.method == "HEAD") { res.start(200, headers, LENGTH); return }
        if (!consumer.compareAndSet(null, res)) { res.text(503, "Already streaming"); return }
        try {
            if (closed.get()) return
            val out = res.start(200, headers, LENGTH)
            out.write(header())
            out.flush()
            var sent = 44L
            while (!closed.get() && sent < LENGTH) {
                val n = minOf(CHUNK.toLong(), LENGTH - sent).toInt()
                val before = System.nanoTime()
                out.write(source(n))
                out.flush()
                sent += n
                delivered.addAndGet(n.toLong())
                lastWrite = android.os.SystemClock.elapsedRealtime()
                val wait = n.toLong() * 1_000_000_000L / (RATE * FRAME_BYTES) - (System.nanoTime() - before)
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            }
        } finally {
            consumer.compareAndSet(res, null)
            android.util.Log.i("PrismLive", "Direct PCM closed; bytes=${delivered.get()}")
        }
    }

    fun summary() = "PCM requests=${requests.get()} bytes=${delivered.get()} receiving=$receiving"
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        consumer.get()?.abort()
        http.stop()
    }
}
