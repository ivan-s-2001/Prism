package com.prism.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.censorship.CensorshipReports
import com.prism.music.data.model.Song
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.PrismSpinner
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.SongVersionsSheet
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Audit liked songs and phone playlists without changing any recording or library entry. */
@Composable
internal fun CollectionVersionAuditSheet(songs: List<Song>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val picks by c.versions.picks.collectAsState()
    var selected by remember { mutableStateOf<Song?>(null) }
    var retry by remember { mutableStateOf(0) }
    val audit by produceState<Result<CensorshipReports.Audit>?>(null, songs, retry) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                val ids = songs.map { it.id }.distinct()
                val links = ids.chunked(400).flatMap { c.db.imports().linkedYandexSources(it) }
                    .groupBy({ it.matchedId }, { it.sourceId })
                Result.success(c.censorshipReports.audit(ids.associateWith { links[it].orEmpty() }))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
    selected?.let { song ->
        SongVersionsSheet(song) { selected = null }
        return
    }
    val result = audit?.getOrNull()
    val flagged = remember(songs, result, picks) {
        songs.filter { it.id in result?.reports.orEmpty() }
            .sortedWith(compareBy<Song> { it.id in picks }.thenBy { it.title.lowercase() })
    }
    PrismSheet(onDismiss) { _ ->
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding().testTag("versionAuditSheet")) {
            Text(tr(R.string.prism_censorship_audit), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            Text(tr(R.string.prism_censorship_audit_scope), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            when {
                audit == null -> PrismSpinner(Modifier.padding(22.dp).size(24.dp), stroke = 2.dp)
                audit?.isFailure == true -> Text(tr(R.string.prism_censorship_audit_error), Modifier.padding(22.dp))
                result != null -> {
                    Text(tr(R.string.prism_censorship_audit_counts, result.linked, result.total, flagged.size),
                        Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    if (result.linked == 0) Text(tr(R.string.prism_censorship_audit_unlinked), Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
                    else if (result.feedsAvailable < 2) Text(tr(R.string.prism_censorship_audit_partial), Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
                    else if (flagged.isEmpty()) Text(tr(R.string.prism_censorship_audit_empty), Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
                    if (flagged.isNotEmpty()) {
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(flagged.size, key = { flagged[it].id }) { i ->
                                val song = flagged[i]
                                val report = result.reports.getValue(song.id)
                                val sources = listOfNotNull("FckCensorData".takeIf { report.fck }, "YM Liberty".takeIf { report.liberty }).joinToString(" · ")
                                Row(Modifier.fillMaxWidth().clickable { selected = song }.padding(horizontal = 22.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Artwork(song.thumbnail, Modifier.size(48.dp), size = 120)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                        Text(song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                        Text(if (song.id in picks) tr(R.string.prism_censorship_audit_picked) else sources,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(if (song.id in picks) PrismIcons.CheckCircle else PrismIcons.Search, null, Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                PrismTextButton(onClick = { retry++ }, enabled = audit != null) { Text(tr(R.string.prism_censorship_audit_retry)) }
                PrismTextButton(onClick = onDismiss) { Text(tr(R.string.done)) }
            }
        }
    }
}
