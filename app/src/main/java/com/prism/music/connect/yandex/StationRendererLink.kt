package com.prism.music.connect.yandex

import android.os.SystemClock
import com.prism.music.connect.CastMedia
import com.prism.music.connect.PositionInfo
import com.prism.music.connect.RendererLink
import com.prism.music.connect.TransportInfo
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Experimental adapter to Prism's existing queue and remote controls. */
internal class StationRendererLink(
    station: YandexStation,
    private val identity: OwnedStationIdentity,
    private val tokens: StationTokenSource,
    private val delivery: (String) -> Long = { 0L },
) : RendererLink {
    @Volatile private var state: StationPlaybackState? = null
    private val stopControl = StationStopControl()
    private val evidence = StationStreamEvidence()
    private val socket = GlagolSocket(station, identity.certificatePem) { text ->
        stopControl.onState(text)
        StationPlaybackState.parse(text, SystemClock.elapsedRealtime())?.let { state = evidence.observe(it, delivery) }
    }
    private var loaded: Pair<String, CastMedia>? = null
    private var requested = false
    private var paused = false
    private var resumePosition: Long? = null
    private var sentAt = 0L
    private var token: String? = null
    private var tokenAt = 0L

    private suspend fun currentToken(): String {
        val now = SystemClock.elapsedRealtime()
        return token?.takeIf { now - tokenAt < 25_000 } ?: tokens.tokenFor(identity).also {
            token = it
            tokenAt = now
        }
    }

    private suspend fun send(payload: JsonObject) = socket.send(currentToken(), payload)

    private fun command(name: String) = buildJsonObject { put("command", name) }

    override suspend fun load(url: String, didl: String, media: CastMedia) {
        // Current audio_play supports file streams; physical playback on this phone is still unverified.
        require(media.mime in setOf("audio/mpeg", "audio/wav", "audio/x-wav"))
        // Anonymous stream reports require a confirmed stopped boundary before replacing playback.
        if (requested && !paused && observed()?.takeIf { it.at >= sentAt }?.playing != false) stopControl.stop(socket, currentToken(), allowTextFallback = false)
        evidence.clear()
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
        if (requested && !paused) stopControl.stop(socket, currentToken(), allowTextFallback = false)
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
        evidence.begin(url, delivery(url), sentAt)
        val reply = socket.request(currentToken(), GlagolProtocol.playStream(url, media.title, media.artist), timeoutMs = 600L)
        if (reply != null && !reply.equals("SUCCESS", ignoreCase = true)) throw IOException("Station rejected audio_play")
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
        stopControl.stop(socket, currentToken(), allowTextFallback = false)
        paused = true
        sentAt = SystemClock.elapsedRealtime()
    }

    override suspend fun stop() {
        try {
            if (requested && !paused) stopControl.stop(socket, currentToken())
        } finally {
            evidence.clear()
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
        val confirmed = withTimeoutOrNull(1800) {
            var nextPing = before + 450
            while (!StationSeekConfirmation.matches(state, url, target, before)) {
                delay(80)
                if (SystemClock.elapsedRealtime() >= nextPing) {
                    send(GlagolProtocol.ping())
                    nextPing = SystemClock.elapsedRealtime() + 450
                }
            }
            true
        } == true
        if (!confirmed) throw IOException("Station did not confirm seek")
    }

    override suspend fun transport(): TransportInfo {
        val now = SystemClock.elapsedRealtime()
        val previous = state
        if (previous == null || now - previous.at > 2500) {
            send(GlagolProtocol.ping())
            val fresh = withTimeoutOrNull(1800) {
                while (state == null || state === previous) delay(80)
                true
            } == true
            if (!fresh) throw IOException("Station state timed out")
        }
        val s = state ?: throw IOException("No Station state")
        return transportInfo(s)
    }

    private var lastPingAt = 0L

    /** Fast periodic read: never wait for a reply while holding Cast's command mutex. */
    suspend fun pollState(): Pair<TransportInfo, PositionInfo> {
        val now = SystemClock.elapsedRealtime()
        val before = state
        val interval = if (requested && !paused && before?.matches(loaded?.first) != true) 350L else 750L
        if ((before == null || now - before.at >= interval) && now - lastPingAt >= interval) {
            lastPingAt = now
            send(GlagolProtocol.ping())
        }
        val fresh = state ?: throw IOException("No Station state")
        if (SystemClock.elapsedRealtime() - fresh.at > 4000) throw IOException("Station state is stale")
        return transportInfo(fresh) to positionInfo(fresh)
    }

    private fun transportInfo(s: StationPlaybackState): TransportInfo {
        val matching = s.matches(loaded?.first)
        val mode = when {
            !requested -> "STOPPED"
            paused && s.playing == false -> "PAUSED_PLAYBACK"
            matching && s.playing == true && s.at >= sentAt -> "PLAYING"
            matching && s.playing == false && s.at >= sentAt -> "PAUSED_PLAYBACK"
            SystemClock.elapsedRealtime() - sentAt < 5000 -> "TRANSITIONING"
            else -> "STOPPED"
        }
        return TransportInfo(mode, "OK")
    }

    override suspend fun position(): PositionInfo = positionInfo(state)

    private fun positionInfo(s: StationPlaybackState?): PositionInfo {
        val track = loaded
        if (paused && track != null && s?.playing == false) {
            return PositionInfo(track.first, resumePosition, track.second.durationMs)
        }
        if (requested && track != null && s != null && s.matches(track.first) && s.at >= sentAt) {
            return PositionInfo(track.first, s.position, s.duration ?: track.second.durationMs)
        }
        return PositionInfo(s?.id?.takeIf { s.playing == true }, null, null)
    }

    fun observed(): StationPlaybackState? = state?.takeIf { SystemClock.elapsedRealtime() - it.at <= 4000 }
    fun ownsObservedStream(): Boolean = observed()?.matches(loaded?.first) == true
    override suspend fun volume(): Int? = observed()?.volume
    override suspend fun setVolume(v: Int) = send(GlagolProtocol.volume(v.coerceIn(0, 100) / 100.0))
    override suspend fun sinkMimes(): Set<String> = setOf("audio/mpeg", "audio/wav")
    override suspend fun close() { evidence.clear(); socket.close(); token = null }
}
