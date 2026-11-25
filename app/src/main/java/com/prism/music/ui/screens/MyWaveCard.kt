package com.prism.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.prism.music.ui.components.Artwork
import com.prism.music.data.model.SongItem
import androidx.compose.ui.graphics.Brush
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
import com.prism.music.data.foryou.WaveScenarios
import com.prism.music.data.foryou.LocalRadio
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.vector.ImageVector
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
        val heroHeight = (maxHeight - bottomPadding - 224.dp).coerceIn(220.dp, 440.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val calendar = java.util.Calendar.getInstance()
    val dayScenarios = WaveScenarios.forDay(calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.DAY_OF_WEEK) in listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY))
    var selectionJob by remember { mutableStateOf<Job?>(null) }
    var requestVersion by remember { mutableIntStateOf(0) }
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

    fun activityName(v: MyWave.Activity) = when (v) {
        MyWave.Activity.ANY -> l("Любое", "Any")
        MyWave.Activity.WAKE -> l("Просыпаюсь", "Waking up")
        MyWave.Activity.ROAD -> l("В дороге", "On the road")
        MyWave.Activity.WORK -> l("Работаю", "Focus")
        MyWave.Activity.TRAIN -> l("Тренируюсь", "Workout")
        MyWave.Activity.SLEEP -> l("Засыпаю", "Sleep")
    }

    fun start(selected: MyWave.Style = style) {
        selectionJob?.cancel()
        val version = ++requestVersion
        style = selected
        starting = true
        val previous = c.queue.source.value
        val seed = System.nanoTime()
        val current = c.player.currentSong.value.takeIf { previous.isMyWave }
        selectionJob = scope.launch {
            try {
                val songs = c.yourMusic.waveBatch(recommendations, selected, offset = 0,
                    previous = current, existing = emptyList())
                // Latest selection wins; a slow response cannot restore an older set of filters.
                if (!isActive || version != requestVersion || c.queue.source.value !== previous) return@launch
                val now = c.player.currentSong.value.takeIf { previous.isMyWave }
                val nextSongs = songs.filter { candidate -> now == null || LocalRadio.sameSong(candidate) != LocalRadio.sameSong(now) }
                if (nextSongs.isEmpty()) {
                    style = MyWave.style(previous.originId)
                    c.player.reportError(l("Недостаточно подходящих треков для выбранной пропорции. Измени процент или проверь подключение.", "Not enough tracks for the selected mix. Change the percentage or check your connection."))
                } else {
                    val next = QueueSource(QueueKind.RADIO, title, seedId = nextSongs.first().id, originId = selected.origin)
                    c.recFeedback.served(nextSongs.map { it.id })
                    c.player.playQueue(nextSongs, 0, next)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (version == requestVersion && c.queue.source.value === previous) {
                    style = MyWave.style(previous.originId)
                    c.player.reportError(l("Не удалось обновить волну", "Could not update Wave"))
                }
            } finally {
                if (version == requestVersion) starting = false
            }
        }
    }

    val waiting = loading && recommendations.isEmpty() && favorites.isEmpty() && recent.isEmpty() && !wave
    val pending = starting || waiting || (wave && buffering)
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val liked by c.library.likedIds.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    val hasNext by c.player.hasNext.collectAsState()
    val current = song.takeIf { wave }
    fun depthName(v: MyWave.Depth) = when (v) {
        MyWave.Depth.ANY -> l("Всё", "All")
        MyWave.Depth.HITS -> l("Хиты", "Hits")
        MyWave.Depth.DEEP -> l("Редкое", "Deep cuts")
    }
    val activeFilters = listOfNotNull(
        activityName(style.activity).takeIf { style.activity != MyWave.Activity.ANY },
        l("${style.discoveryPercent}% открытий", "${style.discoveryPercent}% discoveries"),
        moodName(style.mood).takeIf { style.mood != MyWave.Mood.ANY },
        depthName(style.depth).takeIf { style.depth != MyWave.Depth.ANY },
        l("Разнообразие", "Variety").takeIf { style.diversity != 50 },
    )
    val status = when {
        waiting -> l("Загружаем рекомендации", "Loading recommendations")
        starting && wave -> l("Обновляем подбор", "Updating your mix")
        starting -> l("Подбираем музыку", "Finding your music")
        wave && buffering -> l("Загрузка", "Buffering")
        wave && playing -> l("Играет", "Playing")
        wave -> l("На паузе", "Paused")
        !online -> l("Офлайн", "Offline")
        else -> l("Персональный поток", "Personal radio")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(status, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { settingsOpen = true }, enabled = !starting,
                modifier = Modifier.testTag("home:my_wave:settings")) {
                Icon(PrismIcons.Equalizer, l("Настроить волну", "Tune Wave"))
            }
        }

        // One clear playback target. No full-screen album-art scrim, duplicate title or decorative status text.
        Box(Modifier.fillMaxWidth().height(heroHeight).clip(ui.shape(32.dp))
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.24f), scheme.surfaceContainerHigh, scheme.surface)))
            .testTag("home:my_wave"), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = minOf(size.width, size.height) * 0.36f
                for (n in 0..5) {
                    val r = radius * (0.63f + n * 0.13f)
                    val path = Path()
                    for (j in 0..144) {
                        val angle = j / 144.0 * 2.0 * PI
                        val variation = 1.0 + 0.045 * sin(angle * 3 + n * 0.7) + 0.025 * sin(angle * 5 - n * 0.4)
                        val x = center.x + (kotlin.math.cos(angle) * r * variation).toFloat()
                        val y = center.y + (sin(angle) * r * variation).toFloat()
                        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    drawPath(path, accent.copy(alpha = if (wave && playing) 0.46f - n * 0.045f else 0.28f - n * 0.025f),
                        style = Stroke(1.5.dp.toPx()))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val playLabel = if (wave && playing) l("Пауза", "Pause") else if (wave) l("Продолжить", "Resume") else l("Слушать", "Play")
                FilledIconButton(
                    onClick = { if (wave) c.player.togglePlay() else start() },
                    enabled = wave || (!starting && !waiting),
                    modifier = Modifier.size(88.dp).testTag("home:my_wave:play"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = scheme.onPrimary)
                ) {
                    if (pending && !wave) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                    else Icon(if (wave && playing) PrismIcons.Pause else PrismIcons.Play, playLabel, Modifier.size(36.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(playLabel, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
        }

        // Track feedback and skip belong next to the current recording, not in another settings screen.
        if (current != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(c.covers.art(SongItem(current)), Modifier.size(54.dp), ui.shape(12.dp), size = 160)
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.primaryArtist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { c.library.toggleLike(current) }) {
                    val on = current.id in liked
                    Icon(if (on) PrismIcons.HeartFilled else PrismIcons.Heart,
                        if (on) l("Убрать лайк", "Remove like") else l("Нравится", "Like"),
                        tint = if (on) accent else scheme.onSurface)
                }
                IconButton(onClick = { c.player.next() }, enabled = hasNext) {
                    Icon(PrismIcons.Next, l("Следующий трек", "Next track"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val on = current.id in disliked
                    c.library.setDisliked(current, !on)
                    if (!on && c.player.hasNext.value) c.player.next()
                }, modifier = Modifier.weight(1f)) {
                    Icon(if (current.id in disliked) PrismIcons.ThumbDownFilled else PrismIcons.ThumbDown, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(l("Не моё", "Not for me"), maxLines = 1)
                }
                OutlinedButton(onClick = { start() }, enabled = !starting, modifier = Modifier.weight(1f).testTag("home:my_wave:refresh")) {
                    Icon(PrismIcons.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(l("Встряхнуть", "Refresh mix"), maxLines = 1)
                }
            }
        }

        // Fast access to all current filters; no hidden selection state.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(l("Настройка волны", "Tune your Wave"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { settingsOpen = true }, enabled = !starting) { Text(l("Изменить", "Edit")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (activeFilters.isEmpty()) {
                AssistChip(onClick = { settingsOpen = true }, label = { Text(l("Всё, что люблю", "Made for me")) }, enabled = !starting)
            } else {
                activeFilters.forEach { label ->
                    FilterChip(selected = true, onClick = { settingsOpen = true }, label = { Text(label) }, enabled = !starting)
                }
                AssistChip(onClick = { start(MyWave.Style()) }, enabled = !starting && !waiting, label = { Text(l("Сбросить", "Reset")) })
            }
        }

        WaveScenarioRow(l("Под настроение дня", "For your day"), dayScenarios, style, ru, !waiting, ::start)
        WaveScenarioRow(l("Для любого момента", "For every moment"), WaveScenarios.all.filter { it.group == WaveScenarios.Group.ACTIVITY }, style, ru, !waiting, ::start)
        WaveScenarioRow(l("По музыкальному вкусу", "Your taste and discoveries"), WaveScenarios.all.filter { it.group == WaveScenarios.Group.DISCOVERY }, style, ru, !waiting, ::start)
    }

    if (settingsOpen) {
        var diversity by remember(style.diversity) { mutableFloatStateOf(style.diversity.toFloat()) }
        var discovery by remember(style.discoveryPercent) { mutableFloatStateOf(style.discoveryPercent.toFloat()) }
        ModalBottomSheet(onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = scheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(l("Настроить волну", "Tune your Wave"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (style != MyWave.Style()) start(MyWave.Style()) }) { Text(l("Сбросить", "Reset")) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WaveChoices(l("Занятие", "Activity"), MyWave.Activity.entries, style.activity, ::activityName) { if (it != style.activity) start(style.copy(activity = it)) }
                    Text(l("Характер волны", "Discovery mix"), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("${100 - discovery.toInt()}%", style = MaterialTheme.typography.headlineSmall)
                            Text(l("Лайки", "Liked songs"), style = MaterialTheme.typography.labelMedium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${discovery.toInt()}%", style = MaterialTheme.typography.headlineSmall, color = scheme.primary)
                            Text(l("Открытия", "Discoveries"), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    fun selectPercent(percent: Int) {
                        if (percent != style.discoveryPercent) start(style.copy(discoveryPercent = percent,
                            character = when (percent) {
                                0 -> MyWave.Character.FAMILIAR
                                100 -> MyWave.Character.DISCOVERY
                                else -> MyWave.Character.BALANCED
                            }))
                    }
                    Slider(value = discovery, onValueChange = { discovery = it },
                        onValueChangeFinished = { selectPercent((kotlin.math.round(discovery / 5f).toInt() * 5).coerceIn(0, 100)) },
                        valueRange = 0f..100f, steps = 19, modifier = Modifier.testTag("wave:discovery-percent"))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to l("Лайки", "Liked"), 50 to l("Баланс", "Balance"),
                            70 to l("Для меня", "For me"), 100 to l("Новое", "New")).forEach { (percent, label) ->
                            FilterChip(selected = discovery.toInt() == percent, onClick = { discovery = percent.toFloat(); selectPercent(percent) },
                                label = { Text(label) })
                        }
                    }
                    Text(l("Открытия — похожие записи, которых нет в известной Prism истории. Лайки не заменяют их при нехватке.",
                        "Discoveries are related recordings not in Prism's known history. Likes do not replace missing discoveries."),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    WaveChoices(l("Настроение", "Mood"), MyWave.Mood.entries, style.mood, ::moodName) { if (it != style.mood) start(style.copy(mood = it)) }
                    WaveChoices(l("Популярность", "Popularity"), MyWave.Depth.entries, style.depth, ::depthName) { if (it != style.depth) start(style.copy(depth = it)) }
                    Text(l("Разнообразие", "Variety"), style = MaterialTheme.typography.titleSmall)
                    Slider(value = diversity, onValueChange = { diversity = it },
                        onValueChangeFinished = { if (diversity.toInt() != style.diversity) start(style.copy(diversity = diversity.toInt())) },
                        valueRange = 0f..100f, steps = 9)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(l("Похожее", "Similar"), style = MaterialTheme.typography.labelSmall)
                        Text(l("Разное", "Varied"), style = MaterialTheme.typography.labelSmall)
                    }
                    if (style.mood != MyWave.Mood.ANY || style.activity != MyWave.Activity.ANY)
                        Text(l("Темп и громкость учитываются, если измерены.", "Uses measured tempo and loudness when available."),
                            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (style.depth != MyWave.Depth.ANY)
                        Text(l("По топ-трекам исполнителей, если доступны.", "Based on artist top songs when available."),
                            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (starting) l("Обновляем волну", "Updating Wave") else l("Настройки применяются сразу", "Changes play immediately"),
                        Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    TextButton(onClick = { settingsOpen = false }) { Text(l("Готово", "Done")) }
                }
            }
        }
    }
}

@Composable
private fun <T> WaveChoices(label: String, values: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        values.chunked(if (values.size > 3) 2 else 3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { value ->
                    FilterChip(selected = value == selected, onClick = { onSelect(value) },
                        label = { Text(name(value), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
        }
    }
}


@Composable
private fun WaveScenarioRow(title: String, scenarios: List<WaveScenarios.Scenario>, style: MyWave.Style, ru: Boolean,
    enabled: Boolean, onSelect: (MyWave.Style) -> Unit) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Text(title, style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        scenarios.forEach { scenario ->
            val glyph: ImageVector = when (scenario.glyph) {
                WaveScenarios.Glyph.SUN -> PrismIcons.Sun
                WaveScenarios.Glyph.MOON -> PrismIcons.Moon
                WaveScenarios.Glyph.CAR -> PrismIcons.Car
                WaveScenarios.Glyph.WORK -> PrismIcons.Headphones
                WaveScenarios.Glyph.ENERGY -> PrismIcons.Flame
                WaveScenarios.Glyph.HEART -> PrismIcons.Heart
                WaveScenarios.Glyph.STAR -> PrismIcons.Star
                WaveScenarios.Glyph.DISCOVER -> PrismIcons.SmartShuffle
                WaveScenarios.Glyph.RADIO -> PrismIcons.Radio
            }
            val selected = style == scenario.style
            Surface(modifier = Modifier.width(152.dp).height(106.dp).clip(ui.shape(18.dp))
                .clickable(enabled = enabled) { onSelect(scenario.style) }.testTag("wave:scenario:${scenario.id}"),
                shape = ui.shape(18.dp), color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(glyph, null, Modifier.size(25.dp), tint = if (selected) scheme.onPrimaryContainer else scheme.primary)
                    Text(if (ru) scenario.ru else scenario.en, style = MaterialTheme.typography.labelLarge,
                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
