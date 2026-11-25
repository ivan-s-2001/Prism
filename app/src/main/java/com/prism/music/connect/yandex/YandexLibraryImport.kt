package com.prism.music.connect.yandex

import com.prism.music.data.imports.LibraryPlaylist
import com.prism.music.data.imports.LibraryTrack
import com.prism.music.data.imports.ParsedLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Metadata only. No download of audio, no changes to the Yandex account. */
internal class YandexLibraryImport(private val token: suspend (String?) -> String) {
    data class Preview(val library: ParsedLibrary, val missing: Int)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val root = "https://api.music.yandex.net".toHttpUrl()
    private class Unauthorized : IOException("Yandex Music authorization rejected")

    private suspend fun get(path: List<String>, ids: List<String>? = null): JsonElement {
        val first = token(null)
        return try { request(path, ids, first) } catch (_: Unauthorized) { request(path, ids, token(first)) }
    }

    private suspend fun request(path: List<String>, ids: List<String>?, access: String): JsonElement = withContext(Dispatchers.IO) {
        val url = root.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            ids?.let { addQueryParameter("track-ids", it.joinToString(",")) }
        }.build()
        val req = Request.Builder().url(url).header("Authorization", "OAuth $access").get().build()
        client.newCall(req).execute().use { response ->
            if (response.code == 401) throw Unauthorized()
            if (!response.isSuccessful) throw IOException("Yandex Music HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("Empty response")
            if (source.request(16_777_217L)) throw IOException("Library response exceeds limit")
            val result = Json.parseToJsonElement(source.readUtf8()) as? JsonObject ?: throw IOException("Invalid response")
            result["result"]?.takeUnless { it is JsonNull } ?: throw IOException("Missing library data")
        }
    }

    suspend fun read(liked: Boolean, playlists: Boolean, progress: (String) -> Unit): Preview {
        val account = get(listOf("account", "status")).obj()?.get("account").obj()
        val uid = account?.str("uid")?.takeIf { it.matches(Regex("[0-9]{1,24}")) } ?: throw IOException("Account ID unavailable")
        val base = listOf("users", uid)
        var missing = 0
        val likes = mutableListOf<LibraryTrack>()
        val lists = mutableListOf<LibraryPlaylist>()
        if (liked) {
            val refs = get(base + listOf("likes", "tracks")).obj()?.get("library").obj()?.get("tracks").arr()
            require(refs.size <= 30000) { "Library exceeds 30000 tracks" }
            val ids = refs.mapNotNull { it.obj()?.str("id")?.takeIf { id -> id.matches(Regex("[0-9]{1,24}")) } }
            missing += refs.size - ids.size
            for ((i, chunk) in ids.chunked(80).withIndex()) {
                coroutineContext.ensureActive()
                progress("Liked: ${minOf(i * 80, ids.size)} / ${ids.size}")
                val tracks = get(listOf("tracks"), chunk).arr().mapNotNull { track(it.obj()) }.associateBy { it.sourceId }
                for (id in chunk) tracks["yandex:$id"]?.let { likes += it } ?: run { missing++ }
            }
        }
        if (playlists) {
            val entries = get(base + listOf("playlists", "list")).arr()
            require(entries.size <= 500) { "Library exceeds 500 playlists" }
            for ((i, entry) in entries.withIndex()) {
                coroutineContext.ensureActive()
                progress("Playlists: ${i + 1} / ${entries.size}")
                val kind = entry.obj()?.str("kind")?.takeIf { it.matches(Regex("[0-9]{1,18}")) } ?: throw IOException("Invalid playlist ID")
                if (kind == "3") continue // Favorites are imported independently.
                val full = get(base + listOf("playlists", kind)).obj() ?: throw IOException("Invalid playlist")
                val refs = full["tracks"].arr()
                val expected = full.str("trackCount")?.toIntOrNull()
                if (expected != null && expected > refs.size) throw IOException("Incomplete playlist")
                val tracks = refs.mapNotNull { track(it.obj()?.get("track").obj()) }
                missing += refs.size - tracks.size
                val name = full.str("title")?.takeIf { it.isNotBlank() } ?: "Playlist $kind"
                lists += LibraryPlaylist("$name · Яндекс Музыка", tracks, "yandex:$uid:$kind")
            }
        }
        token(null) // Fail if the account changed during reading.
        return Preview(ParsedLibrary(liked = likes.distinctBy { it.key }, playlists = lists), missing)
    }

    companion object {
        private fun JsonElement?.obj() = this as? JsonObject
        private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: throw IOException("Incomplete library response")
        internal fun track(obj: JsonObject?): LibraryTrack? {
            val id = obj?.str("id") ?: return null
            val title = obj.str("title")?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val artists = (obj["artists"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.str("name") }.joinToString(", ")
            if (artists.isBlank()) return null
            val album = (obj["albums"] as? JsonArray)?.firstOrNull().obj()?.str("title")
            return LibraryTrack(title, artists, album, "yandex:$id")
        }
    }
}
