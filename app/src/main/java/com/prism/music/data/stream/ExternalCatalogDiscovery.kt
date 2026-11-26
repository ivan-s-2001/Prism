package com.prism.music.data.stream

import com.prism.music.data.ExplicitFilter
import com.prism.music.data.SongVersions
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.ExplicitContent
import com.prism.music.data.spotify.SpotifyApi
import com.prism.music.data.spotify.SpotifyParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Catalog-only matches. These links must never be treated as audio stream URLs or saved as playback pins. */
internal enum class CatalogService(val label: String, val hosts: Set<String>) {
    SPOTIFY("Spotify", setOf("open.spotify.com")),
    APPLE("Apple Music", setOf("music.apple.com", "itunes.apple.com")),
    DEEZER("Deezer", setOf("deezer.com", "www.deezer.com"));

    fun accepts(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host in hosts && uri.userInfo == null &&
            uri.port == -1 && uri.path.orEmpty().isNotBlank()
    }
}

internal data class CatalogMatch(val service: CatalogService, val song: Song, val url: String, val market: String? = null)
internal data class CatalogOutcome(val service: CatalogService, val matches: List<CatalogMatch> = emptyList(), val failed: Boolean = false)

internal class ExternalCatalogDiscovery(private val spotify: SpotifyApi) {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun get(url: HttpUrl): JsonObject =
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Catalog HTTP " + r.code)
            val source = r.body.source()
            if (source.request(2_000_001)) throw IOException("Catalog response too large")
            Json.parseToJsonElement(source.readUtf8()).jsonObject
        }

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.arr(key: String) = (get(key) as? JsonArray).orEmpty()

    fun search(target: Song, mode: ExplicitContent): Flow<CatalogOutcome> = channelFlow {
        if (target.isLocal || target.isEpisode || target.isLive || target.isUpload || target.durationSec <= 0) return@channelFlow
        CatalogService.entries.forEach { service ->
            launch {
                val outcome = try {
                    val matches = withContext(Dispatchers.IO) {
                        when (service) {
                            CatalogService.SPOTIFY -> spotify(target)
                            CatalogService.APPLE -> apple(target)
                            CatalogService.DEEZER -> deezer(target)
                        }
                    }.filter { compatible(target, it, mode) }
                        .distinctBy { it.url }.take(8)
                    CatalogOutcome(service, matches)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    CatalogOutcome(service, failed = true)
                }
                send(outcome)
            }
        }
    }

    private suspend fun spotify(target: Song): List<CatalogMatch> {
        val q = "${target.primaryArtist} ${target.title}"
        val body = spotify.get("/search", mapOf("type" to "track", "q" to q, "limit" to "10"))
        return SpotifyParse.searchTracks(body).mapNotNull { t ->
            val id = t.uri.removePrefix("spotify:track:").takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) } ?: return@mapNotNull null
            val sec = (t.durationMs / 1000).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return@mapNotNull null
            CatalogMatch(CatalogService.SPOTIFY,
                Song("catalog:spotify:$id", t.title, t.artists.map { ArtistRef(it) }, durationSec = sec, explicit = t.explicit),
                "https://open.spotify.com/track/$id")
        }
    }

    private fun apple(target: Song): List<CatalogMatch> {
        val term = "${target.primaryArtist} ${target.title}"
        val results = mutableListOf<CatalogMatch>()
        var answered = false
        for (market in listOf("US", "KZ", "DE")) {
            val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term).addQueryParameter("country", market)
                .addQueryParameter("media", "music").addQueryParameter("entity", "song")
                .addQueryParameter("limit", "20").build()
            val response = try { get(url) } catch (_: IOException) { continue }
            if (response["results"] !is JsonArray) continue
            answered = true
            response.arr("results").mapNotNullTo(results) { (it as? JsonObject)?.let { obj -> appleTrack(obj, market) } }
        }
        if (!answered) throw IOException("Apple catalog unavailable")
        return results
    }

    private fun deezer(target: Song): List<CatalogMatch> {
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${target.primaryArtist} ${target.title}").addQueryParameter("limit", "25").build()
        val response = get(url)
        if (response["error"] != null || response["data"] !is JsonArray) throw IOException("Deezer catalog unavailable")
        return response.arr("data").mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: return@mapNotNull null
            val title = o.str("title") ?: return@mapNotNull null
            val artist = o.obj("artist")?.str("name") ?: return@mapNotNull null
            val duration = o.str("duration")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val explicit = o.str("explicit_lyrics") == "true"
            CatalogMatch(CatalogService.DEEZER, Song("catalog:deezer:$id", title,
                listOf(ArtistRef(artist)), durationSec = duration, explicit = explicit),
                "https://www.deezer.com/track/$id")
        }
    }

    companion object {
        internal fun appleTrack(o: JsonObject, market: String): CatalogMatch? {
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            if (str("kind") != "song") return null
            val id = str("trackId")?.takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: return null
            val title = str("trackName") ?: return null
            val artist = str("artistName") ?: return null
            val sec = str("trackTimeMillis")?.toLongOrNull()?.div(1000)
                ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
            val url = str("trackViewUrl")?.takeIf(CatalogService.APPLE::accepts) ?: return null
            return CatalogMatch(CatalogService.APPLE, Song("catalog:apple:$id", title, listOf(ArtistRef(artist)),
                durationSec = sec, explicit = str("trackExplicitness") == "explicit"), url, market)
        }

        internal fun compatible(target: Song, hit: CatalogMatch, mode: ExplicitContent): Boolean =
            hit.service.accepts(hit.url) && SongVersions.compatibleRecording(target, hit.song) &&
                ExplicitFilter.shows(mode, hit.song)
    }
}
