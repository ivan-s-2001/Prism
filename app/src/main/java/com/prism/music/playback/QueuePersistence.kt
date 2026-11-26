package com.prism.music.playback

import androidx.media3.common.Player
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.serialization.Serializable

@Serializable
internal data class SavedQueue(
    val songs: List<Song>, val index: Int, val positionMs: Long, val title: String,
    // Shuffle (with its order) and repeat, so a restart keeps them (#101: shuffle was off again after Prism restarted).
    val shuffle: Boolean = false, val shuffleOrder: List<Int> = emptyList(), val repeat: Int = Player.REPEAT_MODE_OFF,
    val sourceKind: String? = null, val seedId: String? = null, val originId: String? = null,
    val manualIds: Set<String> = emptySet(), val autoplayStart: Int = -1,
    val vibeAnchors: List<Song> = emptyList(),
    val vibeOrigin: VibeOrigin? = null,
    val waveQuotaStart: Int = 0,
) {
    fun source(): QueueSource = QueueSource(
        QueueKind.restore(sourceKind, seedId != null || vibeAnchors.isNotEmpty() || vibeOrigin != null ||
            com.prism.music.data.foryou.MyWave.isOrigin(originId)),
        if (sourceKind == "RADIO") vibeOrigin?.name ?: title else title,
        seedId, originId, vibeAnchors = vibeAnchors, vibeOrigin = vibeOrigin,
    )
}

/**
 * queue.json's text from what the player holds: each lined-up item's stored song ([songJson], as [toMediaItem] put it in
 * the item; null or unreadable ones are left out), where playback is, and shuffle ([order]: the play order by index,
 * kept only when every item made it into the list) and repeat. 1.8.7: the songs are read and written again here, off the
 * main thread (with 1,100 songs lined up it held the main thread for up to 200 ms after every song change on the A16).
 */
internal fun savedQueueText(
    songJson: List<String?>, index: Int, positionMs: Long, title: String, shuffle: Boolean, order: List<Int>, repeat: Int,
    source: QueueSource? = null, manualIds: Set<String> = emptySet(), autoplayStart: Int = -1,
    waveQuotaStart: Int = 0,
): String {
    val decoded = songJson.map { s -> s?.let { runCatching { InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull() } }
    val songs = decoded.filterNotNull()
    val adjustedAuto = if (autoplayStart in decoded.indices && decoded.drop(autoplayStart).any { it != null })
        decoded.take(autoplayStart).count { it != null } else -1
    val adjustedQuota = if (source?.isMyWave == true) decoded.take(waveQuotaStart.coerceIn(0, decoded.size)).count { it != null } else 0
    val q = SavedQueue(songs, index, positionMs, title, shuffle = shuffle, shuffleOrder = if (shuffle && songs.size == songJson.size) order else emptyList(), repeat = repeat,
        sourceKind = source?.kind?.name, seedId = source?.seedId, originId = source?.originId,
        manualIds = manualIds.intersect(songs.mapTo(hashSetOf()) { it.id }), autoplayStart = adjustedAuto, vibeAnchors = source?.vibeAnchors.orEmpty(), vibeOrigin = source?.vibeOrigin, waveQuotaStart = adjustedQuota)
    return InnerTube.json.encodeToString(SavedQueue.serializer(), q)
}

/** What a resumed Prism plays ([resumptionOf]): the saved queue's songs, from [index] at [positionMs]. */
internal data class Resumption(val songs: List<Song>, val index: Int, val positionMs: Long, val title: String,
    val source: QueueSource = QueueSource(QueueKind.OTHER, title), val manualIds: Set<String> = emptySet(), val autoplayStart: Int = -1, val waveQuotaStart: Int = 0)

/**
 * What a headset button or the system's resume card starts after Prism was closed or killed (#388): the saved queue
 * ([queueText], queue.json) at the kept place ([place], queue-place.json, when newer and its song is still queued), else
 * at the queue file's own place. Null when nothing was saved or it can't be read.
 */
internal fun resumptionOf(queueText: String?, place: QueuePlace?): Resumption? {
    val q = queueText?.let { runCatching { InnerTube.json.decodeFromString(SavedQueue.serializer(), it) }.getOrNull() } ?: return null
    if (q.songs.isEmpty()) return null
    val (index, position) = PlaceKeeper.start(q.songs.map { it.id }, q.index, q.positionMs, place, placeIsNewer = true)
    return Resumption(q.songs, index.coerceIn(0, q.songs.lastIndex), position.coerceAtLeast(0), q.title,
        q.source(), q.manualIds.intersect(q.songs.drop(index.coerceIn(0, q.songs.lastIndex) + 1).mapTo(hashSetOf()) { it.id }),
        q.autoplayStart.takeIf { it in q.songs.indices } ?: -1,
        if (q.source().isMyWave) q.waveQuotaStart.coerceIn(0, q.songs.size) else 0)
}

