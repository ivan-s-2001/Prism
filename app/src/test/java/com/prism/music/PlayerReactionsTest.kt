package com.prism.music

import com.prism.music.data.prefs.PlayerButton
import com.prism.music.ui.player.pairedPlayerReactions
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerReactionsTest {
    @Test fun reactionsAreMutuallyExclusiveAndToggleOff() {
        val none = com.prism.music.data.SongReaction.NONE
        val like = com.prism.music.data.SongReaction.LIKE
        val dislike = com.prism.music.data.SongReaction.DISLIKE
        assertEquals(like, com.prism.music.data.reactionAfterLike(none))
        assertEquals(none, com.prism.music.data.reactionAfterLike(like))
        assertEquals(like, com.prism.music.data.reactionAfterLike(dislike))
        assertEquals(dislike, com.prism.music.data.reactionAfterDislike(none))
        assertEquals(dislike, com.prism.music.data.reactionAfterDislike(like))
        assertEquals(none, com.prism.music.data.reactionAfterDislike(dislike))
    }

    @Test fun fullCustomDeckDoesNotSilentlyDropOtherButtons() {
        val buttons = listOf(PlayerButton.LIKE, PlayerButton.QUEUE, PlayerButton.LYRICS,
            PlayerButton.OUTPUT, PlayerButton.SLEEP, PlayerButton.DOWNLOAD)
        assertEquals(buttons, pairedPlayerReactions(buttons, false))
        assertEquals(buttons - PlayerButton.LIKE, pairedPlayerReactions(buttons, true))
    }

    @Test fun fullDeckWithExplicitPairRetainsBothReactions() {
        val buttons = listOf(PlayerButton.DISLIKE, PlayerButton.LIKE, PlayerButton.QUEUE,
            PlayerButton.LYRICS, PlayerButton.OUTPUT, PlayerButton.SLEEP)
        assertEquals(listOf(PlayerButton.LIKE, PlayerButton.DISLIKE, PlayerButton.QUEUE,
            PlayerButton.LYRICS, PlayerButton.OUTPUT, PlayerButton.SLEEP), pairedPlayerReactions(buttons, false))
    }

    @Test fun headerOwnsPairByDefault() {
        assertEquals(listOf(PlayerButton.QUEUE), pairedPlayerReactions(listOf(PlayerButton.QUEUE, PlayerButton.DISLIKE), true))
    }
    @Test fun customDeckKeepsPairAdjacentWithoutDuplicates() {
        assertEquals(listOf(PlayerButton.QUEUE, PlayerButton.LIKE, PlayerButton.DISLIKE, PlayerButton.LYRICS),
            pairedPlayerReactions(listOf(PlayerButton.DISLIKE, PlayerButton.QUEUE, PlayerButton.LIKE, PlayerButton.LYRICS), true))
    }
}
