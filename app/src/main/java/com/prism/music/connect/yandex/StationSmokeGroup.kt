package com.prism.music.connect.yandex

import java.io.Closeable
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Hardware-test group: independent verified sockets, streams and counters.
 * All methods except close() are called serially by the owner's UI.
 * Concurrent commands are best effort; acoustic synchronization is not claimed.
 */
internal class StationSmokeGroup : Closeable {
    private class Member(val station: YandexStation, val session: StationSmokeSession, val frames: AtomicLong) {
        val delivered = AtomicLong()
        var error: String? = null
        var requestedVolume: Int? = null
    }

    data class Status(val station: YandexStation, val frames: Long, val bytes: Long,
        val requestedVolume: Int?, val error: String?)

    private val members = linkedMapOf<String, Member>()
    val size: Int get() = members.size
    fun contains(id: String) = members.containsKey(id)
    fun snapshot() = members.values.map {
        Status(it.station, it.frames.get(), it.delivered.get(), it.requestedVolume, it.error)
    }

    suspend fun add(station: YandexStation, api: QuasarStationApi) {
        if (contains(station.deviceId)) return
        require(members.size < 8) { "Group limit: 8 Stations" }
        val identity = api.verify(station)
        val frames = AtomicLong()
        val session = StationSmokeSession(station, identity, api) { frames.incrementAndGet() }
        try {
            session.ping()
            members[station.deviceId] = Member(station, session, frames)
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    suspend fun remove(id: String) {
        val m = members.remove(id) ?: return
        try { m.session.stop() } finally { m.session.close() }
    }

    suspend fun play(file: File) = all { m ->
        m.delivered.set(0)
        val address = withContext(Dispatchers.IO) {
            DatagramSocket().use {
                it.connect(m.station.address, m.station.port)
                it.localAddress as? Inet4Address ?: error("No IPv4 route")
            }
        }
        withContext(Dispatchers.IO) {
            m.session.play(address, file, "Prism audio test") { bytes ->
                m.delivered.addAndGet(bytes)
            }
        }
    }

    suspend fun stop() = all { it.session.stop() }

    suspend fun volume(id: String, level: Int) {
        val m = members[id] ?: error("Station not connected")
        attempt(m) {
            val v = level.coerceIn(0, 100)
            m.session.volume(v / 100.0)
            m.requestedVolume = v
        }
    }

    suspend fun volumeAll(level: Int) = all { m ->
        val v = level.coerceIn(0, 100)
        m.session.volume(v / 100.0)
        m.requestedVolume = v
    }

    private suspend fun all(action: suspend (Member) -> Unit) = coroutineScope {
        members.values.toList().map { m -> async { attempt(m) { action(m) } } }.awaitAll()
        Unit
    }

    private suspend fun attempt(m: Member, action: suspend () -> Unit) {
        try {
            action()
            m.error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { m.error = e.javaClass.simpleName }
    }

    override fun close() {
        members.values.forEach { it.session.close() }
        members.clear()
    }
}
