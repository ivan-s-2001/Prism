package com.prism.music.connect.yandex

import com.prism.music.connect.BytesBody
import com.prism.music.connect.MediaServer
import org.junit.Assert.*
import org.junit.Test

class StationMediaSessionTest {
    @Test fun staleDecoderCannotPublishIntoNewSession() {
        val server = MediaServer(0)
        val old = server.token
        val body = BytesBody("audio/mpeg", byteArrayOf(1, 2, 3))
        server.publishForSession(old, "old.mp3", body, "127.0.0.1")
        assertTrue(server.has("old.mp3"))
        server.newSession()
        assertFalse(server.has("old.mp3"))
        val current = server.token
        assertNotEquals(old, current)
        try {
            server.publishForSession(old, "stale.mp3", body, "127.0.0.1")
            fail("A decoder from an expired session must not publish")
        } catch (_: IllegalStateException) {
            assertFalse(server.has("stale.mp3"))
        }
        server.publishForSession(current, "new.mp3", body, "127.0.0.1")
        assertTrue(server.has("new.mp3"))
    }
}
