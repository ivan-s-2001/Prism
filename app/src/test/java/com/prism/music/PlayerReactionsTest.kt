package com.prism.music

import com.prism.music.data.prefs.PlayerButton
import com.prism.music.ui.player.pairedPlayerReactions
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerReactionsTest {
    @Test fun headerOwnsPairByDefault() {
        assertEquals(listOf(PlayerButton.QUEUE), pairedPlayerReactions(listOf(PlayerButton.QUEUE, PlayerButton.DISLIKE), true))
    }
    @Test fun customDeckKeepsPairAdjacentWithoutDuplicates() {
        assertEquals(listOf(PlayerButton.QUEUE, PlayerButton.LIKE, PlayerButton.DISLIKE, PlayerButton.LYRICS),
            pairedPlayerReactions(listOf(PlayerButton.DISLIKE, PlayerButton.QUEUE, PlayerButton.LIKE, PlayerButton.LYRICS), true))
    }
}
