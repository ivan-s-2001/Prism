package com.prism.music.ui.screens

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
    val russian = java.util.Locale.getDefault().language == "ru"
    fun l(ru: String, en: String) = if (russian) ru else en
    var opened by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(true) }
    var playlists by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<YandexLibraryImport.Preview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val likeOnAccount by c.imports.likeOnAccount.collectAsState()

    fun close() {
        work?.cancel()
        work = null
        opened = false
    }

    TextButton(onClick = {
        preview = null; message = null; done = false; busy = false; opened = true
    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("account:yandex:transfer")) {
        Text(l("Перенести музыку из Яндекс Музыки", "Import from Yandex Music"), color = ink)
    }
    if (opened) AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(l("Перенос музыки", "Import music")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!done) {
                    Text(l("Prism найдёт соответствующие треки в YouTube Music. Медиатека Яндекса останется без изменений.",
                        "Prism matches tracks in YouTube Music. Your Yandex library stays unchanged."))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(liked, { liked = it; preview = null }, enabled = !busy)
                        Text(l("Любимые треки", "Liked songs"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(playlists, { playlists = it; preview = null }, enabled = !busy)
                        Text(l("Мои плейлисты", "My playlists"))
                    }
                    if (likeOnAccount && liked) Text(l("Включена настройка добавления импортированных лайков в аккаунт YouTube Music.",
                        "Your setting to copy imported likes to YouTube Music is enabled."))
                    preview?.let { p ->
                        Text(l("Любимые: ${p.library.liked.size} · Плейлисты: ${p.library.playlists.size}", "Liked: ${p.library.liked.size} · Playlists: ${p.library.playlists.size}"))
                        Text(l("Треков в плейлистах: ${p.library.playlists.sumOf { it.tracks.size }}", "Playlist tracks: ${p.library.playlists.sumOf { it.tracks.size }}"))
                        if (p.missing > 0) Text(l("Без метаданных: ${p.missing}", "Missing metadata: ${p.missing}"))
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
            if (done) TextButton(onClick = { close() }) { Text(l("Готово", "Done")) }
            else TextButton(enabled = !busy && (liked || playlists), onClick = {
                busy = true
                message = null
                val selected = preview
                progress = l("Читаем медиатеку…", "Reading library…")
                work = scope.launch {
                    try {
                        if (selected == null) {
                            val api = StationConnectAccount.libraryImport() ?: error("Sign in first")
                            preview = api.read(liked, playlists) { progress = it }
                        } else {
                            progress = l("Добавляем в очередь импорта…", "Queuing import…")
                            val news = c.imports.bringYandexLibrary(selected.library)
                            done = true
                            message = l("Поставлено на сопоставление. Новых любимых: ${news.liked}, плейлистов: ${news.playlists}, новых треков в ранее перенесённых плейлистах: ${news.playlistSongs}. Неоднозначные совпадения можно проверить в разделе импорта.",
                                "Matching queued. New likes: ${news.liked}, playlists: ${news.playlists}, additions to imported playlists: ${news.playlistSongs}. Review uncertain matches in Imports.")
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = l("Не удалось прочитать или перенести медиатеку. Проверьте авторизацию и сеть.", "Library import failed. Check account access and network.") }
                    finally { busy = false }
                }
            }) { Text(if (preview == null) l("Проверить медиатеку", "Preview library") else l("Перенести", "Import")) }
        },
        dismissButton = { if (!done) TextButton(enabled = !busy, onClick = { close() }) { Text(l("Отмена", "Cancel")) } },
    )
}
