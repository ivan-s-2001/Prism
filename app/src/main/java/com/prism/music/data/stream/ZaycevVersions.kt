package com.prism.music.data.stream

import com.prism.music.data.SongVersions
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Experimental adapter for public, explicitly exposed audio on Zaycev track pages.
 * No private API signatures, hidden playback endpoints, or paid streams are guessed.
 */
internal class ZaycevVersions {
    data class Recording(val song: Song, val audioUrl: String)
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun get(url: String): Document {
        return http.newCall(Request.Builder().url(url).header("Accept", "text/html").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Zaycev HTTP " + r.code)
            val source = r.body.source()
            if (source.request(3_000_001)) throw IOException("Zaycev page too large")
            Jsoup.parse(source.readUtf8(), url)
        }
    }

    fun search(song: Song): List<Song> {
        if (song.isLocal || song.isLive || song.isEpisode || song.isUpload || song.artists.size != 1) return emptyList()
        val query = "${song.primaryArtist} ${song.title}"
        // Legacy public search form; fail explicitly if its route is no longer served.
        val url = "https://zaycev.net/search.html".toHttpUrl().newBuilder()
            .addQueryParameter("query_search", query).build().toString()
        val doc = get(url)
        val hits = doc.select("a[href]").mapNotNull { a ->
            if (SongVersions.recordingTitle(a.text()) != SongVersions.recordingTitle(song.title)) null
            else id(a.absUrl("href"))
        }.distinct().take(6)
        if (hits.isEmpty() && !doc.text().contains("не найден", ignoreCase = true) &&
            !doc.text().contains("ничего не", ignoreCase = true)) throw IOException("Zaycev search layout not recognized")
        return hits.mapNotNull { key -> runCatching { track(key) }.getOrNull()?.song }
            .filter { SongVersions.compatibleRecording(song, it) }
    }

    fun track(key: String): Recording? = url(key)?.let { parse(get(it), key) }

    companion object {
        private val page = Regex("/pages/[0-9]{1,12}/[0-9]{1,12}\\.shtml")
        private val duration = Regex("Длительность\\s*:\\s*(\\d{1,3}):(\\d{2})", RegexOption.IGNORE_CASE)
        fun id(url: String): String? {
            val u = runCatching { URI(url) }.getOrNull() ?: return null
            val p = u.path ?: return null
            if (u.scheme != "https" || u.host !in setOf("zaycev.net", "www.zaycev.net") ||
                u.userInfo != null || u.port != -1 || !page.matches(p)) return null
            return "zc:" + p.removePrefix("/pages/").removeSuffix(".shtml").replace('/', ':')
        }
        fun url(id: String): String? {
            if (!id.startsWith("zc:")) return null
            val p = id.removePrefix("zc:").split(':')
            if (p.size != 2 || p.any { !it.matches(Regex("[0-9]{1,12}")) }) return null
            return "https://zaycev.net/pages/${p[0]}/${p[1]}.shtml"
        }
        private fun publicAudio(url: String): Boolean {
            val u = runCatching { URI(url) }.getOrNull() ?: return false
            val host = u.host ?: return false
            return u.scheme == "https" && u.userInfo == null && u.port == -1 &&
                (host == "zaycev.net" || host.endsWith(".zaycev.net")) &&
                u.path.orEmpty().endsWith(".mp3", ignoreCase = true)
        }
        internal fun parse(doc: Document, id: String): Recording? {
            if (url(id) == null) return null
            val artist = doc.select("h2").firstOrNull { it.text().startsWith("Исполнитель:", ignoreCase = true) }
                ?.selectFirst("a")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val heading = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val prefix = "$artist - "
            if (!heading.startsWith(prefix, ignoreCase = true)) return null
            val title = heading.substring(prefix.length).trim().takeIf { it.isNotBlank() } ?: return null
            val m = duration.find(doc.text()) ?: return null
            val sec = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            if (sec <= 0 || m.groupValues[2].toInt() >= 60) return null
            // Only a standard audio element on a dedicated track page; never extract a preview,
            // unrelated list item, or inferred/obfuscated private CDN endpoint.
            val audios = doc.select("audio")
            if (audios.size != 1) return null
            val audio = audios.first() ?: return null
            val candidates = (listOf(audio.absUrl("src")) + audio.select("source[src]").map { it.absUrl("src") })
                .filter(::publicAudio).distinct()
            val stream = candidates.singleOrNull() ?: return null
            if (stream.contains("preview", ignoreCase = true) || stream.contains("sample", ignoreCase = true)) return null
            return Recording(Song(id, title, listOf(ArtistRef(artist)), durationSec = sec), stream)
        }
    }
}
