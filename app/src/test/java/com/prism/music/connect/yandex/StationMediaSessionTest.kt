package com.prism.music.connect.yandex

import com.prism.music.connect.BytesBody
import com.prism.music.connect.MediaServer
import org.junit.Assert.*
import org.junit.Test

class StationMediaSessionTest {
    @Test fun onlyExpectedPeerGetCountsAsDelivery() {
        val server = MediaServer(0)
        assertTrue(server.start())
        try {
            val body = BytesBody("audio/mpeg", byteArrayOf(1, 2, 3))
            val own = server.publishForSession(server.token, "own.mp3", body, "127.0.0.1", "127.0.0.1")
            val other = server.publishForSession(server.token, "other.mp3", body, "127.0.0.1", "127.0.0.2")
            fun request(url: String, method: String) {
                val connection = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000
                    assertEquals(200, connection.responseCode)
                    connection.inputStream.use { it.readBytes() }
                } finally { connection.disconnect() }
            }
            request(own, "HEAD")
            assertEquals(0L, server.deliverySequence(own))
            request(other, "GET")
            assertEquals(0L, server.deliverySequence(other))
            request(own, "GET")
            val deadline = System.nanoTime() + 1_000_000_000L
            while (server.deliverySequence(own) == 0L && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(server.deliverySequence(own) > 0L)
            server.newSession()
            assertEquals(0L, server.deliverySequence(own))
        } finally { server.stop() }
    }

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
