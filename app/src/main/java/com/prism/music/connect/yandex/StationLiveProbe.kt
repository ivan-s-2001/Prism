package com.prism.music.connect.yandex

import com.prism.music.connect.Wav
import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/** Hardware experiment: one finite WAV resource, generated on demand rather than pre-rendered.
 * The receiver's own TCP and decoder buffers still determine the audible delay.
 * Not used by normal Prism playback.
 */
internal class StationLiveProbe(
    bindAddress: Inet4Address,
    private val stationAddress: Inet4Address,
    private val log: (String) -> Unit = {},
) : Closeable {
    enum class Signal(val hz: Int) { A(440), B(880), SILENCE(0) }
    companion object {
        const val RATE = 44_100
        const val CHANNELS = 2
        const val FRAME_BYTES = CHANNELS * 2
        const val SECONDS = 600
        const val DATA_BYTES = RATE.toLong() * FRAME_BYTES * SECONDS
        const val TOTAL_BYTES = 44L + DATA_BYTES
        private const val CHUNK = RATE * FRAME_BYTES / 50 // 20 ms
        private const val AMP = 900 // under 3% of full-scale PCM

        /** Also handles arbitrary byte ranges, including split PCM samples. */
        fun pcm(offset: Long, length: Int, signal: Signal): ByteArray {
            require(offset >= 0 && length >= 0)
            return ByteArray(length) { i ->
                val pos = offset + i
                val frame = pos / FRAME_BYTES
                val phase = 2.0 * PI * signal.hz * (frame % RATE) / RATE
                val sample = if (signal == Signal.SILENCE) 0 else (AMP * sin(phase)).toInt()
                (sample shr ((pos % 2).toInt() * 8)).toByte()
            }
        }
    }
    private val header = Wav.header(RATE, CHANNELS, DATA_BYTES)
    private val signal = AtomicReference(Signal.A)
    private val closed = AtomicBoolean()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newFixedThreadPool(2)
    private val server = ServerSocket()
    private val path = "/${UUID.randomUUID()}/live.wav"
    private val gets = AtomicLong()
    private val heads = AtomicLong()
    private val bytes = AtomicLong()
    private val changes = AtomicLong()
    private val started = System.nanoTime()
    val url: String

    init {
        require(bindAddress.isSiteLocalAddress || bindAddress.isLinkLocalAddress)
        require(stationAddress.isSiteLocalAddress || stationAddress.isLinkLocalAddress)
        try { server.bind(InetSocketAddress(bindAddress, 0)) }
        catch (e: Exception) { server.close(); workers.shutdownNow(); throw e }
        url = "http://${bindAddress.hostAddress}:${server.localPort}$path"
        Thread({
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (closed.get() || socket.inetAddress != stationAddress || clients.size >= 2) {
                    socket.close()
                    continue
                }
                clients.add(socket)
                if (closed.get()) { clients.remove(socket); socket.close(); continue }
                try {
                    workers.execute {
                        try { socket.use { serve(it) } }
                        catch (e: Exception) { if (!closed.get()) log("HTTP ended: ${e.javaClass.simpleName}") }
                        finally { clients.remove(socket) }
                    }
                } catch (_: Exception) { clients.remove(socket); socket.close() }
            }
        }, "Prism-Station-Live").apply { isDaemon = true; start() }
    }

    private fun elapsedMs() = (System.nanoTime() - started) / 1_000_000L
    fun select(next: Signal) {
        signal.set(next)
        changes.incrementAndGet()
        log("signal=$next at ${elapsedMs()}ms; PCM bytes written=${bytes.get()}; no new play command")
    }

    fun summary(): String = "Signal: ${signal.get()} · changes: ${changes.get()}\n" +
        "GET: ${gets.get()} · HEAD: ${heads.get()} · sockets: ${clients.size}\n" +
        "PCM written: ${bytes.get()} bytes (approx. ${bytes.get() / (RATE * FRAME_BYTES)} s total across requests)\n" +
        "Elapsed: ${elapsedMs() / 1000}s. Written bytes do not prove audible playback."

    private fun serve(socket: Socket) {
        socket.soTimeout = 5000
        socket.tcpNoDelay = true
        socket.sendBufferSize = 4096
        val input = socket.getInputStream()
        val request = line(input).split(' ')
        val out = socket.getOutputStream()
        fun fail(status: String) {
            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        if (request.size != 3 || request[1] != path) return fail("404 Not Found")
        val method = request[0]
        if (method != "GET" && method != "HEAD") return fail("405 Method Not Allowed")
        var range: String? = null
        var ended = false
        repeat(64) {
            if (!ended) {
                val h = line(input)
                if (h.isEmpty()) ended = true
                else if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim()
            }
        }
        if (!ended) return fail("400 Bad Request")
        var first = 0L
        var last = TOTAL_BYTES - 1
        range?.let {
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) ?: return fail("416 Range Not Satisfiable")
            first = match.groupValues[1].toLongOrNull() ?: return fail("416 Range Not Satisfiable")
            val end = match.groupValues[2]
            if (end.isNotEmpty()) last = minOf(end.toLongOrNull() ?: return fail("416 Range Not Satisfiable"), last)
            if (first !in 0 until TOTAL_BYTES || last < first) return fail("416 Range Not Satisfiable")
        }
        val length = last - first + 1
        if (method == "GET") gets.incrementAndGet() else heads.incrementAndGet()
        log("$method range=${range ?: "none"} at ${elapsedMs()}ms; length=$length")
        val response = buildString {
            append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
            append("Content-Type: audio/wav\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\n")
            if (range != null) append("Content-Range: bytes $first-$last/$TOTAL_BYTES\r\n")
            append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
        }
        out.write(response.toByteArray(Charsets.US_ASCII))
        out.flush()
        if (method == "HEAD") return
        var pos = first
        if (pos < header.size) {
            val n = minOf(header.size.toLong() - pos, last - pos + 1).toInt()
            out.write(header, pos.toInt(), n)
            pos += n
        }
        // Pace each write independently: never burst to catch up after receiver backpressure.
        while (pos <= last && !closed.get()) {
            val n = minOf(CHUNK.toLong(), last - pos + 1).toInt()
            val before = System.nanoTime()
            out.write(pcm(pos - header.size, n, signal.get()))
            out.flush()
            pos += n
            bytes.addAndGet(n.toLong())
            val wait = n.toLong() * 1_000_000_000L / (RATE * FRAME_BYTES) - (System.nanoTime() - before)
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
        }
        log("GET finished at ${elapsedMs()}ms; PCM total=${bytes.get()}")
    }

    private fun line(input: InputStream): String {
        val buffer = java.io.ByteArrayOutputStream()
        while (buffer.size() < 4096) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            if (b == 10) return buffer.toString("US-ASCII").trimEnd('\r')
            buffer.write(b)
        }
        throw java.io.IOException("HTTP line too long")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
