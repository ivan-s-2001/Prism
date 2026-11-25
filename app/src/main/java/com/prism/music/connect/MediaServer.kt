package com.prism.music.connect

import java.security.SecureRandom
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves songs to renderers on the local network: `GET/HEAD /cast/<token>/<key>` with Range support and the DLNA
 * headers TVs look for. [token] is new for every cast session, so a link only works while it's being cast, and only
 * the few songs around the one playing are on offer at a time.
 */
class MediaServer(preferredPort: Int = PORT) {
    @Volatile var token: String = newToken()
        private set
    private class Entry(val body: CastBody, val observedPeer: String?) {
        val delivery = AtomicLong(0)
        val clients = mutableSetOf<HttpResponse>()
        var retired = false
    }
    private val sequence = AtomicLong(0)
    private val bodies = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            if (size <= KEEP) return false
            eldest?.value?.let(::retire)
            return true
        }
    }
    private fun retire(entry: Entry) {
        entry.retired = true
        entry.clients.forEach { it.abort() }
        entry.clients.clear()
    }

    /** Requests served, for the log and tests: "GET key bytes=a-b". */
    val log = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val http = HttpServer("prism-cast", preferredPort, maxBody = 0, handler = ::handle)

    val port: Int get() = http.port

    fun start(): Boolean = http.start()

    fun stop() {
        http.stop()
        synchronized(bodies) { bodies.values.forEach(::retire); bodies.clear() }
    }

    /** A new session: links from the last one stop working. */
    fun newSession() {
        synchronized(bodies) {
            token = newToken()
            bodies.values.forEach(::retire)
            bodies.clear()
        }
    }

    /** Offers [body] as [key] (e.g. "dQw4w9WgXcQ.wav"); returns its link as seen from [host] (this phone's address). */
    fun publish(key: String, body: CastBody, host: String): String =
        publishForSession(token, key, body, host)

    /** A decoder finishing after disconnect must not publish into a newer cast session. */
    fun publishForSession(expectedToken: String, key: String, body: CastBody, host: String, observedPeer: String? = null): String =
        synchronized(bodies) {
            check(token == expectedToken) { "Cast media session changed" }
            bodies.put(key, Entry(body, observedPeer))?.let(::retire)
            "http://$host:$port/cast/$expectedToken/$key"
        }

    data class StationSlice(val url: String, val offsetMs: Long, val durationMs: Long)

    /** Create a unique finite WAV stream without probing or decoding the song again. */
    fun stationSlice(originalUrl: String, positionMs: Long): StationSlice = synchronized(bodies) {
        val uri = java.net.URI(originalUrl)
        val parts = uri.path.trimStart('/').split('/')
        if (parts.size != 3 || parts[0] != "cast" || parts[1] != token) throw IOException("Expired media session")
        val original = bodies[parts[2]] ?: throw IOException("Original audio expired")
        val pcm = original.body as? PcmBody ?: throw IOException("Station requires seekable PCM")
        val slice = pcm.sliceFrom(positionMs)
        val key = "station_" + java.util.UUID.randomUUID().toString() + ".wav"
        bodies[key] = Entry(slice.body, original.observedPeer)
        StationSlice("http://${uri.host}:$port/cast/$token/$key", slice.offsetMs, slice.durationMs)
    }

    /** Revoke only a disposable Station slice, not its reusable original PCM source. */
    fun retireStationSlice(url: String) {
        val parts = runCatching { java.net.URI(url).path.trimStart('/').split('/') }.getOrNull() ?: return
        if (parts.size != 3 || parts[0] != "cast" || !parts[2].startsWith("station_")) return
        synchronized(bodies) { if (parts[1] == token) bodies.remove(parts[2])?.let(::retire) }
    }

    fun url(host: String, key: String) = "http://$host:$port/cast/$token/$key"

    fun has(key: String) = synchronized(bodies) { bodies.containsKey(key) }

    /** Evidence of a real GET body write to the expected Station IP, never HEAD or another client.
     * This proves HTTP delivery started, not that audio has reached the speaker.
     */
    fun deliverySequence(url: String): Long {
        val parts = runCatching { java.net.URI(url).path.trimStart('/').split('/') }.getOrNull() ?: return 0
        if (parts.size != 3 || parts[0] != "cast") return 0
        return synchronized(bodies) {
            if (parts[1] == token) bodies[parts[2]]?.delivery?.get() ?: 0 else 0
        }
    }

    private fun observedOutput(entry: Entry, req: HttpRequest, out: OutputStream, start: Long): OutputStream {
        if (entry.observedPeer == null || req.remote.hostAddress != entry.observedPeer) return out
        return object : OutputStream() {
            private var started = false
            private var position = start
            private val firstAudioByte = if ((entry.body as? PcmBody)?.wav == true) 44L else 0L
            override fun write(b: Int) { out.write(b); position++; if (position > firstAudioByte) mark() }
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                position += len
                if (len > 0 && position > firstAudioByte) mark()
            }
            override fun flush() = out.flush()
            private fun mark() {
                if (!started) {
                    // Flush the first data immediately instead of waiting for the 64 KiB HTTP buffer.
                    out.flush()
                    entry.delivery.set(sequence.incrementAndGet())
                    started = true
                }
            }
        }
    }

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (req.method != "GET" && req.method != "HEAD") { res.text(405, "GET only", mapOf("Allow" to "GET, HEAD")); return }
        val parts = req.path.removePrefix("/").split('/')
        if (parts.size != 3 || parts[0] != "cast") { res.text(404, "Not found"); return }
        val key = parts[2]
        val entry = synchronized(bodies) { if (parts[1] == token) bodies[key] else null } ?: run { res.text(404, "Not found"); return }
        val registered = synchronized(bodies) {
            if (entry.retired) false else { entry.clients.add(res); true }
        }
        if (!registered) { res.text(404, "Not found"); return }
        try {
        val body = entry.body
        val total = body.length
        val headers = linkedMapOf(
            "Content-Type" to body.mime,
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-cache",
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to ProtocolInfo.of(body.mime, body.converted).substringAfterLast(':'),
            "Server" to "Android UPnP/1.1 Prism/1.7",
        )
        val range = ByteRange.parse(req.header("range"), total)
        log += "${req.method} $key ${req.header("range") ?: "whole"}"
        when (range) {
            ByteRange.Unsatisfiable -> res.send(416, null, ByteArray(0), headers + ("Content-Range" to "bytes */$total"))
            ByteRange.Whole -> {
                val out = res.start(200, headers, total)
                if (req.method == "GET") body.write(0, total - 1, observedOutput(entry, req, out, 0))
                out.flush()
            }
            is ByteRange.Part -> {
                val out = res.start(206, headers + ("Content-Range" to "bytes ${range.start}-${range.end}/$total"), range.length)
                if (req.method == "GET") body.write(range.start, range.end, observedOutput(entry, req, out, range.start))
                out.flush()
            }
        }
        } finally {
            synchronized(bodies) { entry.clients.remove(res) }
        }
    }

    companion object {
        const val PORT = 47322
        private const val KEEP = 8
        private val random = SecureRandom()

        fun newToken(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
