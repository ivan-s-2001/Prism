package com.prism.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Descending contiguous ranges keep earlier indices valid as later ranges are removed. */
internal fun removalRuns(from: Int, until: Int, remove: (Int) -> Boolean): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var end = until
    var index = until - 1
    while (index >= from) {
        if (!remove(index)) {
            if (index + 1 < end) runs.add((index + 1) until end)
            end = index
        }
        index--
    }
    if (from < end) runs.add(from until end)
    return runs
}

internal fun Player.removeMatchingRuns(from: Int, until: Int, remove: (Int, MediaItem) -> Boolean) {
    val start = from.coerceIn(0, mediaItemCount)
    val end = until.coerceIn(start, mediaItemCount)
    // Snapshot the predicates before mutating the timeline; listener callbacks cannot change these indices.
    val runs = removalRuns(start, end) { index -> remove(index, getMediaItemAt(index)) }
    for (run in runs) removeMediaItems(run.first, run.last + 1)
}
