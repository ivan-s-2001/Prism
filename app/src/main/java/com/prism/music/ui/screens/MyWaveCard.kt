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
internal fun MyWaveScreen(bottomPadding: Dp, onOpenPlayer: () -> Unit = {}) {
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
    LaunchedEffect(account, online, retry, favorites) {
        if (!source.isMyWave) try { c.yourMusic.warmWave() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Start can still use the existing feed. */ }
    }
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
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomPadding)) {
        MyWaveCard(recommendations, favorites, recent, online, loading = loading,
            onRetry = if (failed && recommendations.isEmpty() && !source.isMyWave && online) ({ retry++ }) else null,
            onOpenPlayer = onOpenPlayer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyWaveCard(recommendations: List<Song>, favorites: List<Song>, recent: List<Song>, online: Boolean,
    heroHeight: Dp = 420.dp, loading: Boolean = false, onRetry: (() -> Unit)? = null, onOpenPlayer: () -> Unit = {}) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val source by c.queue.source.collectAsState()
    val song by c.player.currentSong.collectAsState()
    val playing by c.player.isPlaying.collectAsState()
    val buffering by c.player.buffering.collectAsState()
    val wave = source.isMyWave && song != null
    val title = if (java.util.Locale.getDefault().language == "ru") "Поток" else "Stream"
    val ru = java.util.Locale.getDefault().language == "ru"
    fun l(a: String, b: String) = if (ru) a else b
    var starting by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var scenariosOpen by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf(WaveScenarios.Group.DAY) }
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
                val began = android.os.SystemClock.elapsedRealtime()
                val quick = c.yourMusic.waveBatch(recommendations, selected, offset = 0,
                    previous = current, existing = emptyList(), count = 2, fast = true)
                // A cold/empty cache may need network access. Never wait for a full queue when one song is ready.
                val songs = quick.ifEmpty {
                    c.yourMusic.waveBatch(recommendations, selected, offset = 0,
                        previous = current, existing = emptyList(), count = 2)
                }
                android.util.Log.i("PrismWave", "first selection: ${android.os.SystemClock.elapsedRealtime() - began}ms; tracks=${songs.size}")
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
                    c.player.reportError(l("Не удалось обновить поток", "Could not update Stream"))
                }
            } finally {
                if (version == requestVersion) starting = false
            }
        }
    }

    val waiting = false // A feed refresh must not disable play; start resolves a cache miss itself.
    val pending = starting || waiting || (wave && buffering)
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val liked by c.library.likedIds.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    val hasNext by c.player.hasNext.collectAsState()
    val hasPrevious by c.player.hasPrevious.collectAsState()
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
        else -> l("Персональный поток", "Personal stream")
    }
    val activeScenario = WaveScenarios.all.firstOrNull { it.style == style }
    val quickScenarios = when (group) {
        WaveScenarios.Group.DAY -> dayScenarios
        WaveScenarios.Group.ACTIVITY -> WaveScenarios.all.filter { it.group == group }
        WaveScenarios.Group.DISCOVERY -> WaveScenarios.all.filter { it.group == group }
    }
    @Composable fun Header() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                Text(status, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            IconButton(onClick = { settingsOpen = true }, modifier = Modifier.testTag("home:my_wave:settings")) {
                Icon(PrismIcons.Equalizer, l("Настроить поток", "Tune Stream"))
            }
        }
    }
    @Composable fun Hero(modifier: Modifier, compact: Boolean) {
        Box(modifier.clip(ui.shape(28.dp))
            .background(Brush.verticalGradient(listOf(scheme.surfaceContainerHigh, accent.copy(alpha = 0.17f), scheme.surface)))
            .testTag("home:my_wave"), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = if (playing && wave) 0.36f else 0.22f),
                    accent.copy(alpha = 0.04f), androidx.compose.ui.graphics.Color.Transparent),
                    center = center, radius = maxOf(width, height) * 0.6f), radius = maxOf(width, height) * 0.6f)
                for (n in 0..7) {
                    val path = Path()
                    val offset = n * height * 0.042f
                    for (j in 0..100) {
                        val t = j / 100f
                        val envelope = sin(t * PI).toFloat()
                        val y = height * 0.33f + offset +
                            (sin(t * 2.0 * PI + n * 0.35) * 0.12 * height * envelope).toFloat()
                        if (j == 0) path.moveTo(t * width, y) else path.lineTo(t * width, y)
                    }
                    drawPath(path, accent.copy(alpha = (if (playing && wave) 0.32f else 0.19f) - n * 0.016f),
                        style = Stroke((if (n % 2 == 0) 2.dp else 1.dp).toPx()))
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
                Text(activeScenario?.let { if (ru) it.ru else it.en } ?: l("Музыка в твоём ритме", "Music in your rhythm"),
                    color = scheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = { c.player.previous() }, enabled = wave && hasPrevious) {
                        Icon(PrismIcons.Previous, l("Предыдущий трек", "Previous track"))
                    }
                    FilledIconButton(onClick = { if (wave) c.player.togglePlay() else start() },
                        enabled = wave || !starting, modifier = Modifier.size(if (compact) 58.dp else 76.dp).testTag("home:my_wave:play"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = scheme.onPrimary)) {
                        if (pending && !wave) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                        else Icon(if (wave && playing) PrismIcons.Pause else PrismIcons.Play,
                            if (wave && playing) l("Пауза", "Pause") else l("Слушать", "Play"), Modifier.size(32.dp))
                    }
                    IconButton(onClick = { c.player.next() }, enabled = wave && hasNext) {
                        Icon(PrismIcons.Next, l("Следующий трек", "Next track"))
                    }
                }
            }
        }
    }
    @Composable fun Track() {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current != null) {
                Artwork(c.covers.art(SongItem(current)), Modifier.size(44.dp).clickable(onClick = onOpenPlayer), ui.shape(10.dp), size = 160)
                Column(Modifier.weight(1f).clickable(onClick = onOpenPlayer)) {
                    Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.primaryArtist, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { c.library.toggleLike(current) }) {
                    val on = current.id in liked
                    Icon(if (on) PrismIcons.HeartFilled else PrismIcons.Heart, l("Нравится", "Like"), tint = if (on) accent else scheme.onSurface)
                }
                IconButton(onClick = {
                    val on = current.id in disliked
                    c.library.setDisliked(current, !on)
                    if (!on && c.player.hasNext.value) c.player.next()
                }) { Icon(if (current.id in disliked) PrismIcons.ThumbDownFilled else PrismIcons.ThumbDown, l("Не моё", "Not for me")) }
            } else {
                Text(l("Персональные рекомендации", "Personal recommendations"), style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { if (onRetry != null && !wave) onRetry() else start() },
                enabled = !starting, modifier = Modifier.testTag("home:my_wave:refresh")) {
                Icon(PrismIcons.Refresh, l("Обновить поток", "Refresh Stream"))
            }
        }
    }
    @Composable fun Controls(compact: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(l("Сценарии", "Scenarios"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { scenariosOpen = true }) { Text(l("Все", "All")) }
            }
            if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(WaveScenarios.Group.DAY to l("День", "Day"), WaveScenarios.Group.ACTIVITY to l("Дела", "Activity"),
                    WaveScenarios.Group.DISCOVERY to l("Вкус", "Taste")).forEach { (value, label) ->
                    FilterChip(selected = group == value, onClick = { group = value }, modifier = Modifier.weight(1f),
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickScenarios.take(3).forEach { scenario ->
                    Surface(onClick = { start(scenario.style) }, modifier = Modifier.weight(1f).height(if (compact) 60.dp else 82.dp)
                        .testTag("wave:scenario:${scenario.id}"), shape = ui.shape(16.dp),
                        color = if (style == scenario.style) scheme.primaryContainer else scheme.surfaceContainerHigh) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.Center) {
                            if (!compact) {
                                val glyph = when (scenario.glyph) {
                                    WaveScenarios.Glyph.SUN -> PrismIcons.Sun
                                    WaveScenarios.Glyph.MOON -> PrismIcons.Moon
                                    WaveScenarios.Glyph.CAR -> PrismIcons.Car
                                    WaveScenarios.Glyph.WORK -> PrismIcons.Headphones
                                    WaveScenarios.Glyph.ENERGY -> PrismIcons.Flame
                                    WaveScenarios.Glyph.HEART -> PrismIcons.Heart
                                    WaveScenarios.Glyph.STAR -> PrismIcons.Star
                                    WaveScenarios.Glyph.DISCOVER -> PrismIcons.SmartShuffle
                                    WaveScenarios.Glyph.RADIO -> PrismIcons.Equalizer
                                }
                                Icon(glyph, null, Modifier.size(20.dp), tint = accent)
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(if (ru) scenario.ru else scenario.en, style = MaterialTheme.typography.labelLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(ui.shape(14.dp)).clickable { settingsOpen = true }
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l("Характер потока", "Discovery mix"), style = MaterialTheme.typography.labelLarge)
                    Text(l("${100 - style.discoveryPercent}% лайки · ${style.discoveryPercent}% новое",
                        "${100 - style.discoveryPercent}% liked · ${style.discoveryPercent}% new"),
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(PrismIcons.Equalizer, l("Настройки", "Settings"))
            }
        }
    }
    // The route already reserves system bars, bottom tabs and the mini player. No scroll container.
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp)) {
        val compact = maxHeight < 520.dp
        val wide = maxWidth >= 600.dp || maxWidth > maxHeight * 1.25f
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Header()
                    Hero(Modifier.weight(1f).fillMaxWidth(), true)
                    Track()
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Controls(true)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Header()
                Hero(Modifier.weight(1f).fillMaxWidth(), compact)
                Track()
                Controls(compact)
            }
        }
    }
    if (scenariosOpen) {
        ModalBottomSheet(onDismissRequest = { scenariosOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(l("Сценарии потока", "Stream scenarios"), style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(WaveScenarios.Group.DAY to l("День", "Day"), WaveScenarios.Group.ACTIVITY to l("Дела", "Activity"),
                        WaveScenarios.Group.DISCOVERY to l("Вкус", "Taste")).forEach { (value, label) ->
                        FilterChip(selected = group == value, onClick = { group = value }, modifier = Modifier.weight(1f), label = { Text(label) })
                    }
                }
                quickScenarios.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { scenario ->
                            FilterChip(selected = style == scenario.style, onClick = { scenariosOpen = false; start(scenario.style) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                label = { Text(if (ru) scenario.ru else scenario.en, maxLines = 2) })
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                TextButton(onClick = { scenariosOpen = false }, modifier = Modifier.align(Alignment.End)) { Text(l("Готово", "Done")) }
            }
        }
    }

    if (settingsOpen) {
        var diversity by remember(style.diversity) { mutableFloatStateOf(style.diversity.toFloat()) }
        var discovery by remember(style.discoveryPercent) { mutableFloatStateOf(style.discoveryPercent.toFloat()) }
        ModalBottomSheet(onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = scheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(l("Настроить поток", "Tune your Stream"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (style != MyWave.Style()) start(MyWave.Style()) }) { Text(l("Сбросить", "Reset")) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WaveChoices(l("Занятие", "Activity"), MyWave.Activity.entries, style.activity, ::activityName) { if (it != style.activity) start(style.copy(activity = it)) }
                    Text(l("Характер потока", "Discovery mix"), style = MaterialTheme.typography.titleSmall)
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
                    Text(if (starting) l("Обновляем поток", "Updating Stream") else l("Настройки применяются сразу", "Changes play immediately"),
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

