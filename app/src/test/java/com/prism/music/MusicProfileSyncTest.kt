package com.prism.music

import com.prism.music.data.SongReaction
import com.prism.music.data.mergeReactionEdits
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicProfileSyncTest {
    @Test fun latestPendingReactionWinsOverAcknowledgedAndFetchedState() {
        val result = mergeReactionEdits(
            mapOf("song" to SongReaction.LIKE, "other" to SongReaction.DISLIKE),
            mapOf("song" to SongReaction.DISLIKE),
            mapOf("song" to SongReaction.NONE),
        )
        assertEquals(SongReaction.NONE, result["song"])
        assertEquals(SongReaction.DISLIKE, result["other"])
    }

    @Test fun acknowledgedRemovalWinsOverOldFetch() {
        val result = mergeReactionEdits(
            mapOf("song" to SongReaction.LIKE),
            mapOf("song" to SongReaction.NONE),
            emptyMap(),
        )
        assertEquals(SongReaction.NONE, result["song"])
    }
}
