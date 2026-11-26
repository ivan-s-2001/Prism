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
import com.prism.music.data.stream.RecordingDiscovery
import com.prism.music.data.stream.ExternalCatalogDiscovery
import com.prism.music.data.stream.CatalogService
import com.prism.music.data.stream.CatalogOutcome
import com.prism.music.data.stream.RecordingHit
import com.prism.music.data.stream.RecordingSearchRank
import com.prism.music.data.stream.RecordingSearchResult
import com.prism.music.data.stream.RecordingSource
import com.prism.music.data.stream.RecordingSourcePlan
import com.prism.music.data.model.Song
import com.prism.music.data.model.formatDuration
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
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
            try { c.censorshipReports.reports(c.db.imports().yandexSourceIds(song.id)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
    val prefs = remember(context) { context.getSharedPreferences("recording_sources", android.content.Context.MODE_PRIVATE) }
    var plan by remember(prefs) { mutableStateOf(RecordingSourcePlan.restore(
        prefs.getString("order", null), prefs.getStringSet("disabled", emptySet()).orEmpty()
    )) }
    fun updatePlan(next: RecordingSourcePlan) {
        plan = next
        prefs.edit().putString("order", next.order.joinToString(",") { it.name })
            .putStringSet("disabled", next.disabled.map { it.name }.toSet()).apply()
    }
    val enabled = plan.enabled.filter { it == RecordingSource.YOUTUBE || preferExplicit }.toSet()
    var retry by remember(song.id) { mutableStateOf(0) }
    var showSources by remember { mutableStateOf(false) }
    val discovery = remember(c) { RecordingDiscovery(c.ytm, c.streams) }
    val results by produceState<Map<RecordingSource, RecordingSearchResult>>(emptyMap(), song.id, country, explicitMode, enabled, retry) {
        value = emptyMap()
        if (!song.isLocal && !song.isEpisode && !song.isLive && !song.isUpload) {
            discovery.search(song, country, explicitMode, enabled).collect { result ->
                value = value + (result.source to result)
            }
        }
    }
    val hits = remember(song, plan, results, explicitMode) { RecordingSearchRank.rank(song, plan, results.values, explicitMode) }
    var showCatalogs by remember(song.id) { mutableStateOf(false) }
    val catalogDiscovery = remember(c) { ExternalCatalogDiscovery(c.spotify.api) }
    val catalogResults by produceState<Map<CatalogService, CatalogOutcome>>(emptyMap(), song.id, explicitMode, showCatalogs) {
        value = emptyMap()
        if (showCatalogs) catalogDiscovery.search(song, explicitMode).collect { result ->
            value = value + (result.service to result)
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
            if (!song.isLocal && !song.isEpisode && !song.isLive && !song.isUpload) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.prism_recordings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { showSources = !showSources }) {
                        Text(tr(R.string.prism_recording_sources))
                    }
                }
                val done = results.keys.count { it in enabled }
                Quiet(tr(R.string.prism_recording_progress, hits.size, done, enabled.size))
                if (showSources) {
                    plan.order.forEachIndexed { index, source ->
                        val status = when {
                            source !in plan.enabled -> tr(R.string.prism_recording_disabled)
                            source !in enabled -> tr(R.string.prism_recording_restricted)
                            results[source] == null -> tr(R.string.prism_recording_searching)
                            results[source]?.failed == true -> tr(R.string.prism_recording_error)
                            else -> tr(R.string.prism_recording_count, results[source]?.hits?.size ?: 0)
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = source in plan.enabled, onCheckedChange = { updatePlan(plan.toggle(source)) })
                            Column(Modifier.weight(1f)) {
                                Text(source.label, style = MaterialTheme.typography.bodyMedium)
                                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.TextButton(enabled = index > 0, onClick = { updatePlan(plan.move(source, -1)) }) { Text("↑") }
                            androidx.compose.material3.TextButton(enabled = index < plan.order.lastIndex, onClick = { updatePlan(plan.move(source, 1)) }) { Text("↓") }
                        }
                    }
                    Quiet(tr(R.string.prism_recording_order_note))
                }
                val saved = picks[song.id]?.song?.takeUnless { it.isLocal }
                if (saved != null && hits.none { it.song.id == saved.id }) {
                    VersionRow(saved, RecordingSource.of(saved.id).label, true, Modifier.testTag("version:${saved.id}")) {}
                }
                hits.forEach { hit ->
                    val v = hit.song
                    val catalogLabel = hit.catalogs.takeIf { it.isNotEmpty() }?.let {
                        tr(R.string.prism_version_catalog, it.joinToString(", "))
                    }
                    val kind = when {
                        preferExplicit && SongVersions.preferredOriginal(song, v) -> tr(R.string.prism_version_explicit_match)
                        else -> null
                    }
                    VersionRow(v, listOfNotNull(hit.source.label, kind, catalogLabel).joinToString(" · "),
                        picked == v.id, Modifier.testTag("version:${v.id}")) {
                        choose(SongVersions.Pick(v.id, hit.source.label + " · " + versionLabel(v), v))
                    }
                }
                if (done < enabled.size) PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
                if (done == enabled.size && hits.isEmpty()) {
                    Quiet(if (enabled.isEmpty()) tr(R.string.prism_recording_none_enabled)
                        else if (results.values.any { it.failed }) tr(R.string.prism_recording_partial)
                        else tr(R.string.prism_sc_no_versions))
                }
                if (results.values.any { it.failed } || done == enabled.size) {
                    androidx.compose.material3.TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(tr(R.string.prism_recording_retry))
                    }
                }
                Quiet(tr(R.string.prism_recording_caution))
                androidx.compose.material3.TextButton(
                    onClick = { showCatalogs = !showCatalogs }, modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text(tr(R.string.prism_external_catalogs)) }
                if (showCatalogs) {
                    Quiet(tr(R.string.prism_external_catalog_note))
                    CatalogService.entries.forEach { service ->
                        val result = catalogResults[service]
                        Text(service.label, Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleSmall)
                        when {
                            result == null -> Quiet(tr(R.string.prism_recording_searching))
                            result.failed -> Quiet(tr(R.string.prism_recording_error))
                            result.matches.isEmpty() -> Quiet(tr(R.string.prism_sc_no_versions))
                            else -> result.matches.forEach { hit ->
                                VersionRow(hit.song, listOfNotNull(tr(R.string.prism_external_open),
                                    hit.market, tr(R.string.prism_version_explicit_match).takeIf { hit.song.explicit })
                                    .joinToString(" · "), false, Modifier.testTag("catalog:${hit.song.id}:${hit.market}")) {
                                    runCatching { uriHandler.openUri(hit.url) }
                                }
                            }
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
                        label = { Text("Bandcamp / Zaycev.net URL") },
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
                                    checkedLink = withContext(Dispatchers.IO) {
                                        if (com.prism.music.data.stream.ZaycevVersions.id(requested.trim()) != null)
                                            c.streams.zaycevVersionFromLink(song, requested)
                                        else c.streams.bandcampVersionFromLink(song, requested)
                                    }
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
                        val source = if (com.prism.music.data.stream.ZaycevVersions.url(v.id) != null) "Zaycev.net" else "Bandcamp"
                        VersionRow(v, source, picked == v.id, Modifier.testTag("version:manual")) {
                            choose(SongVersions.Pick(v.id, "$source · " + versionLabel(v), v))
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
