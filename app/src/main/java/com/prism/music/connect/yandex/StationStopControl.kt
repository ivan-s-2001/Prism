package com.prism.music.connect.yandex

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only an explicit fresh state.playing=false confirms a stop. */
internal class StationStopControl {
    private data class Reading(val playing: Boolean)
    private val state = AtomicReference<Reading?>()

    fun onState(text: String) {
        val playing = StationPlaybackState.parse(text, 0)?.playing ?: return
        state.set(Reading(playing))
    }

    suspend fun stop(socket: GlagolSocket, token: String, allowTextFallback: Boolean = true) {
        suspend fun attempt(payload: JsonObject): Boolean {
            val before = state.get()
            val result = socket.request(token, payload, timeoutMs = 600L)
            if (result != null && !result.equals("SUCCESS", ignoreCase = true)) return false
            socket.send(token, GlagolProtocol.ping())
            return withTimeoutOrNull(2500) {
                var attempts = 0
                while (true) {
                    val current = state.get()
                    if (current !== before && current?.playing == false) break
                    delay(100)
                    if (++attempts % 5 == 0) socket.send(token, GlagolProtocol.ping())
                }
                true
            } == true
        }
        if (attempt(GlagolProtocol.stop())) return
        // Explicit user stop only, never used for automatic track changes.
        if (allowTextFallback && attempt(buildJsonObject {
            put("command", "sendText")
            put("text", "останови музыку")
        })) return
        throw IOException("Station stop not confirmed")
    }
}
