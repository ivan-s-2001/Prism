package com.prism.music.data

import android.net.Uri
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Stable, reversible SoundCloud recording ids. Only public track permalinks are accepted. */
object OriginalSources {
    private const val SC = "sc:"
    private val slug = Regex("[A-Za-z0-9_-]+")
    fun isSoundCloud(id: String): Boolean = id.startsWith(SC)

    fun soundCloudId(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host !in setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com")) return null
        val parts = uri.pathSegments
        if (parts.size != 2 || parts.any { !slug.matches(it) }) return null
        return SC + parts.joinToString(":")
    }

    fun soundCloudUrl(id: String): String? {
        if (!isSoundCloud(id)) return null
        val parts = id.removePrefix(SC).split(':')
        if (parts.size != 2 || parts.any { !slug.matches(it) }) return null
        return "https://soundcloud.com/" + parts.joinToString("/")
    }

    /** Blocking: call on IO after Prism's StreamResolver has initialized NewPipe. */
    fun search(query: String, soundCloud: Boolean): List<Song> {
        val service = if (soundCloud) ServiceList.SoundCloud else ServiceList.YouTube
        val info = SearchInfo.getInfo(service.getSearchExtractor(query))
        return info.relatedItems.filterIsInstance<StreamInfoItem>()
            .asSequence().filter {
                it.duration in 1..1800 && (it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.AUDIO_STREAM)
            }.mapNotNull { item ->
                val id = if (soundCloud) soundCloudId(item.url)
                else Uri.parse(item.url).getQueryParameter("v")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                id?.let {
                    Song(
                        id = it, title = item.name, artists = listOf(ArtistRef(item.uploaderName.orEmpty())),
                        durationSec = item.duration.toInt(), thumbnail = item.thumbnails.lastOrNull()?.url,
                        isVideo = !soundCloud,
                    )
                }
            }.distinctBy { it.id }.take(25).toList()
    }
}
