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
    private var resumePosition: Long? = null
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
        if (requested && !paused) send(GlagolProtocol.stop())
        requested = false
        paused = false
        resumePosition = null
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
        val resume = if (paused) resumePosition else null
        // audio_play replaces the previous stream. load() has already stopped our old stream.
        // A second stop here can arrive while the new source is buffering.
        // Mark ownership before sending: a cancelled send can still have reached the speaker.
        requested = true
        paused = false
        sentAt = SystemClock.elapsedRealtime()
        send(GlagolProtocol.playStream(url, media.title, media.artist))
        if (resume != null && resume > 0) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                val observed = state
                if (observed?.matches(url) == true && observed.playing == true && observed.at >= sentAt) {
                    seek(resume)
                    resumePosition = null
                    return
                }
                delay(100)
            }
            throw IOException("Station resume could not be confirmed")
        }
    }

    override suspend fun pause() {
        if (!requested || paused) return
        val current = state
        resumePosition = current?.takeIf { it.matches(loaded?.first) }?.position
        stopControl.stop(socket, tokens.tokenFor(identity), allowTextFallback = false)
        paused = true
        sentAt = SystemClock.elapsedRealtime()
    }

    override suspend fun stop() {
        try {
            if (requested && !paused) stopControl.stop(socket, tokens.tokenFor(identity))
        } finally {
            requested = false
            paused = false
            resumePosition = null
            loaded = null
        }
    }

    override suspend fun seek(ms: Long) {
        require(ms >= 0)
        if (paused) { resumePosition = ms; return }
        val (url, media) = loaded ?: throw IOException("No track to seek")
        val target = ms.coerceAtMost(media.durationMs.takeIf { it > 0 } ?: ms)
        val before = SystemClock.elapsedRealtime()
        val payload = buildJsonObject { put("command", "rewind"); put("position", target / 1000.0) }
        send(payload)
        send(GlagolProtocol.ping())
        // No fabricated success: require fresh position from this stream. Cancel on a newer seek.
        val confirmed = kotlinx.coroutines.withTimeoutOrNull(1800) {
            while (!StationSeekConfirmation.matches(state, url, target, before)) delay(80)
            true
        } == true
        if (!confirmed) throw IOException("Station did not confirm seek")
    }

    override suspend fun transport(): TransportInfo {
        val now = SystemClock.elapsedRealtime()
        val previous = state
        if (previous == null || now - previous.at > 2500) {
            send(GlagolProtocol.ping())
            withTimeout(1800) {
                while (state == null || SystemClock.elapsedRealtime() - (state?.at ?: 0) > 3000) delay(100)
            }
        }
        val s = state ?: throw IOException("No Station state")
        val currentUrl = loaded?.first
        val matching = s.matches(currentUrl)
        val mode = when {
            !requested -> "STOPPED"
            matching && s.playing == true && s.at >= sentAt -> "PLAYING"
            paused && s.playing == false -> "PAUSED_PLAYBACK"
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
        if (paused && track != null && s?.playing == false) {
            return PositionInfo(track.first, resumePosition, track.second.durationMs)
        }
        // A foreign source must not be mistaken for the current Prism stream.
        return PositionInfo(s?.id?.takeIf { s.playing == true }, null, null)
    }

    fun observed(): StationPlaybackState? = state?.takeIf { SystemClock.elapsedRealtime() - it.at <= 4000 }
    fun ownsObservedStream(): Boolean = observed()?.matches(loaded?.first) == true
    override suspend fun volume(): Int? = observed()?.volume
    override suspend fun setVolume(v: Int) = send(GlagolProtocol.volume(v.coerceIn(0, 100) / 100.0))
    override suspend fun sinkMimes(): Set<String> = setOf("audio/mpeg", "audio/wav")
    override suspend fun close() { socket.close(); token = null }
}
