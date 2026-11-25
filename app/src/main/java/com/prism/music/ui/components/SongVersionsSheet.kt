package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.SongVersions
import com.prism.music.data.UncensoredRules
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.formatDuration
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Play a different version" (1.7.9, Reddit: a song from an album played as someone's guitar cover): the other
 * recordings YouTube has of the song's title, with their album and length. The one picked plays in the song's place
 * from then on ([com.prism.music.AppContainer.pickVersion]); the song's own row goes back to it.
 */
@Composable
fun SongVersionsSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val picks by c.versions.picks.collectAsState()
    val picked = picks[song.id]?.id
    val autoOriginals by c.uncensored.enabled.collectAsState()
    // Results arrive independently: one unavailable provider cannot hide the others.
    var queryOverride by androidx.compose.runtime.saveable.rememberSaveable(song.id) { mutableStateOf("") }
    var queryInput by androidx.compose.runtime.saveable.rememberSaveable(song.id) { mutableStateOf("") }
    var found by remember(song.id, queryOverride) { mutableStateOf<List<Song>>(emptyList()) }
    var pending by remember(song.id, queryOverride) { mutableStateOf(true) }
    var verifying by remember(song.id) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(song.id, queryOverride) {
        val query = queryOverride.ifBlank { "${song.primaryArtist} ${song.title}".trim() }
        val hint = if (song.title.any { it in '\u0400'..'\u04ff' }) "без цензуры" else "uncensored"
        val alias = if (song.primaryArtist.equals("Heronwater", true)) query.replace("Heronwater", "Heroinwater", ignoreCase = true) else query
        fun publish(results: List<Song>) {
            found = (found + results).distinctBy { it.id }
        }
        try {
            coroutineScope {
                listOf(
                    async {
                        val results = withContext(Dispatchers.IO) {
                            runCatching { c.ytm.search(query, SearchFilter.SONGS).items }.getOrDefault(emptyList())
                        }
                        publish(results.filterIsInstance<SongItem>().map { it.song })
                    },
                    async {
                        val results = withContext(Dispatchers.IO) {
                            runCatching { c.ytm.search("$alias $hint", SearchFilter.VIDEOS).items }.getOrDefault(emptyList())
                        }
                        publish(results.filterIsInstance<SongItem>().map { it.song })
                    },
                    async {
                        val results = withContext(Dispatchers.IO) {
                            runCatching {
                                c.streams
                                com.prism.music.data.OriginalSources.search("$alias $hint", false)
                            }.getOrDefault(emptyList())
                        }
                        publish(results)
                    },
                    async {
                        val results = withContext(Dispatchers.IO) {
                            runCatching {
                                c.streams
                                com.prism.music.data.OriginalSources.search(alias, true)
                            }.getOrDefault(emptyList())
                        }
                        publish(results)
                    },
                ).forEach { it.await() }
            }
        } finally {
            pending = false
        }
    }
    val versions = remember(song, found, queryOverride) {
        val ranked = UncensoredRules.review(song, found)
        // Explicit custom searches expose all results, making it possible to find an upload with different metadata.
        if (queryOverride.isBlank()) (ranked + SongVersions.candidates(song, found)).distinctBy { it.id }
        else (ranked + found).distinctBy { it.id }.take(40)
    }
    fun choose(pick: SongVersions.Pick?) {
        c.pickVersion(song, pick)
        android.widget.Toast.makeText(
            context, if (pick == null) tr(R.string.plays_as_itself_again) else tr(R.string.plays_as_this_version_from_now), android.widget.Toast.LENGTH_SHORT,
        ).show()
        onDismiss()
    }
    // Files on this phone (1.8.3, #243): those with the song's title, and any other the listener picks.
    val files by c.localMusic.tracks.collectAsState()
    val canSeeFiles = remember { c.localMusic.hasPermission() }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (canSeeFiles && !c.localMusic.scanned.value) c.localMusic.scan() }
    val fileMatches = remember(files, song.id) {
        val want = SongVersions.bare(song.title)
        if (want.isEmpty() || song.isLocal) emptyList() else files.filter { SongVersions.bare(it.song.title) == want }.take(6)
    }
    var choosingFile by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (choosingFile) { PhoneFilePicker(files.map { it.song }) { f -> choosingFile = false; f?.let { choose(SongVersions.Pick(it.id, fileLabel(it), it)) } }; return }
    PrismSheet(onDismiss) { _ ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 8.dp)) {
                Text(tr(R.string.play_a_different_version), style = MaterialTheme.typography.titleLarge)
                Text(tr(R.string.the_one_you_pick_plays_for), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(tr(R.string.uncensored_recordings), style = MaterialTheme.typography.bodyLarge)
                    Text(tr(R.string.uncensored_recordings_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoOriginals, onCheckedChange = c.uncensored::setEnabled)
            }
            VersionRow(song, tr(R.string.version_original), picked == null, Modifier.testTag("version:own")) { choose(null) }
            // A file of the song on the phone plays from the file (a FLAC of an album bought elsewhere, say).
            if (!song.isLocal) {
                fileMatches.forEach { t ->
                    VersionRow(t.song, tr(R.string.your_file), picked == t.song.id, Modifier.testTag("version:${t.song.id}")) {
                        choose(SongVersions.Pick(t.song.id, fileLabel(t.song), t.song))
                    }
                }
                // A picked file that doesn't match the title still shows, ticked.
                picks[song.id]?.song?.takeIf { it.isLocal && fileMatches.none { t -> t.song.id == it.id } }?.let { f ->
                    VersionRow(f, tr(R.string.your_file), true, Modifier.testTag("version:${f.id}")) {}
                }
                if (canSeeFiles && files.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().clickable { choosingFile = true }.padding(horizontal = 22.dp, vertical = 12.dp).testTag("version:chooseFile"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PrismIcons.Folder, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(tr(R.string.choose_a_file_on_this_phone), style = MaterialTheme.typography.bodyLarge)
                }
            }
            SearchField(queryInput, tr(R.string.search), { queryInput = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text(
                tr(R.string.search), Modifier.padding(horizontal = 22.dp, vertical = 10.dp).clickable {
                    queryOverride = queryInput.trim()
                },
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            )
            if (pending || verifying) PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
            if (!pending && versions.isEmpty()) Quiet(tr(R.string.youtube_has_no_other_version_of))
            versions.forEach { v ->
                val sc = com.prism.music.data.OriginalSources.isSoundCloud(v.id)
                val provider = if (sc) "SoundCloud" else if (v.isVideo) "YouTube" else "YouTube Music"
                val tag = listOfNotNull(provider, tr(R.string.uncensored_candidate).takeIf { UncensoredRules.markedOriginal(v) }).joinToString(" · ")
                VersionRow(v, tag, picked == v.id, Modifier.testTag("version:${v.id}")) {
                    if (verifying) return@VersionRow
                    verifying = true
                    scope.launch {
                        try {
                            val playable = withContext(Dispatchers.IO) {
                                runCatching { c.streams.resolve(v.id) }.isSuccess
                            }
                            if (playable) {
                                val shown = v.copy(title = song.title, artists = song.artists, album = song.album, thumbnail = song.thumbnail)
                                choose(SongVersions.Pick(v.id, "$provider · ${versionLabel(v)}", shown))
                            } else {
                                android.widget.Toast.makeText(context, tr(R.string.no_playable_audio_stream_was_found), android.widget.Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            verifying = false
                        }
                    }
                }
            }
        }
    }
}

/** What a version's row (and the song menu, once picked) says of it: artist, album or "Video", length. */
fun versionLabel(v: Song): String = listOfNotNull(
    v.artistText.takeIf { it.isNotBlank() },
    if (v.isVideo) tr(R.string.video) else v.album?.title?.takeIf { it.isNotBlank() },
    formatDuration(v.durationSec).takeIf { it.isNotEmpty() },
).joinToString(" · ")

/** A file's row: "Your file · Artist · 3:41" (in the song menu once picked). */
private fun fileLabel(f: Song): String = listOf(tr(R.string.your_file), versionLabel(f)).filter { it.isNotBlank() }.joinToString(" · ")

/** Any music file on the phone, found by typing (1.8.3, #243); null: none picked. */
@Composable
private fun PhoneFilePicker(files: List<Song>, onPick: (Song?) -> Unit) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val shown = remember(files, query) {
        if (query.isBlank()) files.take(300)
        else {
            val words = com.prism.music.data.LibrarySearch.norm(query).split(' ').filter { it.isNotBlank() }
            files.filter { f -> com.prism.music.data.LibrarySearch.norm("${f.title} ${f.artistText} ${f.album?.title.orEmpty()}").let { hay -> words.all { it in hay } } }.take(300)
        }
    }
    PrismSheet({ onPick(null) }) { _ ->
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding().testTag("filePicker")) {
            Text(tr(R.string.choose_a_file_on_this_phone), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 22.dp))
            SearchField(query, tr(R.string.search), { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                items(shown.size, key = { shown[it].id }) { i ->
                    val f = shown[i]
                    VersionRow(f, null, false, Modifier.testTag("file:${f.id}")) { onPick(f) }
                }
            }
        }
    }
}

@Composable
private fun Quiet(text: String) = Text(
    text, Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun VersionRow(v: Song, tag: String?, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(v.thumbnail, Modifier.size(48.dp), shape = LocalUi.current.shape(6.dp), size = 120)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(tag, versionLabel(v).takeIf { it.isNotBlank() }).distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) Icon(PrismIcons.Check, tr(R.string.playing_this_one), Modifier.padding(start = 8.dp).size(20.dp), tint = scheme.onSurface)
    }
}
