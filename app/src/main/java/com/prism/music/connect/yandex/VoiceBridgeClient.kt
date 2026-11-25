package com.prism.music.connect.yandex

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal object VoiceBridgeClient {
    private class Config(val url: HttpUrl, val token: String)
    @Volatile private var config: Config? = null

    fun configure(base: String, token: String) {
        val url = base.trim().toHttpUrlOrNull() ?: error("Invalid HTTPS URL")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { "HTTPS URL without credentials required" }
        require(token.matches(Regex("[a-f0-9]{64}"))) { "Expected 64-character phone token" }
        config = Config(url.newBuilder().addPathSegments("v1/pull").build(), token)
    }

    fun clear() { config = null }
    private val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(5, TimeUnit.SECONDS).build()

    data class Command(val action: String, val value: Int?, val query: String?)

    /** A single consumer owned by PlaybackService; no polling while a Station isn't active. */
    suspend fun run(active: () -> Boolean, execute: suspend (Command) -> Unit) {
        while (currentCoroutineContext().isActive) {
            val selected = config
            if (selected == null || !active()) { delay(1500); continue }
            try {
                val command = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(selected.url)
                        .header("Authorization", "Bearer ${selected.token}").get().build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Voice relay HTTP ${response.code}")
                        val source = response.body?.source() ?: throw IOException("Empty voice response")
                        if (source.request(8193L)) throw IOException("Voice response too large")
                        val obj = Json.parseToJsonElement(source.readUtf8()) as? JsonObject
                        val cmd = obj?.get("command") as? JsonObject
                        if (cmd == null) null else {
                            val action = (cmd["action"] as? JsonPrimitive)?.contentOrNull
                                ?: throw IOException("Invalid voice command")
                            Command(action, (cmd["value"] as? JsonPrimitive)?.intOrNull,
                                (cmd["query"] as? JsonPrimitive)?.contentOrNull)
                        }
                    }
                }
                if (command != null && config === selected && active()) execute(command)
                delay(1200)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                android.util.Log.w("PrismVoice", "Relay or command failed")
                delay(4000)
            }
        }
    }
}
