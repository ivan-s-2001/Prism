package com.prism.music.connect.yandex

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Experimental local Yandex Station transport message encoder.
 * Updated directive reference: https://github.com/AlexxIT/YandexStation/pull/802
 * No network calls, credentials, or TLS policy are handled here.
 */
internal object GlagolProtocol {
    const val SERVICE_TYPE = "_yandexio._tcp."
    const val DEFAULT_PORT = 1961

    fun envelope(
        conversationToken: String,
        payload: JsonObject,
        id: String = UUID.randomUUID().toString(),
        sentTime: Long = System.currentTimeMillis(),
    ): String {
        require(conversationToken.isNotBlank())
        return buildJsonObject {
            put("conversationToken", conversationToken)
            put("id", id)
            put("sentTime", sentTime)
            put("payload", payload)
        }.toString()
    }

    fun ping(): JsonObject = buildJsonObject { put("command", "ping") }

    fun stop(): JsonObject = buildJsonObject { put("command", "stop") }

    fun volume(value: Double): JsonObject {
        require(value.isFinite() && value in 0.0..1.0)
        return buildJsonObject {
            put("command", "setVolume")
            put("volume", value)
        }
    }

    /** The Station pulls a fresh HTTP URL from a phone-hosted server. */
    fun playStream(url: String, title: String, artist: String): JsonObject {
        val uri = URI(url)
        require(uri.scheme.equals("http", ignoreCase = true) && uri.host != null)
        require(uri.userInfo == null && uri.fragment == null)
        val params = buildJsonObject {
            put("stream", buildJsonObject {
                put("url", url)
                // Stable identity echoed in playerState.id by current Station firmware.
                put("id", url)
                // Firmware detects the actual codec; MP3 is the enum for ordinary file streams.
                put("format", "MP3")
                put("type", "Track")
            })
            put("metadata", buildJsonObject {
                if (title.isNotBlank()) put("title", title)
                if (artist.isNotBlank()) put("subtitle", artist)
            })
        }.toString()
        val message = ByteArrayOutputStream().apply {
            stringField(1, "audio_play")
            stringField(2, params)
        }.toByteArray()
        return buildJsonObject {
            put("command", "externalCommandBypass")
            put("data", Base64.getEncoder().encodeToString(message))
        }
    }

    private fun ByteArrayOutputStream.stringField(number: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        varint((number shl 3) or 2)
        varint(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.varint(value: Int) {
        var remaining = value
        while (remaining >= 128) {
            write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        write(remaining)
    }
}
