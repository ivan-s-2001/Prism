package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/** The same persisted reaction state used by the player and recommendation filters. */
@Composable
internal fun VibeReactionsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val liked by c.library.liked.collectAsState()
    val disliked by c.library.dislikedSongs.collectAsState()
    var negative by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(liked, disliked, negative, query) {
        (if (negative) disliked else liked).filter {
            query.isBlank() || it.title.contains(query, true) || it.artistText.contains(query, true)
        }
    }
    PrismSheet(onDismiss) { close ->
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.prism_vibe_reactions), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                RoundAction(PrismIcons.Close, tr(R.string.done)) { close() }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.prism.music.ui.components.PrismChip(selected = !negative,
                    label = tr(R.string.prism_vibe_likes_count, liked.size), icon = PrismIcons.HeartFilled) { negative = false }
                com.prism.music.ui.components.PrismChip(selected = negative,
                    label = tr(R.string.prism_vibe_dislikes_count, disliked.size), icon = PrismIcons.ThumbDownFilled) { negative = true }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(tr(R.string.prism_vibe_reaction_search)) })
            if (visible.isEmpty()) Text(tr(R.string.prism_vibe_reactions_empty),
                Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(visible, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            if (song.artistText.isNotBlank()) Text(song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        RoundAction(if (negative) PrismIcons.Heart else PrismIcons.ThumbDown,
                            tr(if (negative) R.string.prism_vibe_mark_like else R.string.not_for_me)) {
                            if (negative) c.library.setLiked(song, true) else c.library.setDisliked(song, true)
                        }
                        RoundAction(PrismIcons.Close, tr(R.string.prism_vibe_clear_reaction)) { c.library.clearReaction(song) }
                    }
                }
            }
        }
    }
}
