package com.prism.music.connect.yandex

import android.os.SystemClock
import android.util.Log
import com.prism.music.connect.CastMedia
import com.prism.music.connect.MediaServer
import com.prism.music.connect.PositionInfo
import com.prism.music.connect.RendererLink
import com.prism.music.connect.TransportInfo
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Receiver owns observed time; the host changes position by replacing the WAV stream. */
internal class StationRendererLink(
    station: YandexStation,
    private val identity: OwnedStationIdentity,
    private val tokens: StationTokenSource,
    private val delivery: (String) -> Long = { 0L },
    private val slice: ((String, Long) -> MediaServer.StationSlice)? = null,
) : RendererLink {
    private data class Attempt(val physical: String, val logical: String, val offset: Long, val duration: Long, val at: Long)
    @Volatile private var state: StationPlaybackState? = null
    @Volatile private var attempt: Attempt? = null
    private val stopControl = StationStopControl()
    private val evidence = StationStreamEvidence()
    private val socket = GlagolSocket(station, identity.certificatePem) { text ->
        stopControl.onState(text)
        StationPlaybackState.parse(text, SystemClock.elapsedRealtime())?.let { raw ->
            state = evidence.observe(raw, delivery)
        }
    }
    private var loaded: Pair<String, CastMedia>? = null
    private var requested = false
    private var paused = false
    private var target = 0L
    private var confirmedPausePosition: Long? = null
    private var token: String? = null
    private var tokenAt = 0L
    private var lastPingAt = 0L

    private suspend fun currentToken(): String {
        val now = SystemClock.elapsedRealtime()
        return token?.takeIf { now - tokenAt < 25_000 } ?: tokens.tokenFor(identity).also {
            token = it
            tokenAt = now
        }
    }

    private suspend fun send(payload: kotlinx.serialization.json.JsonObject) = socket.send(currentToken(), payload)

    private fun absolute(raw: StationPlaybackState?): StationPlaybackState? {
        val a = attempt ?: return raw
        if (raw == null || raw.at < a.at || !raw.matches(a.physical)) return raw
        return raw.copy(id = a.logical, position = raw.position?.let { (a.offset + it).coerceAtMost(a.duration) },
            duration = a.duration)
    }

    private suspend fun stopActive() {
        if (!requested || paused) return
        val a = attempt
        val fresh = state?.takeIf { a != null && it.at >= a.at && SystemClock.elapsedRealtime() - it.at <= 1000 }
        if (fresh?.playing != false) stopControl.stop(socket, currentToken(), allowTextFallback = false)
        requested = false
    }

    override suspend fun load(url: String, didl: String, media: CastMedia) {
        require(media.mime in setOf("audio/mpeg", "audio/wav", "audio/x-wav"))
        stopActive()
        evidence.clear()
        attempt = null
        requested = false
        paused = false
        target = 0
        confirmedPausePosition = null
        loaded = url to media
    }

    fun setStartPosition(ms: Long) {
        check(!requested)
        target = ms.coerceAtLeast(0)
    }

    override suspend fun loadNext(url: String, didl: String, media: CastMedia) {
        throw UnsupportedOperationException("Station has no gapless queue")
    }

    private suspend fun startAt(ms: Long) {
        val (logical, media) = loaded ?: throw IOException("No track")
        val latest = state
        if (latest?.type == "bluetooth" && latest.playing == true) throw IOException("Station is playing Bluetooth")
        // Create the replacement before stopping the old stream: failure leaves old playback intact.
        val part = slice?.invoke(logical, ms) ?: if (ms == 0L) {
            MediaServer.StationSlice(logical, 0, media.durationMs)
        } else throw IOException("Seekable Station stream is unavailable")
        stopActive()
        val auth = currentToken()
        val at = SystemClock.elapsedRealtime()
        evidence.begin(part.url, delivery(part.url), at)
        attempt = Attempt(part.url, logical, part.offsetMs, part.durationMs, at)
        requested = true
        paused = false
        target = part.offsetMs
        confirmedPausePosition = null
        // A cancelled send may already have reached the receiver; keep ownership until confirmed stop.
        val reply = socket.request(auth, GlagolProtocol.playStream(part.url, media.title, media.artist), timeoutMs = 600L)
        if (reply != null && !reply.equals("SUCCESS", ignoreCase = true)) throw IOException("Station rejected audio_play")
        Log.i("PrismStation", "audio_play offset=${part.offsetMs} duration=${part.durationMs} reply=${if (reply == null) "pending" else "success"}")
    }

    override suspend fun play() {
        val a = attempt
        if (requested && !paused && a != null) {
            // No blind restarts when the receiver is already fetching the current URL.
            val current = state
            if ((current?.matches(a.physical) == true && current.playing == true && current.at >= a.at) ||
                delivery(a.physical) > 0L) return
        }
        startAt(target)
    }

    override suspend fun pause() {
        if (!requested || paused) return
        val current = observed()
        val position = current?.takeIf { it.matches(loaded?.first) }?.position
        stopControl.stop(socket, currentToken(), allowTextFallback = false)
        confirmedPausePosition = position
        target = position ?: target
        paused = true
        Log.i("PrismStation", "pause confirmed position=${position ?: -1}")
    }

    override suspend fun stop() {
        try {
            if (requested && !paused) stopControl.stop(socket, currentToken())
        } finally {
            evidence.clear()
            attempt = null
            requested = false
            paused = false
            target = 0
            confirmedPausePosition = null
            loaded = null
        }
    }

    override suspend fun seek(ms: Long) {
        require(ms >= 0)
        if (paused || !requested) { target = ms; return }
        startAt(ms)
    }

    override suspend fun transport(): TransportInfo {
        val previous = state
        if (previous == null || SystemClock.elapsedRealtime() - previous.at > 2500) {
            send(GlagolProtocol.ping())
            val fresh = withTimeoutOrNull(1800) {
                while (state == null || state === previous) delay(80)
                true
            } == true
            if (!fresh) throw IOException("Station state timed out")
        }
        return transportInfo(state ?: throw IOException("No Station state"))
    }

    suspend fun pollState(): Pair<TransportInfo, PositionInfo> {
        val now = SystemClock.elapsedRealtime()
        val before = state
        val interval = if (requested && !paused && before?.matches(attempt?.physical) != true) 350L else 750L
        if ((before == null || now - before.at >= interval) && now - lastPingAt >= interval) {
            lastPingAt = now
            send(GlagolProtocol.ping())
        }
        val fresh = state ?: throw IOException("No Station state")
        if (SystemClock.elapsedRealtime() - fresh.at > 4000) throw IOException("Station state is stale")
        return transportInfo(fresh) to positionInfo(fresh)
    }

    private fun transportInfo(raw: StationPlaybackState): TransportInfo {
        val a = attempt
        val matching = a != null && raw.matches(a.physical) && raw.at >= a.at
        val mode = when {
            paused && raw.playing == false -> "PAUSED_PLAYBACK"
            !requested -> "STOPPED"
            matching && raw.playing == true -> "PLAYING"
            matching && raw.playing == false -> "PAUSED_PLAYBACK"
            a != null && SystemClock.elapsedRealtime() - a.at < 5000 -> "TRANSITIONING"
            else -> "STOPPED"
        }
        return TransportInfo(mode, "OK")
    }

    override suspend fun position(): PositionInfo = positionInfo(state)

    private fun positionInfo(raw: StationPlaybackState?): PositionInfo {
        val track = loaded
        if (paused && track != null && raw?.playing == false) {
            return PositionInfo(track.first, confirmedPausePosition, track.second.durationMs)
        }
        val s = absolute(raw)
        val a = attempt
        if (requested && track != null && a != null && raw?.matches(a.physical) == true && raw.at >= a.at) {
            return PositionInfo(track.first, s?.position, a.duration)
        }
        return PositionInfo(raw?.id?.takeIf { raw.playing == true }, null, null)
    }

    fun observed(): StationPlaybackState? = absolute(state?.takeIf { SystemClock.elapsedRealtime() - it.at <= 4000 })
    fun ownsObservedStream(): Boolean {
        val a = attempt ?: return false
        val raw = state ?: return false
        return raw.at >= a.at && SystemClock.elapsedRealtime() - raw.at <= 4000 && raw.matches(a.physical)
    }
    override suspend fun volume(): Int? = observed()?.volume
    override suspend fun setVolume(v: Int) = send(GlagolProtocol.volume(v.coerceIn(0, 100) / 100.0))
    override suspend fun sinkMimes(): Set<String> = setOf("audio/wav")
    override suspend fun close() { evidence.clear(); socket.close(); token = null }
}
