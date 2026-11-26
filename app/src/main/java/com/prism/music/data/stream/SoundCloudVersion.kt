package com.prism.music.data.stream

import com.prism.music.data.SongVersions
import com.prism.music.data.imports.ImportKeys
import com.prism.music.data.model.Song
import java.net.URI
import kotlin.math.abs

/** A public SoundCloud permalink, namespaced so it can never collide with a YouTube recording. */
internal object SoundCloudVersion {
    private val path = Regex("[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+")
    private const val PREFIX = "sc:"
    fun id(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host !in setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com") ||
            uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) return null
        val p = uri.path.removePrefix("/").removeSuffix("/")
        return p.takeIf { path.matches(it) }?.replace('/', ':')?.let { PREFIX + it }
    }

    private const val HLS_PREFIX = "sc-hls:"
    fun isHls(id: String): Boolean = id.startsWith(HLS_PREFIX) && url(id) != null
    fun hlsId(recordingId: String): String? = url(recordingId)?.let { id(it)?.replace(PREFIX, HLS_PREFIX) }

    fun url(id: String): String? = id.takeIf { it.startsWith(PREFIX) || it.startsWith(HLS_PREFIX) }
        ?.removePrefix(HLS_PREFIX)?.removePrefix(PREFIX)?.replace(':', '/')?.takeIf { path.matches(it) }
        ?.let { "https://soundcloud.com/$it" }

    fun matches(song: Song, title: String, uploader: String, seconds: Long): Boolean {
        if (song.durationSec <= 0 || seconds <= 0 || abs(seconds - song.durationSec) > 5) return false
        val want = SongVersions.bare(song.title)
        if (want.isEmpty()) return false
        val split = title.split(Regex(" +[-–—] +"), limit = 2)
        val candidateTitle = if (split.size == 2 && ImportKeys.norm(split[0]) == ImportKeys.norm(song.primaryArtist)) split[1] else title
        val marks = ImportKeys.versionMarks(candidateTitle)
        if (marks != ImportKeys.versionMarks(song.title) || ImportKeys.cleanRelease(candidateTitle)) return false
        if (SongVersions.bare(candidateTitle) != want) return false
        val artist = ImportKeys.norm(song.primaryArtist)
        return artist.isNotBlank() && (ImportKeys.norm(uploader) == artist ||
            (split.size == 2 && ImportKeys.norm(split[0]) == artist))
    }
}
