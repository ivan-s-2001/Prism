package com.prism.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import com.prism.music.ui.components.Artwork
import com.prism.music.data.model.SongItem
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.data.ExplicitFilter
import com.prism.music.data.foryou.MyWave
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** The Wave has its own route and can start before Home has ever been opened. */
@Composable
internal fun MyWaveScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val favorites by c.library.likedShown.collectAsState()
    val recent by c.library.recent.collectAsState()
    val online by c.network.online.collectAsState()
    val source by c.queue.source.collectAsState()
    val account = "${settings.authUser}|${settings.dataSyncId}|${settings.accountName}"
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember(account, online, retry) { mutableStateOf(online) }
    var failed by remember(account, online, retry) { mutableStateOf(false) }
    val recommendations by produceState(emptyList<Song>(), account, online, retry) {
        value = emptyList()
        try {
            if (!online) return@produceState
            val day = java.time.LocalDate.now().toString()
            val cached = c.homeCache.readPicks(day, account).orEmpty()
            if (cached.isNotEmpty()) { value = cached; return@produceState }
            // No parallel feed fetching while already listening to a Wave. The queue is filled by the service.
            if (source.isMyWave && retry == 0) return@produceState
            val fresh = kotlinx.coroutines.withTimeoutOrNull(7000L) {
                val shelves = c.ytm.fullHome(pages = 1, maxPages = 2,
                    satisfied = { earlyQuickPicks(it, settings.accountName) != null })
                val own = earlyQuickPicks(shelves, settings.accountName)?.items.orEmpty()
                    .filterIsInstance<SongItem>().map { it.song }
                if (own.isNotEmpty()) own else shelves.flatMap { it.items }.filterIsInstance<SongItem>().map { it.song }.distinctBy { it.id }.take(60)
            }.orEmpty()
            value = fresh
            // Keep first sound fast: a separate recommendation pass is bounded, never required for starting.
            if (!c.castingNow && !c.queue.source.value.isMyWave) {
                val curated = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    c.yourMusic.quickPicks(fresh, radioOf = { seed -> c.ytm.next(seed.id, playlistId = "RDAMVM${seed.id}").songs }, count = 36, draw = retry)
                }.orEmpty()
                if (curated.isNotEmpty()) value = curated
            }
            failed = value.isEmpty()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = value.isEmpty() }
        finally { loading = false }
    }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val heroHeight = (maxHeight - bottomPadding - 104.dp).coerceIn(320.dp, 540.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding + 16.dp)) {
            MyWaveCard(recommendations, favorites, recent, online, heroHeight, loading)
            if (failed && recommendations.isEmpty() && !source.isMyWave && online) {
                TextButton(onClick = { retry++ }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (java.util.Locale.getDefault().language == "ru") "Обновить рекомендации" else "Retry recommendations")
                }
            }
        }
    }
}

@Composable
internal fun MyWaveCard(recommendations: List<Song>, favorites: List<Song>, recent: List<Song>, online: Boolean,
    heroHeight: Dp = 420.dp, loading: Boolean = false) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val source by c.queue.source.collectAsState()
    val song by c.player.currentSong.collectAsState()
    val playing by c.player.isPlaying.collectAsState()
    val buffering by c.player.buffering.collectAsState()
    val wave = source.isMyWave && song != null
    val title = if (java.util.Locale.getDefault().language == "ru") "Моя волна" else "My Wave"
    val ru = java.util.Locale.getDefault().language == "ru"
    fun l(a: String, b: String) = if (ru) a else b
    var starting by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var style by remember(source.originId) { mutableStateOf(MyWave.style(source.originId)) }
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val moment = when (hour) {
        in 6..10 -> l("Бодрое утро", "Morning energy") to MyWave.Style(mood = MyWave.Mood.ENERGETIC)
        in 11..17 -> l("Время сосредоточиться", "Focus time") to MyWave.Style(mood = MyWave.Mood.CALM)
        else -> l("Спокойный вечер", "Wind down") to MyWave.Style(mood = MyWave.Mood.CALM)
    }
    fun characterName(v: MyWave.Character) = when (v) {
        MyWave.Character.BALANCED -> l("Всё", "All")
        MyWave.Character.FAMILIAR -> l("Любимое", "Familiar")
        MyWave.Character.DISCOVERY -> l("Незнакомое", "Discover")
    }
    fun moodName(v: MyWave.Mood) = when (v) {
        MyWave.Mood.ANY -> l("Любое", "Any")
        MyWave.Mood.CALM -> l("Спокойное", "Calm")
        MyWave.Mood.ENERGETIC -> l("Бодрое", "Energetic")
    }

    fun start(selected: MyWave.Style = style) {
        if (starting) return
        starting = true
        val previous = c.queue.source.value
        val seed = System.nanoTime()
        scope.launch {
            try {
                val songs = withContext(Dispatchers.Default) {
                    val base = if (online) recommendations else c.yourMusic.offlinePicks(60)
                    val known = c.library.likedIds.value
                    val signals = c.discover.radioSignals(base + favorites.take(100) + recent.take(60), selected.tune.copy(newness = 0.5f))
                    MyWave.start(base, favorites, recent, c.sessionHeard::has,
                        allowed = { s ->
                            !s.isEpisode && !s.isLive && !s.isVideo &&
                                (online || c.availableOffline(s.id)) &&
                                !c.library.isDisliked(s.id) && !c.exclusions.blocks(s) &&
                                !c.exclusions.isSongExcluded(s.id) &&
                                !c.recFeedback.turnedDown(s.id, known = s.id in known) &&
                                ExplicitFilter.plays(c.settings.current.behavior.explicitContent, s)
                        }, seed = seed, style = selected, signals = signals,
                        artistDoubt = c.recFeedback::artistDoubt,
                        servedRecently = { c.recFeedback.servedDaysAgo(it.id)?.let { days -> days <= 1 } == true })
                }
                // If the listener chose another queue while this was being prepared, it wins.
                if (c.queue.source.value !== previous) return@launch
                if (songs.isEmpty()) c.player.reportError(l("Нет доступных треков для волны. Попробуйте после загрузки рекомендаций.", "No tracks available for Wave. Try after recommendations load."))
                else {
                    c.recFeedback.served(songs.map { it.id })
                    c.player.playQueue(songs, 0, QueueSource(QueueKind.RADIO, title,
                        seedId = songs.first().id, originId = selected.origin))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { c.player.reportError(l("Не удалось запустить волну", "Could not start Wave")) }
            finally { starting = false }
        }
    }

    val waiting = loading && recommendations.isEmpty() && favorites.isEmpty() && recent.isEmpty() && !wave
    val pending = starting || waiting || (wave && buffering)
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val deep = Color(0xFF15151D)
    val subtitle = when {
        starting || waiting -> l("Подбираем музыку", "Finding your music")
        wave -> song?.let { "${it.title} · ${it.primaryArtist}" }.orEmpty()
        !online -> l("Твоя музыка без интернета", "Your music offline")
        else -> l("Музыка под тебя", "Music made for you")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .height(heroHeight).clip(ui.shape(32.dp))
            .background(Brush.linearGradient(listOf(deep, accent.copy(alpha = 0.40f), deep)))
            .testTag("home:my_wave")
    ) {
        // Use real album art from the active track, otherwise a recommendation, behind a high-contrast veil.
        val art = if (wave) song else recommendations.firstOrNull() ?: favorites.firstOrNull()
        if (art != null) {
            Artwork(c.covers.art(SongItem(art)), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), size = 400)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(deep.copy(alpha = 0.30f), deep.copy(alpha = 0.86f), deep))))
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            for (i in 0..10) {
                val path = Path()
                for (x in 0..100) {
                    val t = x / 100f
                    val y = h * (0.47f + i * 0.027f) + sin((t * 2.5 * PI) + i * 0.18).toFloat() * h * (0.13f + i * 0.006f)
                    if (x == 0) path.moveTo(0f, y) else path.lineTo(t * w, y)
                }
                drawPath(path, Brush.horizontalGradient(listOf(accent.copy(alpha = 0.12f),
                    accent.copy(alpha = 0.48f), Color.White.copy(alpha = 0.12f))), style = Stroke(1.4.dp.toPx()))
            }
        }
        Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(80.dp).clip(CircleShape).background(Color.White)
                .clickable(enabled = !starting && !waiting) { if (wave) c.player.togglePlay() else start() }
                .testTag("home:my_wave:play"), contentAlignment = Alignment.Center) {
                if (pending) CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp, color = Color(0xFF22254F))
                else Icon(if (wave && playing) PrismIcons.Pause else PrismIcons.Play,
                    if (wave && playing) l("Пауза", "Pause") else l("Слушать", "Play"),
                    tint = Color(0xFF22254F), modifier = Modifier.size(30.dp))
            }
        }
        if (wave) Box(Modifier.align(Alignment.TopStart).padding(12.dp).size(42.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)).clickable(enabled = !starting) { start() }.testTag("home:my_wave:refresh"),
            contentAlignment = Alignment.Center) {
            Icon(PrismIcons.Refresh, l("Новая волна", "New Wave"), tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(42.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f)).clickable(enabled = !starting) { settingsOpen = true }
            .testTag("home:my_wave:settings"), contentAlignment = Alignment.Center) {
            Icon(PrismIcons.Equalizer, l("Настроить волну", "Tune Wave"), tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (wave) {
                Text(characterName(style.character) + " · " + moodName(style.mood), color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1)
            } else {
                Text(l("Сейчас", "For now"), color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
                Text(moment.first, Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.16f))
                    .clickable(enabled = !starting) { style = moment.second; start(moment.second) }.padding(horizontal = 13.dp, vertical = 8.dp),
                    color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(l("Для себя", "For you") to MyWave.Style(), l("В движении", "On the move") to MyWave.Style(mood = MyWave.Mood.ENERGETIC), l("Отдохнуть", "Unwind") to MyWave.Style(mood = MyWave.Mood.CALM)).forEach { (label, preset) ->
            Surface(modifier = Modifier.weight(1f).clip(ui.shape(18.dp)).clickable(enabled = !starting && !waiting) { style = preset; start(preset) },
                shape = ui.shape(18.dp), color = if (wave && style == preset) scheme.primaryContainer else scheme.surfaceContainerHigh) {
                Text(label, Modifier.padding(horizontal = 6.dp, vertical = 16.dp), style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    }
    if (settingsOpen) {
        var draft by remember { mutableStateOf(style) }
        AlertDialog(onDismissRequest = { settingsOpen = false },
            title = { Text(l("Настроить волну", "Tune your Wave")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(l("Характер музыки", "Music discovery"), style = MaterialTheme.typography.titleSmall)
                    MyWave.Character.entries.forEach { value ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { draft = draft.copy(character = value) },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = draft.character == value, onClick = { draft = draft.copy(character = value) })
                            Text(characterName(value))
                        }
                    }
                    Text(l("Настроение", "Mood"), style = MaterialTheme.typography.titleSmall)
                    MyWave.Mood.entries.forEach { value ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { draft = draft.copy(mood = value) },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = draft.mood == value, onClick = { draft = draft.copy(mood = value) })
                            Text(moodName(value))
                        }
                    }
                    if (draft.mood != MyWave.Mood.ANY) Text(l("Настроение учитывается по измеренным темпу и громкости, если они известны.",
                        "Mood uses measured tempo and loudness when available."), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false; style = draft; start(draft) }) { Text(l("Включить", "Play")) } },
            dismissButton = { TextButton(onClick = { draft = MyWave.Style() }) { Text(l("Сбросить", "Reset")) } })
    }
}
