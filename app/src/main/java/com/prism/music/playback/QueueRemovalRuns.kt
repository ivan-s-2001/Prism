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

internal fun Player.removeMatchingRuns(from: Int, until: Int, remove: (Int, MediaItem) -> Boolean): List<IntRange> {
    val start = from.coerceIn(0, mediaItemCount)
    val end = until.coerceIn(start, mediaItemCount)
    // Snapshot the predicates before mutating the timeline; listener callbacks cannot change these indices.
    val runs = removalRuns(start, end) { index -> remove(index, getMediaItemAt(index)) }
    for (run in runs) removeMediaItems(run.first, run.last + 1)
    return runs
}

/** Keep a queue boundary attached to its original position after disjoint ranges are removed. -1 means unset. */
internal fun boundaryAfterRemoval(boundary: Int, runs: List<IntRange>): Int {
    if (boundary < 0) return boundary
    val removedBefore = runs.sumOf { range ->
        (minOf(boundary, range.last + 1) - range.first).coerceAtLeast(0)
    }
    return (boundary - removedBefore).coerceAtLeast(0)
}
