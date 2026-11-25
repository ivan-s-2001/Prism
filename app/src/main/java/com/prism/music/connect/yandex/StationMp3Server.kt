package com.prism.music.connect.yandex

import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal finite MP3 HTTP endpoint for a single selected Station.
 * Input must be an already complete, immutable MP3 file in app-owned storage.
 */
internal class StationMp3Server(
    bindAddress: Inet4Address,
    private val stationAddress: Inet4Address,
    private val mp3: File,
    private val onDownloaded: (Long) -> Unit = {},
) : Closeable {
    private val size = mp3.length().also { require(mp3.isFile && it > 0) }
    private val server = ServerSocket()
    private val closed = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newSingleThreadExecutor()
    private val path = "/${UUID.randomUUID()}/track.mp3"
    val url: String
    private val acceptThread: Thread

    init {
        require(bindAddress.isSiteLocalAddress || bindAddress.isLinkLocalAddress)
        require(stationAddress.isSiteLocalAddress || stationAddress.isLinkLocalAddress)
        try {
            server.bind(InetSocketAddress(bindAddress, 0))
        } catch (e: Exception) {
            server.close()
            workers.shutdownNow()
            throw e
        }
        url = "http://${bindAddress.hostAddress}:${server.localPort}$path"
        acceptThread = Thread({
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (socket.inetAddress != stationAddress || closed.get() || clients.isNotEmpty()) {
                    socket.close()
                    continue
                }
                clients.add(socket)
                try {
                    workers.execute {
                        try { socket.use { serve(it) } }
                        catch (_: Exception) { /* station disconnected; no sensitive logs */ }
                        finally { clients.remove(socket) }
                    }
                } catch (_: Exception) {
                    clients.remove(socket)
                    socket.close()
                }
            }
        }, "Prism-Station-HTTP").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5000
        val input = socket.getInputStream()
        val request = readLine(input).split(' ')
        val out = socket.getOutputStream()
        fun error(status: String) {
            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        if (request.size != 3 || request[1] != path) return error("404 Not Found")
        if (request[0] != "GET" && request[0] != "HEAD") return error("405 Method Not Allowed")
        var range: String? = null
        var headersEnded = false
        repeat(64) {
            val header = readLine(input)
            if (header.isEmpty()) { headersEnded = true; break }
            if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
        }
        if (!headersEnded) return error("400 Bad Request")
        if (mp3.length() != size) return error("409 Conflict")
        var first = 0L
        var last = size - 1
        if (range != null) {
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range!!) ?: return error("416 Range Not Satisfiable")
            first = match.groupValues[1].toLongOrNull() ?: return error("416 Range Not Satisfiable")
            last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            if (first !in 0 until size || last < first) return error("416 Range Not Satisfiable")
        }
        val count = last - first + 1
        val header = buildString {
            append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
            append("Content-Type: audio/mpeg\r\nContent-Length: $count\r\nAccept-Ranges: bytes\r\n")
            if (range != null) append("Content-Range: bytes $first-$last/$size\r\n")
            append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (request[0] == "GET") {
            java.io.RandomAccessFile(mp3, "r").use { source ->
                source.seek(first)
                val buffer = ByteArray(32 * 1024)
                var remaining = count
                while (remaining > 0 && !closed.get()) {
                    val n = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw java.io.EOFException()
                    out.write(buffer, 0, n)
                    remaining -= n
                }
                out.flush()
                if (remaining == 0L) onDownloaded(count)
            }
        } else out.flush()
    }

    private fun readLine(input: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 4096) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            if (b == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(b)
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
