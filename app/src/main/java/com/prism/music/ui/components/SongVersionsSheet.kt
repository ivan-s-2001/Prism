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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

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
    // null while looking; a failure when the search didn't answer.
    val settings = com.prism.music.ui.theme.LocalAppSettings.current
    val country = settings.contentCountry
    val explicitMode = settings.behavior.explicitContent
    val preferExplicit = explicitMode == com.prism.music.data.prefs.ExplicitContent.SHOW
    val report by produceState<com.prism.music.data.censorship.CensorshipReports.Report?>(null, song.id, preferExplicit) {
        value = null
        if (preferExplicit) value = withContext(Dispatchers.IO) {
            c.censorshipReports.reports(c.db.imports().yandexSourceIds(song.id))
        }
    }
    data class RegionalResults(val songs: List<Song>, val catalogs: Map<String, Set<String>>)
    val found by produceState<Result<RegionalResults>?>(null, song.id, country, explicitMode) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val queries = SongVersions.searchQueries(song, preferExplicit)
                coroutineScope {
                    suspend fun search(q: String, filter: SearchFilter): List<Song> =
                        try { c.ytm.search(q, filter).items.filterIsInstance<SongItem>().map { it.song } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { emptyList() }
                    fun matches(results: List<Song>): List<Song> = SongVersions.candidates(
                        song, results.filter { com.prism.music.data.ExplicitFilter.plays(explicitMode, it) },
                        preferExplicit = preferExplicit,
                    )
                    val catalogs = mutableMapOf<String, MutableSet<String>>()
                    val all = mutableListOf<Song>()
                    if (preferExplicit) {
                        val regions = SongVersions.alternativeCatalogs(country)
                        suspend fun foreign(q: String, region: String, filter: SearchFilter): List<Song> =
                            try { c.ytm.catalogSongs(q, region, filter) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                        // Different regions are requested independently, not by rewriting global settings.
                        for (query in queries.take(2)) {
                            val results = regions.map { region ->
                                async { region to foreign(query, region, SearchFilter.SONGS) }
                            }.map { it.await() }
                            for ((region, songs) in results) {
                                all += songs
                                songs.forEach { catalogs.getOrPut(it.id) { mutableSetOf() }.add(region) }
                            }
                        }
                    }
                    val initial = listOf(
                        async { search(queries.first(), SearchFilter.SONGS) },
                        async { search(queries.first(), SearchFilter.VIDEOS) },
                    ).map { it.await() }.flatten()
                    all += initial
                    // Search for an explicit/original edition even when the Yandex report is absent.
                    // Additional queries are bounded and strictly filtered; do not lower identity checks.
                    if (matches(all).size < 4 || preferExplicit && matches(all).none { it.explicit }) {
                        for (batch in queries.drop(1).chunked(2)) {
                            all += batch.map { q -> async { search(q, SearchFilter.SONGS) } }.map { it.await() }.flatten()
                            if (matches(all).size >= 6 && (!preferExplicit || matches(all).any { it.explicit })) break
                        }
                    }
                    if (matches(all).size < 2) {
                        all += search(queries.first(), SearchFilter.TOP)
                        queries.getOrNull(1)?.let { all += search(it, SearchFilter.VIDEOS) }
                    }
                    if (all.isEmpty()) throw java.io.IOException()
                    val ranked = matches(all).sortedWith(compareBy<Song>(
                        { !(preferExplicit && SongVersions.preferredOriginal(song, it)) },
                        { it.id !in catalogs },
                        { it.isVideo },
                        { kotlin.math.abs(song.durationSec - it.durationSec) }
                    ))
                    RegionalResults(ranked, catalogs.mapValues { it.value.toSet() })
                }
            }
        }
    }
    val soundCloud by produceState<Result<List<Song>>?>(null, song.id, preferExplicit) {
        value = null
        if (preferExplicit) value = withContext(Dispatchers.IO) {
            try { Result.success(c.streams.soundCloudVersions(song)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
    val bandcamp by produceState<Result<List<Song>>?>(null, song.id, preferExplicit) {
        value = null
        if (preferExplicit) value = withContext(Dispatchers.IO) {
            try { Result.success(c.streams.bandcampVersions(song)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
    val audius by produceState<Result<List<Song>>?>(null, song.id, preferExplicit) {
        value = null
        if (preferExplicit) value = withContext(Dispatchers.IO) {
            try { Result.success(c.streams.audiusVersions(song)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
    var link by remember(song.id) { mutableStateOf("") }
    var checkingLink by remember(song.id) { mutableStateOf(false) }
    var checkedLink by remember(song.id) { mutableStateOf<Song?>(null) }
    var linkError by remember(song.id) { mutableStateOf(false) }
    var enteringLink by remember(song.id) { mutableStateOf(false) }
    val linkScope = androidx.compose.runtime.rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
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
        if (song.isLocal) emptyList() else files.filter { SongVersions.compatibleRecording(song, it.song) }.take(6)
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
            report?.let { r ->
                val sources = listOfNotNull("FckCensorData".takeIf { r.fck }, "YM Liberty".takeIf { r.liberty }).joinToString(" · ")
                Text(tr(R.string.prism_censorship_report_title), Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium)
                Quiet(tr(R.string.prism_censorship_report_note, sources))
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
            if (preferExplicit && !song.isLocal && !song.isEpisode && !song.isLive && !song.isUpload) {
                Text(tr(R.string.prism_sc_versions), Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
                Quiet(tr(R.string.prism_sc_versions_note))
                val sc = soundCloud
                val saved = picks[song.id]?.song?.takeIf { com.prism.music.data.stream.SoundCloudVersion.url(it.id) != null }
                val candidates = sc?.getOrNull().orEmpty()
                if (saved != null && candidates.none { it.id == saved.id }) {
                    VersionRow(saved, "SoundCloud", true, Modifier.testTag("version:${saved.id}")) {}
                }
                when {
                    sc == null -> PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
                    sc.isFailure -> Quiet(tr(R.string.couldn_t_look_for_other_versions))
                    candidates.isEmpty() -> Quiet(tr(R.string.prism_sc_no_versions))
                    else -> candidates.forEach { v ->
                        val source = if (com.prism.music.data.stream.SoundCloudVersion.isHls(v.id)) "SoundCloud · HLS" else "SoundCloud"
                        VersionRow(v, source, picked == v.id, Modifier.testTag("version:${v.id}")) {
                            choose(SongVersions.Pick(v.id, "SoundCloud · " + versionLabel(v), v))
                        }
                    }
                }
            }
            if (preferExplicit && !song.isLocal && !song.isEpisode && !song.isLive && !song.isUpload) {
                Text("Bandcamp", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                Quiet(tr(R.string.prism_bandcamp_note))
                val bc = bandcamp
                val candidates = bc?.getOrNull().orEmpty()
                val saved = picks[song.id]?.song?.takeIf { com.prism.music.data.stream.BandcampVersion.url(it.id) != null }
                if (saved != null && candidates.none { it.id == saved.id }) {
                    VersionRow(saved, "Bandcamp", true, Modifier.testTag("version:${saved.id}")) {}
                }
                when {
                    bc == null -> PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
                    bc.isFailure -> Quiet(tr(R.string.couldn_t_look_for_other_versions))
                    candidates.isEmpty() -> Quiet(tr(R.string.prism_sc_no_versions))
                    else -> candidates.forEach { v ->
                        VersionRow(v, "Bandcamp", picked == v.id, Modifier.testTag("version:${v.id}")) {
                            choose(SongVersions.Pick(v.id, "Bandcamp · " + versionLabel(v), v))
                        }
                    }
                }
            }
            if (preferExplicit && !song.isLocal && !song.isEpisode && !song.isLive && !song.isUpload) {
                Text("Audius", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                Quiet(tr(R.string.prism_audius_note))
                val au = audius
                val candidates = au?.getOrNull().orEmpty()
                val saved = picks[song.id]?.song?.takeIf { com.prism.music.data.stream.AudiusVersions.url(it.id) != null }
                if (saved != null && candidates.none { it.id == saved.id }) {
                    VersionRow(saved, "Audius", true, Modifier.testTag("version:${saved.id}")) {}
                }
                when {
                    au == null -> PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
                    au.isFailure -> Quiet(tr(R.string.couldn_t_look_for_other_versions))
                    candidates.isEmpty() -> Quiet(tr(R.string.prism_sc_no_versions))
                    else -> candidates.forEach { v ->
                        VersionRow(v, "Audius", picked == v.id, Modifier.testTag("version:${v.id}")) {
                            choose(SongVersions.Pick(v.id, "Audius · " + versionLabel(v), v))
                        }
                    }
                }
            }
            if (preferExplicit && !song.isLocal && !song.isEpisode && !song.isLive && !song.isUpload) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Prism", "${song.primaryArtist} ${song.title}"))
                        runCatching { uriHandler.openUri("https://zaycev.net/") }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text(tr(R.string.prism_zaycev_open)) }
                androidx.compose.material3.TextButton(
                    onClick = { enteringLink = !enteringLink },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text(tr(R.string.prism_bc_manual)) }
                if (enteringLink) {
                    androidx.compose.material3.OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; checkedLink = null; linkError = false },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                        enabled = !checkingLink,
                        singleLine = true,
                        label = { Text("Bandcamp URL") },
                    )
                    androidx.compose.material3.TextButton(
                        enabled = link.isNotBlank() && !checkingLink,
                        onClick = {
                            checkingLink = true
                            checkedLink = null
                            linkError = false
                            val requested = link
                            linkScope.launch {
                                try {
                                    checkedLink = withContext(Dispatchers.IO) { c.streams.bandcampVersionFromLink(song, requested) }
                                    linkError = checkedLink == null
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { linkError = true }
                                finally { checkingLink = false }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text(tr(R.string.prism_bc_check)) }
                    if (checkingLink) PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
                    if (linkError) Quiet(tr(R.string.prism_bc_link_failed))
                    checkedLink?.let { v ->
                        VersionRow(v, "Bandcamp", picked == v.id, Modifier.testTag("version:manual")) {
                            choose(SongVersions.Pick(v.id, "Bandcamp · " + versionLabel(v), v))
                        }
                    }
                }
            }
            val r = found
            when {
                r == null -> PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
                r.isFailure -> Quiet(tr(R.string.couldn_t_look_for_other_versions))
                r.getOrThrow().songs.isEmpty() -> Quiet(tr(R.string.youtube_has_no_other_version_of))
                else -> r.getOrThrow().songs.forEach { v ->
                    val catalogLabel = r.getOrThrow().catalogs[v.id]?.takeIf { it.isNotEmpty() }?.let {
                        tr(R.string.prism_version_catalog, it.joinToString(", "))
                    }
                    val kind = when {
                        preferExplicit && SongVersions.preferredOriginal(song, v) -> tr(R.string.prism_version_explicit_match)
                        SongVersions.sameRecording(song, v) -> tr(R.string.another_upload)
                        else -> null
                    }
                    VersionRow(v, listOfNotNull(kind, catalogLabel).joinToString(" · ").ifBlank { null },
                        picked == v.id, Modifier.testTag("version:${v.id}")) {
                        // The version itself is kept too: the song shows as it from now on (#328).
                        choose(SongVersions.Pick(v.id, versionLabel(v), v))
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
