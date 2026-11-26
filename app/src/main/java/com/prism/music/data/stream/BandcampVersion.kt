package com.prism.music.data.stream

import java.net.URI

/** Public Bandcamp track pages, not arbitrary remote files or paid-only album downloads. */
internal object BandcampVersion {
    private val host = Regex("[a-z0-9-]+\\.bandcamp\\.com")
    private val track = Regex("/track/[a-zA-Z0-9_-]+")
    fun albumUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || !host.matches(uri.host ?: "") ||
            !Regex("/album/[a-zA-Z0-9_-]+").matches(uri.path ?: "") ||
            uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return url
    }
    fun id(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val h = uri.host ?: return null
        val p = uri.path ?: return null
        if (uri.scheme != "https" || !host.matches(h) || !track.matches(p) ||
            uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return "bc:" + h.substringBefore(".bandcamp.com") + ":" + p.substringAfter("/track/")
    }
    fun url(id: String): String? {
        if (!id.startsWith("bc:")) return null
        val parts = id.removePrefix("bc:").split(':')
        if (parts.size != 2) return null
        val candidate = "https://${parts[0]}.bandcamp.com/track/${parts[1]}"
        return candidate.takeIf { id(it) == id }
    }
}

internal object PublicRecording {
    fun url(id: String): String? = SoundCloudVersion.url(id) ?: BandcampVersion.url(id) ?: AudiusVersions.url(id)
}
