package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.prism.music.connect.yandex.StationConnectAccount
import com.prism.music.connect.yandex.YandexLibraryImport
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun YandexTransferRow(ink: Color) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()

    var opened by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(true) }
    var playlists by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<YandexLibraryImport.Preview?>(null) }
    var previewSession by remember { mutableStateOf<Long?>(null) }
    val signedIn by StationConnectAccount.signedIn.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val likeOnAccount by c.imports.likeOnAccount.collectAsState()
    val nav = com.prism.music.ui.LocalNavigator.current
    val status by produceState<com.prism.music.data.imports.HistoryImports.YandexProgress?>(null) {
        while (true) {
            try { value = c.imports.yandexProgress() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            kotlinx.coroutines.delay(1500)
        }
    }

    fun close() {
        work?.cancel()
        work = null
        opened = false
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val p = status
        if (p == null) {
            Text(tr(R.string.prism_yandex_library_transfer), style = MaterialTheme.typography.titleMedium)
        } else if (p.lastRead > 0) {
            val complete = p.total > 0 && p.matched == p.total && p.pending == 0 && p.placementComplete && p.missingMetadata == 0
            Text(when {
                p.pending > 0 -> tr(R.string.prism_yandex_matching_tracks)
                complete -> tr(R.string.prism_yandex_library_transferred)
                p.total > 0 && p.matched == p.total && !p.placementComplete -> tr(R.string.prism_yandex_adding_to_library)
                p.matched == 0 && p.total > 0 -> tr(R.string.prism_yandex_no_confirmed_matches)
                else -> tr(R.string.prism_yandex_last_transfer_on_this_device)
            }, style = MaterialTheme.typography.titleMedium)
            if (p.total > 0) {
                LinearProgressIndicator(progress = { ((p.total - p.pending).toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(tr(R.string.prism_yandex_matched_value_of_value, p.matched, p.total), style = MaterialTheme.typography.bodyLarge)
            }
            Text(tr(R.string.prism_yandex_likes_added_value_playlists_value, p.likesPlaced, p.playlistsPlaced), style = MaterialTheme.typography.bodySmall)
            if (p.pending > 0) Text(tr(R.string.prism_yandex_pending_value_matching_continues_in_the_background_with_network_access, p.pending), style = MaterialTheme.typography.bodySmall)
            if (p.review + p.missing + p.skipped > 0) Text(tr(R.string.prism_yandex_review_value_not_found_value_skipped_value, p.review, p.missing, p.skipped), style = MaterialTheme.typography.bodySmall)
            if (p.missingMetadata > 0) Text(tr(R.string.prism_yandex_missing_source_metadata_value, p.missingMetadata), style = MaterialTheme.typography.bodySmall)
            Text(tr(R.string.prism_yandex_last_read, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.lastRead))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.review > 0) {
                TextButton(onClick = { nav.go("bring") }) { Text(tr(R.string.prism_yandex_review_matches)) }
            }
        } else {
            Text(tr(R.string.prism_yandex_library_transfer), style = MaterialTheme.typography.titleMedium)
            Text(tr(R.string.prism_yandex_liked_songs_and_your_playlists_from_yandex_music), style = MaterialTheme.typography.bodyMedium)
        }
        Button(enabled = signedIn, onClick = {
            preview = null; previewSession = null; message = null; done = false; busy = false; opened = true
        }, modifier = Modifier.fillMaxWidth().testTag("account:yandex:transfer")) {
            Text(if ((p?.lastRead ?: 0L) > 0L) tr(R.string.prism_yandex_refresh_library) else tr(R.string.prism_yandex_import_music))
        }
    }
    if (opened) AlertDialog(
        onDismissRequest = { if (!busy || preview == null) close() },
        title = { Text(tr(R.string.prism_yandex_import_music_2)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!done) {
                    Text(tr(R.string.prism_yandex_prism_matches_tracks_in_youtube_music_your_yandex_library_stays_unchan))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(liked, { liked = it; preview = null }, enabled = !busy)
                        Text(tr(R.string.prism_yandex_liked_songs))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(playlists, { playlists = it; preview = null }, enabled = !busy)
                        Text(tr(R.string.prism_yandex_my_playlists))
                    }
                    if (likeOnAccount && liked) Text(tr(R.string.prism_yandex_your_setting_to_copy_imported_likes_to_youtube_music_is_enabled))
                    preview?.let { p ->
                        Text(tr(R.string.prism_yandex_liked_value_playlists_value, p.library.liked.size, p.library.playlists.size))
                        Text(tr(R.string.prism_yandex_playlist_tracks_value, p.library.playlists.sumOf { it.tracks.size }))
                        if (p.missing > 0) Text(tr(R.string.prism_yandex_missing_metadata_value, p.missing))
                    }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress)
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = {
            if (done) TextButton(onClick = { close() }) { Text(tr(R.string.prism_yandex_done)) }
            else TextButton(enabled = !busy && (liked || playlists), onClick = {
                busy = true
                message = null
                val selected = preview
                progress = tr(R.string.prism_yandex_reading_library)
                work = scope.launch {
                    try {
                        if (selected == null) {
                            val accountSession = StationConnectAccount.sessionId() ?: error("Sign in first")
                            val api = StationConnectAccount.libraryImport() ?: error("Sign in first")
                            val result = api.read(liked, playlists) { progress = it }
                            check(StationConnectAccount.sessionId() == accountSession) { "Account changed" }
                            previewSession = accountSession
                            preview = result
                        } else {
                            if (previewSession == null || StationConnectAccount.sessionId() != previewSession) {
                                preview = null
                                previewSession = null
                                message = tr(R.string.prism_yandex_account_changed_preview_the_library_again)
                                return@launch
                            }
                            progress = tr(R.string.prism_yandex_queuing_import)
                            val news = c.imports.bringYandexLibrary(selected.library, selected.missing)
                            done = true
                            message = tr(R.string.prism_yandex_matching_queued_new_likes_value_playlists_value_additions_to_imported, news.liked, news.playlists, news.playlistSongs)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = tr(R.string.prism_yandex_library_import_failed_check_account_access_and_network) }
                    finally { busy = false }
                }
            }) { Text(if (preview == null) tr(R.string.prism_yandex_preview_library) else tr(R.string.prism_yandex_import)) }
        },
        dismissButton = { if (!done) TextButton(enabled = !busy || preview == null, onClick = { close() }) { Text(tr(R.string.prism_yandex_cancel)) } },
    )
}
