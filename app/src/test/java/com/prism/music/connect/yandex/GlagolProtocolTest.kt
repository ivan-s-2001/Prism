package com.prism.music.connect.yandex

import java.io.ByteArrayInputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GlagolProtocolTest {
    @Test fun envelopePreservesPayload() {
        val json = Json.parseToJsonElement(
            GlagolProtocol.envelope("test-token", GlagolProtocol.stop(), "test-id", 42)
        ).jsonObject
        assertEquals("test-token", json["conversationToken"]!!.jsonPrimitive.content)
        assertEquals("test-id", json["id"]!!.jsonPrimitive.content)
        assertEquals("42", json["sentTime"]!!.jsonPrimitive.content)
        assertEquals(GlagolProtocol.stop(), json["payload"])
    }

    @Test fun audioPlayEncodesUnicodeAndMultiByteLengths() {
        val title = "Музыка ".repeat(30)
        val url = "http://192.168.1.10:8888/test.mp3?track=42"
        val payload = GlagolProtocol.playStream(url, title, "Исполнитель")
        assertEquals("externalCommandBypass", payload["command"]!!.jsonPrimitive.content)
        val input = ByteArrayInputStream(Base64.getDecoder().decode(payload["data"]!!.jsonPrimitive.content))
        assertEquals(10, input.read())
        assertEquals("audio_play", readString(input))
        assertEquals(18, input.read())
        val params = Json.parseToJsonElement(readString(input)).jsonObject
        val stream = params["stream"]!!.jsonObject
        val metadata = params["metadata"]!!.jsonObject
        assertEquals(url, stream["url"]!!.jsonPrimitive.content)
        assertEquals("MP3", stream["format"]!!.jsonPrimitive.content)
        assertEquals("Track", stream["type"]!!.jsonPrimitive.content)
        assertEquals(title, metadata["title"]!!.jsonPrimitive.content)
        assertEquals("Исполнитель", metadata["subtitle"]!!.jsonPrimitive.content)
        assertEquals(-1, input.read())
    }

    @Test(expected = IllegalArgumentException::class)
    fun httpsStreamIsRejected() {
        GlagolProtocol.playStream("https://example.com/test.mp3", "", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun volumeOutsideRangeIsRejected() {
        GlagolProtocol.volume(1.1)
    }

    private fun readString(input: ByteArrayInputStream): String {
        var size = 0
        var shift = 0
        do {
            val next = input.read()
            check(next >= 0 && shift < 32)
            size = size or ((next and 127) shl shift)
            shift += 7
        } while (next and 128 != 0)
        val bytes = ByteArray(size)
        assertEquals(size, input.read(bytes))
        return bytes.toString(Charsets.UTF_8)
    }
}
