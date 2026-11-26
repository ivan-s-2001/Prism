package com.prism.music.data.stream

import com.prism.music.data.SongVersions
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
        if (song.artists.size != 1 || song.durationSec <= 0 || seconds <= 0 ||
            abs(seconds - song.durationSec) > 3 || seconds > Int.MAX_VALUE) return false
        val artist = song.primaryArtist.trim()
        if (artist.isBlank() || !artist.equals(uploader.trim(), ignoreCase = true)) return false
        val split = title.split(Regex(" +[-–—] +"), limit = 2)
        val candidateTitle = if (split.size == 2 && artist.equals(split[0].trim(), ignoreCase = true)) split[1] else title
        // The uploader must be the artist; text in the upload title cannot establish artist identity.
        return SongVersions.compatibleRecording(song, Song(
            "soundcloud-candidate", candidateTitle,
            listOf(com.prism.music.data.model.ArtistRef(uploader)), durationSec = seconds.toInt(),
        ))
    }
}
