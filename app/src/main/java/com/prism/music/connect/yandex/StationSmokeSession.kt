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
    private val socket = GlagolSocket(station, identity.certificatePem, onState)
    private var server: StationMp3Server? = null

    suspend fun ping() = send(GlagolProtocol.ping())

    suspend fun play(
        phoneAddress: Inet4Address,
        file: File,
        title: String,
        onDownloaded: (Long) -> Unit = {},
    ) {
        // A fresh path prevents reuse of the receiver's old HTTP state.
        stop()
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

    suspend fun volume(value: Double) = send(GlagolProtocol.volume(value))

    suspend fun stop() {
        try { send(GlagolProtocol.stop()) }
        finally { server?.close(); server = null }
    }

    private suspend fun send(payload: JsonObject) {
        socket.send(tokens.tokenFor(identity), payload)
    }

    /** Call stop() before close() to request remote playback termination. */
    override fun close() {
        server?.close()
        server = null
        socket.close()
    }
}
