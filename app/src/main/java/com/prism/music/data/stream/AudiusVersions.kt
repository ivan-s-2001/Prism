package com.prism.music.data.stream

import com.prism.music.data.SongVersions
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public, ungated tracks through Audius's documented read-only REST API. */
internal class AudiusVersions {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private fun get(path: String, query: String? = null): JsonElement? {
        val url = ("https://api.audius.co/v1/" + path).toHttpUrl().newBuilder()
            .addQueryParameter("app_name", "Prism")
            .apply { if (query != null) addQueryParameter("query", query).addQueryParameter("limit", "25") }
            .build()
        return http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) throw IOException("Audius HTTP " + it.code)
            val source = it.body.source()
            if (source.request(2_000_001)) throw IOException("Audius response too large")
            (Json.parseToJsonElement(source.readUtf8()) as? JsonObject)?.get("data")
        }
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull

    internal fun publicTrack(raw: JsonObject): Song? {
        if (raw.flag("is_streamable") != true || raw.flag("is_stream_gated") == true ||
            raw.flag("is_available") == false || raw.flag("is_unlisted") == true ||
            raw.flag("is_delete") == true || raw.flag("is_scheduled_release") == true) return null
        val conditions = raw["stream_conditions"]
        if (conditions != null && conditions != JsonNull && conditions != JsonObject(emptyMap())) return null
        val id = raw.string("id")?.takeIf { valid.matches(it) } ?: return null
        val title = raw.string("title")?.takeIf { it.isNotBlank() } ?: return null
        val user = raw["user"] as? JsonObject ?: return null
        val name = user.string("name")?.takeIf { it.isNotBlank() } ?: return null
        val duration = raw.string("duration")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val art = (raw["artwork"] as? JsonObject)?.string("480x480")?.takeIf { it.startsWith("https://") }
        return Song("au:$id", title, listOf(ArtistRef(name)), durationSec = duration, thumbnail = art)
    }

    fun search(song: Song): List<Song> {
        if (song.isLocal || song.isUpload || song.isEpisode || song.isLive || song.artists.size != 1) return emptyList()
        return SongVersions.searchQueries(song, false).take(2).flatMap { q ->
            (get("tracks/search", q) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::publicTrack) }
        }.distinctBy { it.id }.filter { SongVersions.compatibleRecording(song, it) }.take(6)
    }

    fun track(id: String): Song? {
        val key = id.removePrefix("au:").takeIf { id.startsWith("au:") && valid.matches(it) } ?: return null
        return (get("tracks/$key") as? JsonObject)?.let(::publicTrack)
    }

    companion object {
        private val valid = Regex("[A-Za-z0-9]{1,32}")
        fun url(id: String): String? {
            val key = id.removePrefix("au:").takeIf { id.startsWith("au:") && valid.matches(it) } ?: return null
            return "https://api.audius.co/v1/tracks/$key/stream?app_name=Prism"
        }
    }
}
