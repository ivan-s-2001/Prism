package com.prism.music

import com.prism.music.data.ReactionRequest
import com.prism.music.data.SongReaction
import com.prism.music.data.reactionJournalKey
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReactionJournalTest {
    @Test fun requestRoundTripsWithMetadataAndAccount() {
        val original = ReactionRequest(Song("video", "Track"), SongReaction.DISLIKE, "account-a")
        val encoded = InnerTube.json.encodeToString(ReactionRequest.serializer(), original)
        val restored = InnerTube.json.decodeFromString(ReactionRequest.serializer(), encoded)
        assertEquals(original, restored)
        assertEquals(original.journalKey, restored.journalKey)
    }

    @Test fun successiveReactionsReplaceSameAccountSlot() {
        val song = Song("video", "Track")
        val like = ReactionRequest(song, SongReaction.LIKE, "account-a")
        val clear = ReactionRequest(song, SongReaction.NONE, "account-a")
        assertEquals(like.journalKey, clear.journalKey)
        assertNotEquals(like.operationId, clear.operationId)
    }

    @Test fun journalSlotsNeverMixAccountsOrDeviceOnlyTracks() {
        assertNotEquals(reactionJournalKey("video", "account-a"), reactionJournalKey("video", "account-b"))
        assertNotEquals(reactionJournalKey("video", "account-a"), reactionJournalKey("video", null))
    }
}
