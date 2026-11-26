package com.prism.music.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.FavoriteArtist
import com.prism.music.data.foryou.Excluded
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

private enum class PreferenceTab { LIKES, DISLIKES, ARTISTS, BLOCKED, SONGS, PLAYLISTS }

@Composable
internal fun VibeReactionsSheet(onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    PrismSheet(onDismiss) { close ->
        VibePreferencesContent(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().imePadding(),
            onClose = { close {} }, onAddArtists = { close { nav.go(Routes.PICK_ARTISTS) } })
    }
}

@Composable
internal fun VibePreferencesScreen(bottomPadding: Dp) {
    val nav = LocalNavigator.current
    VibePreferencesContent(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomPadding),
        onClose = { nav.back() }, onAddArtists = { nav.go(Routes.PICK_ARTISTS) })
}

/** One editor over the original stores. Track reactions and taste exclusions are intentionally distinct. */
@Composable
private fun VibePreferencesContent(modifier: Modifier, onClose: () -> Unit, onAddArtists: () -> Unit) {
    val c = LocalContainer.current
    val liked by c.library.liked.collectAsState()
    val disliked by c.library.dislikedSongs.collectAsState()
    val artists by c.library.profileArtists.collectAsState()
    val blocked by c.exclusions.artists.collectAsState()
    val ignoredSongs by c.exclusions.songs.collectAsState()
    val ignoredLists by c.exclusions.playlists.collectAsState()
    var tab by rememberSaveable { mutableStateOf(PreferenceTab.LIKES.name) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = PreferenceTab.valueOf(tab)
    val tracks = if (selected == PreferenceTab.LIKES) liked else disliked
    val exclusions = when (selected) {
        PreferenceTab.BLOCKED -> blocked
        PreferenceTab.SONGS -> ignoredSongs
        PreferenceTab.PLAYLISTS -> ignoredLists
        else -> emptyList()
    }
    fun matches(name: String) = query.isBlank() || name.contains(query.trim(), true)
    val labels = listOf(
        tr(R.string.prism_vibe_likes_count, liked.size),
        tr(R.string.prism_vibe_dislikes_count, disliked.size),
        tr(R.string.prism_vibe_favorite_artists_count, artists.size),
        tr(R.string.prism_vibe_blocked_artists_count, blocked.size),
        tr(R.string.prism_vibe_ignored_songs_count, ignoredSongs.size),
        tr(R.string.prism_vibe_ignored_lists_count, ignoredLists.size)
    )
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.prism_vibe_reactions), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            RoundAction(PrismIcons.Close, tr(R.string.done), onClick = onClose)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreferenceTab.entries.forEachIndexed { index, type ->
                com.prism.music.ui.components.PrismChip(selected = type == selected, label = labels[index]) { tab = type.name; query = "" }
            }
        }
        com.prism.music.ui.components.PrismTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(tr(R.string.prism_vibe_reaction_search)) })
        if (selected == PreferenceTab.ARTISTS) {
            com.prism.music.ui.components.PrismTextButton(onAddArtists) { Text(tr(R.string.prism_vibe_add_artists)) }
        }
        if (selected == PreferenceTab.SONGS || selected == PreferenceTab.PLAYLISTS)
            Text(tr(R.string.prism_vibe_ignore_info), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            when (selected) {
                PreferenceTab.LIKES, PreferenceTab.DISLIKES -> {
                    val shown = tracks.filter { matches(it.title + " " + it.artistText) }
                    if (shown.isEmpty()) item { EmptyPreferences() }
                    items(shown, key = { it.id }) { song ->
                        PreferenceRow(song.title, song.artistText) {
                            val negative = selected == PreferenceTab.DISLIKES
                            RoundAction(if (negative) PrismIcons.Heart else PrismIcons.ThumbDown,
                                tr(if (negative) R.string.prism_vibe_mark_like else R.string.not_for_me)) {
                                if (negative) c.library.setLiked(song, true) else c.library.setDisliked(song, true)
                            }
                            RoundAction(PrismIcons.Close, tr(R.string.prism_vibe_clear_reaction)) { c.library.clearReaction(song) }
                        }
                    }
                }
                PreferenceTab.ARTISTS -> {
                    val shown = artists.filter { matches(it.name) }
                    if (shown.isEmpty()) item { EmptyPreferences() }
                    items(shown, key = { it.id }) { artist ->
                        PreferenceRow(artist.name) {
                            RoundAction(PrismIcons.Block, tr(R.string.don_t_play_this_artist)) {
                                c.library.setArtistExcluded(Excluded(artist.id, artist.name, artist.thumbnail), true, artist.channelId)
                            }
                            RoundAction(PrismIcons.Close, tr(R.string.prism_vibe_clear_reaction)) { c.library.setFavoriteArtist(artist, artist.channelId, false) }
                        }
                    }
                }
                else -> {
                    val shown = exclusions.filter { matches(it.name) }
                    if (shown.isEmpty()) item { EmptyPreferences() }
                    items(shown, key = { it.id }) { entry ->
                        PreferenceRow(entry.name) {
                            if (selected == PreferenceTab.BLOCKED) RoundAction(PrismIcons.Heart, tr(R.string.prism_vibe_mark_like)) {
                                c.library.setFavoriteArtist(FavoriteArtist(entry.id, entry.name, entry.thumbnail), null, true)
                            }
                            RoundAction(PrismIcons.Close, tr(R.string.bring_back)) {
                                when (selected) {
                                    PreferenceTab.BLOCKED -> c.exclusions.setArtist(entry, false)
                                    PreferenceTab.SONGS -> c.exclusions.setSong(entry, false)
                                    PreferenceTab.PLAYLISTS -> c.exclusions.setPlaylist(entry, false)
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPreferences() {
    Text(tr(R.string.prism_vibe_reactions_empty), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PreferenceRow(title: String, subtitle: String = "", actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        actions()
    }
}
