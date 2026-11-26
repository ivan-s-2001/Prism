package com.prism.music
import com.prism.music.data.stream.AudiusVersions
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AudiusVersionsTest {
    @Test fun publicOnly() {
        val p = AudiusVersions()
        val raw = buildJsonObject {
            put("id", "abc123"); put("title", "Song"); put("duration", 200)
            put("is_streamable", true); put("is_stream_gated", false)
            putJsonObject("user") { put("name", "Artist") }
        }
        assertEquals("au:abc123", p.publicTrack(raw)?.id)
        assertNull(p.publicTrack(JsonObject(raw + ("is_stream_gated" to JsonPrimitive(true)))))
        assertNull(p.publicTrack(JsonObject(raw + ("is_streamable" to JsonPrimitive(false)))))
        assertNull(AudiusVersions.url("au:../track"))
    }
}
