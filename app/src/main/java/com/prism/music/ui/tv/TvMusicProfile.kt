package com.prism.music.ui.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.FavoriteArtist
import com.prism.music.data.foryou.Excluded
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/** Native D-pad editor over the same stores as the phone. */
@Composable
internal fun TvMusicProfilePage(nav: TvNav) {
    val c = LocalContainer.current
    val likes by c.library.liked.collectAsState()
    val dislikes by c.library.dislikedSongs.collectAsState()
    val favorites by c.artistPrefs.favorites.collectAsState()
    val blocked by c.exclusions.artists.collectAsState()
    val ignoredSongs by c.exclusions.songs.collectAsState()
    val ignoredLists by c.exclusions.playlists.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val artists = favorites.filterNot { f -> blocked.any { it.id == f.id || it.name.equals(f.name, true) } }
    val labels = listOf(
        tr(R.string.prism_vibe_likes_count, likes.size), tr(R.string.prism_vibe_dislikes_count, dislikes.size),
        tr(R.string.prism_vibe_favorite_artists_count, artists.size), tr(R.string.prism_vibe_blocked_artists_count, blocked.size),
        tr(R.string.prism_vibe_ignored_songs_count, ignoredSongs.size), tr(R.string.prism_vibe_ignored_lists_count, ignoredLists.size)
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvIconButton(PrismIcons.ArrowBack, tr(R.string.back), { nav.back() }, Modifier.tvItem("profile/back"))
            Text(tr(R.string.prism_vibe_reactions), style = MaterialTheme.typography.headlineMedium)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { index, label ->
                    TvButton(label, { tab = index }, Modifier.fillMaxWidth().tvItem("profile/tab/$index", entry = index == 0),
                        primary = tab == index, fitLabel = true)
                }
            }
            key(tab) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
                    when (tab) {
                        0, 1 -> {
                            val list = if (tab == 0) likes else dislikes
                            if (list.isEmpty()) item { Text(tr(R.string.prism_vibe_reactions_empty)) }
                            items(list, key = { it.id }) { song ->
                                TvPreferenceRow(song.title, song.artistText) {
                                    TvIconButton(if (tab == 0) PrismIcons.ThumbDown else PrismIcons.Heart,
                                        tr(if (tab == 0) R.string.not_for_me else R.string.prism_vibe_mark_like),
                                        { if (tab == 0) c.library.setDisliked(song, true) else c.library.setLiked(song, true) },
                                        Modifier.tvItem("profile/change/${song.id}"))
                                    TvIconButton(PrismIcons.Close, tr(R.string.prism_vibe_clear_reaction),
                                        { c.library.clearReaction(song) }, Modifier.tvItem("profile/clear/${song.id}"))
                                }
                            }
                        }
                        2 -> {
                            if (artists.isEmpty()) item { Text(tr(R.string.prism_vibe_reactions_empty)) }
                            items(artists, key = { it.id }) { artist ->
                                TvPreferenceRow(artist.name) {
                                    TvIconButton(PrismIcons.Block, tr(R.string.don_t_play_this_artist),
                                        { c.library.setArtistExcluded(Excluded(artist.id, artist.name, artist.thumbnail), true, artist.channelId) },
                                        Modifier.tvItem("profile/block/${artist.id}"))
                                    TvIconButton(PrismIcons.Close, tr(R.string.prism_vibe_clear_reaction),
                                        { c.library.setFavoriteArtist(artist, artist.channelId, false) }, Modifier.tvItem("profile/clear/${artist.id}"))
                                }
                            }
                        }
                        else -> {
                            val list = when (tab) { 3 -> blocked; 4 -> ignoredSongs; else -> ignoredLists }
                            if (list.isEmpty()) item { Text(tr(R.string.prism_vibe_reactions_empty)) }
                            items(list, key = { it.id }) { entry ->
                                TvPreferenceRow(entry.name) {
                                    if (tab == 3) TvIconButton(PrismIcons.Heart, tr(R.string.prism_vibe_mark_like),
                                        { c.library.setFavoriteArtist(FavoriteArtist(entry.id, entry.name, entry.thumbnail), null, true) },
                                        Modifier.tvItem("profile/like/${entry.id}"))
                                    TvIconButton(PrismIcons.Close, tr(R.string.bring_back), {
                                        when (tab) {
                                            3 -> c.library.setArtistExcluded(entry, false)
                                            4 -> c.exclusions.setSong(entry, false)
                                            else -> c.exclusions.setPlaylist(entry, false)
                                        }
                                    }, Modifier.tvItem("profile/clear/${entry.id}"))
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
private fun TvPreferenceRow(title: String, subtitle: String = "", actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}
