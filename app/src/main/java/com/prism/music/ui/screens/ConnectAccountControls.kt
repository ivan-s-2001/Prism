package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.prism.music.connect.CastState
import com.prism.music.connect.RendererKind
import com.prism.music.connect.yandex.ConnectDeviceClient
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun ConnectAccountControls(ink: Color) {
    val context = LocalContext.current
    val c = LocalContainer.current
    val cast by c.cast.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ru = java.util.Locale.getDefault().language == "ru"
    fun l(r: String, e: String) = if (ru) r else e
    var open by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val stationActive = (cast as? CastState.Casting)?.renderer?.kind == RendererKind.YANDEX_STATION ||
        (cast as? CastState.Connecting)?.renderer?.kind == RendererKind.YANDEX_STATION

    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        while (true) {
            try {
                status = ConnectDeviceClient.status(context)
                checked = true
                online = true
                if (status != null) code = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { checked = true; online = false }
            delay(1500)
        }
    }

    TextButton(onClick = { open = true; error = null; checked = false; online = false },
        modifier = Modifier.padding(start = 18.dp, bottom = 8.dp).testTag("account:connect")) {
        Text("Prism Connect · " + l("Алиса и устройства", "Alice and devices"), color = ink)
    }
    if (open) {
        val session = status?.get("session") as? JsonObject
        val generation = (session?.get("generation") as? JsonPrimitive)?.contentOrNull
        val snapshot = session?.get("state") as? JsonObject
        fun field(key: String) = (snapshot?.get(key) as? JsonPrimitive)?.contentOrNull
        val result = snapshot?.get("result") as? JsonObject
        val receivedId = (result?.get("id") as? JsonPrimitive)?.contentOrNull
        val outcome = (result?.get("status") as? JsonPrimitive)?.contentOrNull
        LaunchedEffect(receivedId, pendingId, outcome) {
            if (pendingId != null && receivedId == pendingId && outcome != null) {
                pendingId = null
                notice = when (outcome) {
                    "requested" -> l("Команда принята плеером; сверяем состояние Станции.", "Player received command; awaiting Station state.")
                    "not_found" -> l("Ничего не найдено. Очередь сохранена.", "Nothing found. Queue unchanged.")
                    "stale_track" -> l("Очередь изменилась. Выберите трек повторно.", "Queue changed. Select the track again.")
                    "unavailable" -> l("Действие сейчас недоступно.", "Action unavailable.")
                    else -> l("Не удалось выполнить команду.", "Command failed.")
                }
            }
        }
        LaunchedEffect(pendingId) {
            if (pendingId != null) {
                delay(12000)
                pendingId = null
                notice = l("Подтверждение не получено. Проверьте состояние.", "No confirmation received. Check state.")
            }
        }
        fun send(action: String, value: Int? = null, text: String? = null, index: Int? = null, trackId: String? = null) {
            val g = generation ?: return
            if (busy || !online) return
            busy = true
            error = null
            scope.launch {
                try {
                    pendingId = ConnectDeviceClient.command(g, buildJsonObject {
                        put("action", action)
                        value?.let { put("value", it) }
                        text?.let { put("query", it) }
                        index?.let { put("index", it) }
                        trackId?.let { put("track_id", it) }
                    })
                    notice = l("Команда отправлена.", "Command submitted.")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = l("Команда не принята. Проверьте соединение.", "Command not accepted. Check connection.") }
                finally { busy = false }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = { Text("Prism Connect") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!checked) {
                        CircularProgressIndicator()
                    } else if (!online) {
                        Text(l("Нет связи с сервером. Последнее состояние может быть устаревшим.", "Server unavailable. Last state may be stale."), color = MaterialTheme.colorScheme.error)
                    } else if (status == null) {
                        Text(l("Подтвердите этот телефон через ваш приватный навык Алисы. Общий токен не нужен.", "Approve this phone using your private Alice skill. No shared token is needed."))
                        val current = code
                        if (current != null) {
                            Text(current, style = MaterialTheme.typography.headlineMedium)
                            Text(l("В навыке «Призма муз» отправьте: подключи телефон " + current,
                                "In your private skill send: подключи телефон " + current))
                            Text(l("Код действует 3 минуты. Не сообщайте чужие коды.", "Code expires in 3 minutes. Never approve someone else's code."))
                        }
                        Button(enabled = !busy && !stationActive, onClick = {
                            busy = true; error = null
                            scope.launch {
                                try { code = ConnectDeviceClient.beginPair(context) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = l("Сервер Connect недоступен или код уже подтверждён.", "Connect unavailable or already paired.") }
                                finally { busy = false }
                            }
                        }) { Text(l("Получить код", "Get code")) }
                    } else if (generation == null) {
                        Text(l("Телефон подключён. Активной сессии нет.", "Phone paired. No active session."))
                        if ((status?.get("reserved") as? JsonPrimitive)?.booleanOrNull == true)
                            Text(l("Предыдущий источник зарезервирован. Отключите его корректно.", "Previous host is reserved. Disconnect it safely."))
                    } else {
                        Text(field("station") ?: l("Подключённая Станция", "Connected Station"))
                        Text(field("title") ?: l("Ожидание подтверждения от Станции", "Awaiting Station state"))
                        field("artist")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val position = field("position")?.toLongOrNull()
                        val duration = field("duration")?.toLongOrNull()
                        val currentTrack = field("track_id")
                        if (position != null && duration != null && duration > 0 && currentTrack != null) {
                            fun time(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
                            var draft by remember(currentTrack) { mutableStateOf<Float?>(null) }
                            Slider(value = draft ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                                onValueChange = { draft = it },
                                onValueChangeFinished = {
                                    draft?.let { send("seek_absolute", (it * duration / 1000).toInt(), trackId = currentTrack) }
                                    draft = null
                                }, enabled = !busy && !stationActive)
                            Text(time(position) + " / " + time(duration), style = MaterialTheme.typography.bodySmall)
                        }
                        val playing = (snapshot?.get("playing") as? JsonPrimitive)?.booleanOrNull
                        Text(when (playing) { true -> l("Воспроизведение", "Playing"); false -> l("Пауза / остановлено", "Paused / stopped"); null -> l("Состояние не подтверждено", "State unconfirmed") })
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = !busy && !stationActive, onClick = { send("previous") }) { Text(l("Назад", "Prev")) }
                            TextButton(enabled = !busy && !stationActive && playing != null, onClick = { send(if (playing == true) "pause" else "resume") }) {
                                Text(if (playing == true) l("Пауза", "Pause") else l("Играть", "Play"))
                            }
                            TextButton(enabled = !busy && !stationActive, onClick = { send("next") }) { Text(l("Дальше", "Next")) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = !busy && !stationActive, onClick = { send("volume_delta", -10) }) { Text("−10") }
                            Text(field("volume")?.let { "$it%" } ?: "—")
                            TextButton(enabled = !busy && !stationActive, onClick = { send("volume_delta", 10) }) { Text("+10") }
                        }
                        val entries = snapshot?.get("queue") as? JsonArray
                        if (entries != null && entries.isNotEmpty()) {
                            TextButton(onClick = { showQueue = !showQueue }) {
                                Text(l("Очередь", "Queue") + " · " + (field("queue_count") ?: entries.size.toString()))
                            }
                            if (showQueue) {
                                entries.forEach { item ->
                                    val entry = item as? JsonObject ?: return@forEach
                                    val idx = (entry["index"] as? JsonPrimitive)?.intOrNull ?: return@forEach
                                    val id = (entry["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                                    val title = (entry["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    val artist = (entry["artist"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    val selected = idx == field("current_index")?.toIntOrNull()
                                    Column(Modifier.fillMaxWidth().clickable(enabled = !busy && !stationActive && !selected) {
                                        send("select_track", index = idx, trackId = id)
                                    }.padding(vertical = 8.dp)) {
                                        Text((if (selected) "▶ " else "") + title.ifBlank { l("Трек", "Track") }, maxLines = 2)
                                        if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                        OutlinedTextField(query, { query = it.take(200) }, label = { Text(l("Песня или исполнитель", "Song or artist")) }, singleLine = true)
                        TextButton(enabled = !busy && !stationActive && query.isNotBlank(), onClick = { send("search_play", text = query.trim()) }) { Text(l("Включить", "Play search")) }
                        if (!stationActive) TextButton(enabled = !busy, onClick = { confirmDisconnect = true }) { Text(l("Отключить Станцию", "Disconnect Station")) }
                        if (confirmDisconnect) {
                            Text(l("Остановить воспроизведение на другом телефоне?", "Stop playback on the other phone?"))
                            Row {
                                TextButton(onClick = { confirmDisconnect = false; send("disconnect") }) { Text(l("Отключить", "Disconnect")) }
                                TextButton(onClick = { confirmDisconnect = false }) { Text(l("Отмена", "Cancel")) }
                            }
                        }
                    }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text(l("Готово", "Done")) } },
            dismissButton = {
                if (status != null && !stationActive) TextButton(enabled = !busy && online, onClick = {
                    busy = true
                    scope.launch {
                        try { ConnectDeviceClient.revoke(context); status = null; code = null }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = l("Не удалось отозвать доступ. Сначала остановите источник.", "Could not revoke. Stop host first.") }
                        finally { busy = false }
                    }
                }) { Text(l("Отвязать", "Unpair")) }
            }
        )
    }
}
