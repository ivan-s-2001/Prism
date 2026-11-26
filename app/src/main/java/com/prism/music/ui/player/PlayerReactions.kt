package com.prism.music.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

internal fun canStartTrackVibe(song: Song): Boolean = !song.isEpisode && !song.isLive && !song.isLocal && !song.isRss

/** When Like is moved to the customizable deck, keep the two reactions adjacent there.
 * Otherwise the credits own the pair; do not repeat a previously saved dislike button. */
internal fun pairedPlayerReactions(buttons: List<PlayerButton>, headerLike: Boolean): List<PlayerButton> {
    if (PlayerButton.LIKE in buttons) return buttons.filter { it != PlayerButton.DISLIKE }.flatMap {
        if (it == PlayerButton.LIKE) listOf(it, PlayerButton.DISLIKE) else listOf(it)
    }
    return if (headerLike) buttons.filter { it != PlayerButton.DISLIKE } else buttons
}

@Composable
internal fun PlayerDislikeGlyph(song: Song) {
    val c = LocalContainer.current
    val disliked by c.library.dislikedIds.collectAsState()
    val on = song.id in disliked
    CircleGlyph(if (on) PrismIcons.ThumbDownFilled else PrismIcons.ThumbDown,
        tr(R.string.not_for_me), on, Modifier.testTag("player:dislike")) {
        val set = !c.library.isDisliked(song.id)
        c.library.setDisliked(song, set)
        if (set && c.player.currentSong.value?.id == song.id && c.player.hasNext.value) c.player.next()
    }
}
