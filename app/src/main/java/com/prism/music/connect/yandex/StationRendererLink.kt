package com.prism.music.connect.yandex

import android.os.SystemClock
import com.prism.music.connect.CastMedia
import com.prism.music.connect.PositionInfo
import com.prism.music.connect.RendererLink
import com.prism.music.connect.TransportInfo
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Experimental adapter to Prism's existing queue and remote controls. */
internal class StationRendererLink(
    station: YandexStation,
    private val identity: OwnedStationIdentity,
    private val tokens: StationTokenSource,
) : RendererLink {
    @Volatile private var state: StationPlaybackState? = null
    private val stopControl = StationStopControl()
    private val socket = GlagolSocket(station, identity.certificatePem) { text ->
        stopControl.onState(text)
        StationPlaybackState.parse(text, SystemClock.elapsedRealtime())?.let { state = it }
    }
    private var loaded: Pair<String, CastMedia>? = null
    private var requested = false
    private var paused = false
    private var sentAt = 0L
    private var token: String? = null
    private var tokenAt = 0L

    private suspend fun send(payload: JsonObject) {
        val now = SystemClock.elapsedRealtime()
        val current = token?.takeIf { now - tokenAt < 25_000 } ?: tokens.tokenFor(identity).also {
            token = it
            tokenAt = now
        }
        socket.send(current, payload)
    }

    private fun command(name: String) = buildJsonObject { put("command", name) }

    override suspend fun load(url: String, didl: String, media: CastMedia) {
        // Current audio_play supports file streams; physical playback on this phone is still unverified.
        require(media.mime in setOf("audio/mpeg", "audio/wav", "audio/x-wav"))
        if (requested) send(GlagolProtocol.stop())
        requested = false
        paused = false
        loaded = url to media
    }

    override suspend fun loadNext(url: String, didl: String, media: CastMedia) {
        throw UnsupportedOperationException("Station has no gapless queue")
    }

    override suspend fun play() {
        val (url, media) = loaded ?: throw IOException("No track")
        if (requested && !paused && state?.matches(url) == true && state?.playing == true) return
        if (state?.type == "bluetooth" && state?.playing == true) {
            throw IOException("Station is playing Bluetooth")
        }
        if (paused) {
            // Reload the external file, as in the current YandexStation integration.
            send(GlagolProtocol.playStream(url, media.title, media.artist))
        } else {
            send(GlagolProtocol.stop())
            delay(150)
            send(GlagolProtocol.playStream(url, media.title, media.artist))
        }
        requested = true
        paused = false
        sentAt = SystemClock.elapsedRealtime()
    }

    override suspend fun pause() {
        if (!requested) return
        send(GlagolProtocol.stop())
        paused = true
        sentAt = SystemClock.elapsedRealtime()
    }

    override suspend fun stop() {
        try {
            if (requested) stopControl.stop(socket, tokens.tokenFor(identity))
        } finally {
            requested = false
            paused = false
            loaded = null
        }
    }

    override suspend fun seek(ms: Long) {
        require(ms >= 0)
        send(buildJsonObject { put("command", "rewind"); put("position", ms / 1000.0) })
    }

    override suspend fun transport(): TransportInfo {
        val now = SystemClock.elapsedRealtime()
        val previous = state
        if (previous == null || now - previous.at > 2500) {
            send(GlagolProtocol.ping())
            withTimeout(6000) {
                while (state == null || SystemClock.elapsedRealtime() - (state?.at ?: 0) > 3000) delay(100)
            }
        }
        val s = state ?: throw IOException("No Station state")
        val currentUrl = loaded?.first
        val matching = s.matches(currentUrl)
        val mode = when {
            !requested -> "STOPPED"
            matching && s.playing == true -> "PLAYING"
            matching && s.playing == false -> "PAUSED_PLAYBACK"
            now - sentAt < 5000 -> "TRANSITIONING"
            else -> "STOPPED"
        }
        return TransportInfo(mode, "OK")
    }

    override suspend fun position(): PositionInfo {
        val s = state
        val track = loaded
        if (requested && track != null && s != null && s.matches(track.first)) {
            return PositionInfo(track.first, s.position, s.duration ?: track.second.durationMs)
        }
        // A foreign source must not be mistaken for the current Prism stream.
        return PositionInfo(s?.id?.takeIf { s.playing == true }, null, null)
    }

    override suspend fun volume(): Int? = state?.volume
    override suspend fun setVolume(v: Int) = send(GlagolProtocol.volume(v.coerceIn(0, 100) / 100.0))
    override suspend fun sinkMimes(): Set<String> = setOf("audio/mpeg", "audio/wav")
    override suspend fun close() { socket.close(); token = null }
}
