package com.prism.music.connect.yandex

import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Only create this from the authenticated Quasar account's device_list response. */
internal class OwnedStationIdentity private constructor(
    val deviceId: String,
    val platform: String,
    val certificatePem: String,
) {
    companion object {
        fun fromDeviceList(response: String, station: YandexStation): OwnedStationIdentity {
            val devices = Json.parseToJsonElement(response).jsonObject["devices"]?.jsonArray
                ?: error("Invalid Yandex device list")
            val matching = devices.map { it.jsonObject }.singleOrNull {
                it["id"]?.jsonPrimitive?.contentOrNull == station.deviceId &&
                    it["platform"]?.jsonPrimitive?.contentOrNull == station.platform
            } ?: error("Station is not in this Yandex account")
            val cert = matching["glagol"]?.jsonObject?.get("security")?.jsonObject
                ?.get("server_certificate")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: error("No Station certificate in account metadata")
            return OwnedStationIdentity(station.deviceId, station.platform, cert)
        }
    }
}

/** Supplies a fresh, short-lived device token through the future account integration. */
internal fun interface StationTokenSource {
    suspend fun tokenFor(station: OwnedStationIdentity): String
}

/** A hardware smoke-test session; no production player integration yet. */
internal class StationSmokeSession(
    private val station: YandexStation,
    private val identity: OwnedStationIdentity,
    private val tokens: StationTokenSource,
    onState: (String) -> Unit = {},
) : Closeable {
    init {
        require(identity.deviceId == station.deviceId && identity.platform == station.platform)
    }
    private val stopControl = StationStopControl()
    @Volatile private var liveState: StationPlaybackState? = null
    private val socket = GlagolSocket(station, identity.certificatePem) { text ->
        stopControl.onState(text)
        StationPlaybackState.parse(text, android.os.SystemClock.elapsedRealtime())?.let { liveState = it }
        onState(text)
    }
    private var server: StationMp3Server? = null
    private var liveProbe: StationLiveProbe? = null
    private var liveHls: StationLiveHls? = null
    private var liveRequestedAt = 0L
    private var liveTitle = "Prism Live"

    suspend fun ping() = send(GlagolProtocol.ping())

    suspend fun play(
        phoneAddress: Inet4Address,
        file: File,
        title: String,
        onDownloaded: (Long) -> Unit = {},
    ) {
        liveProbe?.close()
        liveProbe = null
        // A fresh path prevents reuse of the receiver's old HTTP state.
        server?.close()
        server = null
        send(GlagolProtocol.stop())
        delay(150)
        val stream = StationMp3Server(phoneAddress, station.address, file, onDownloaded)
        server = stream
        try {
            send(GlagolProtocol.playStream(stream.url, title, "Prism test"))
        } catch (e: Throwable) {
            stream.close()
            server = null
            throw e
        }
    }

    suspend fun playLive(phoneAddress: Inet4Address, log: (String) -> Unit) {
        server?.close()
        server = null
        liveProbe?.close()
        liveProbe = null
        send(GlagolProtocol.stop())
        delay(150)
        // A quiet starting level. Signal changes below never send Glagol commands.
        send(GlagolProtocol.volume(0.15))
        val probe = StationLiveProbe(phoneAddress, station.address, log)
        liveProbe = probe
        try {
            send(GlagolProtocol.playStream(probe.url, "Prism Live · test", "Prism"))
        } catch (e: Throwable) {
            probe.close()
            liveProbe = null
            throw e
        }
    }

    suspend fun playMusicLive(phoneAddress: Inet4Address) {
        server?.close()
        server = null
        liveProbe?.close()
        liveProbe = null
        liveHls?.close()
        val stream = StationLiveHls(phoneAddress, station.address, StationAudioTap::read)
        liveHls = stream
        try {
            val ready = kotlinx.coroutines.withTimeoutOrNull(8000L) {
                while (!stream.ready) {
                    stream.failure?.let { error("AAC encoder: $it") }
                    delay(100)
                }
                true
            } == true
            check(ready) { "AAC playlist not ready: ${stream.summary()}" }
            send(GlagolProtocol.stop())
            delay(150)
            send(GlagolProtocol.volume(0.20))
            liveRequestedAt = android.os.SystemClock.elapsedRealtime()
            liveTitle = "Prism Live " + java.util.UUID.randomUUID().toString().take(6)
            android.util.Log.i("PrismLive", "Sending radio_play; ${stream.summary()}; captured=${StationAudioTap.captured}")
            val reply = socket.request(tokens.tokenFor(identity),
                GlagolProtocol.playRadio(stream.url, liveTitle, "Prism"), 1500L)
            android.util.Log.i("PrismLive", "radio_play reply=${if (reply == null) "pending" else if (reply.equals("SUCCESS", true)) "success" else "rejected"}")
            if (reply != null && !reply.equals("SUCCESS", true)) error("Station rejected radio_play")
        } catch (e: Throwable) {
            stream.close()
            liveHls = null
            throw e
        }
    }

    fun liveReceiving(): Boolean = liveHls?.receiving == true

    fun liveConfirmed(): Boolean {
        val stream = liveHls ?: return false
        val state = liveState
        val matched = state != null && state.at >= liveRequestedAt && state.type != "bluetooth" &&
            state.playing == true && (state.matches(stream.url) || state.title == liveTitle)
        return matched && stream.receiving
    }

    fun liveStatus(): String {
        val state = liveState
        return "${liveHls?.summary() ?: "no HLS"}; captured=${StationAudioTap.captured}; " +
            "stateType=${state?.type}; playing=${state?.playing}; matched=${liveConfirmed()}"
    }

    fun liveSignal(signal: StationLiveProbe.Signal) {
        (liveProbe ?: error("Start Live probe first")).select(signal)
    }

    fun liveSummary(): String = liveProbe?.summary() ?: "Live probe not running"

    suspend fun volume(value: Double) = send(GlagolProtocol.volume(value))

    suspend fun stop() {
        liveHls?.close()
        liveHls = null
        liveProbe?.close()
        liveProbe = null
        server?.close()
        server = null
        stopControl.stop(socket, tokens.tokenFor(identity))
    }

    private suspend fun send(payload: JsonObject) {
        socket.send(tokens.tokenFor(identity), payload)
    }

    /** Call stop() before close() to request remote playback termination. */
    override fun close() {
        liveHls?.close()
        liveHls = null
        liveProbe?.close()
        liveProbe = null
        server?.close()
        server = null
        socket.close()
    }
}
