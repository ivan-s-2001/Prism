package com.prism.music.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.PrismScenarioDisc
import com.prism.music.ui.components.scenarioEdgeFade
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.pageTitleStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.prism.music.data.model.hiRes
import com.prism.music.ui.player.playerCoverRequest
import com.prism.music.ui.player.pausedCoverScale
import com.prism.music.ui.player.ThemeBackdrop
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.prism.music.ui.player.rememberFullArtworkBlur
import com.prism.music.ui.player.FullArtworkBlurBackdrop
import com.prism.music.ui.player.TransportRow
import com.prism.music.ui.player.PlayerScrubber
import com.prism.music.ui.player.CircleGlyph
import com.prism.music.ui.player.playerTitleStyle
import com.prism.music.ui.player.playerArtistStyle
import com.prism.music.data.model.SongItem
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
    val settings = LocalAppSettings.current
    fun restoredStyle(origin: String?) = MyWave.style(origin).let {
        if (MyWave.isOrigin(origin)) it else it.copy(russianPercent = settings.streamRussianPercent)
    }
    var style by remember(source.originId) { mutableStateOf(restoredStyle(source.originId)) }
    val scenarioOrder = remember {
        val calendar = java.util.Calendar.getInstance()
        WaveScenarios.stripOrder(calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.DAY_OF_WEEK) in listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY))
    }
    val scenarioScroll = androidx.compose.foundation.lazy.rememberLazyListState()
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
                    .ifEmpty { if (!c.network.isOnline()) songs else emptyList() }
                if (nextSongs.isEmpty()) {
                    style = restoredStyle(previous.originId)
                    c.player.reportError(if (!c.network.isOnline()) l("Нет подходящих скачанных треков", "No matching downloaded tracks") else l("Недостаточно подходящих треков для выбранной пропорции. Измени процент или проверь подключение.", "Not enough tracks for the selected mix. Change the percentage or check your connection."))
                } else {
                    val next = QueueSource(QueueKind.RADIO, title, seedId = nextSongs.first().id, originId = selected.origin)
                    c.recFeedback.served(nextSongs.map { it.id })
                    c.player.playQueue(nextSongs, 0, next)
                    c.settings.setStreamRussianPercent(selected.russianPercent)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (version == requestVersion && c.queue.source.value === previous) {
                    style = restoredStyle(previous.originId)
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
    val liked by c.library.likedIds.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    val hasNext by c.player.hasNext.collectAsState()
    val hasPrevious by c.player.hasPrevious.collectAsState()
    val current = song.takeIf { wave }
    val cover = current?.let { c.covers.art(SongItem(it)) }
    // The artwork mode is a real player backdrop, not an extracted palette.
    // The player's own white ink stays stable as covers change.
    val accent = if (settings.streamThemeColors) lerp(scheme.primary, Color.White, if (scheme.primary.luminance() < 0.28f) 0.48f else 0f) else Color.White
    val blurred = rememberFullArtworkBlur(cover.takeUnless { settings.streamThemeColors }, settings.player.blur)
    var lastBlur by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover, blurred, settings.streamThemeColors) {
        if (cover == null || settings.streamThemeColors) lastBlur = null
        else if (blurred != null) lastBlur = blurred
    }
    val context = LocalContext.current
    val artUrl = hiRes(cover, 1200)
    var artTry by remember(artUrl) { mutableIntStateOf(0) }
    var artFailed by remember(artUrl) { mutableStateOf(false) }
    val artRequest = remember(artUrl, artTry) { playerCoverRequest(context, artUrl, false) }
    var shownCover by remember { mutableStateOf<ImageRequest?>(null) }
    LaunchedEffect(artUrl, artFailed, artTry) {
        if (artUrl == null) shownCover = null
        else if (artFailed && artTry < com.prism.music.ui.player.COVER_RETRIES) {
            kotlinx.coroutines.delay(com.prism.music.ui.player.COVER_RETRY_MS)
            artFailed = false
            artTry++
        } else if (artFailed) shownCover = null
    }
    val fadeMs = if (settings.reduceMotion) 0 else 320
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
        !online && wave && playing -> l("Офлайн · скачанная музыка", "Offline · downloaded music")
        starting && wave -> l("Обновляем подбор", "Updating your mix")
        starting -> l("Подбираем музыку", "Finding your music")
        wave && buffering -> l("Загрузка", "Buffering")
        wave && playing -> l("Играет", "Playing")
        wave -> l("На паузе", "Paused")
        !online -> l("Офлайн", "Offline")
        else -> l("Персональный поток", "Personal stream")
    }
    @Composable fun Header() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = pageTitleStyle(), color = scheme.onBackground, maxLines = 1)
                Text(status, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            RoundAction(PrismIcons.Refresh, l("Встряхнуть", "Refresh Stream"),
                enabled = !starting, modifier = Modifier.testTag("home:my_wave:refresh")) {
                if (onRetry != null && !wave) onRetry() else start()
            }

        }
    }
    // One player card, not a decorative hero followed by a second mini player.
    @Composable fun Hero(modifier: Modifier) {
        // The surrounding route keeps Home's ScreenBackdrop. All media effects are clipped to this one panel.
        GlassSurface(modifier.testTag("home:my_wave"), shape = ui.shape(24.dp),
            onMedia = true, inPage = true) {
            if (settings.streamThemeColors) ThemeBackdrop()
            else Crossfade(targetState = lastBlur, animationSpec = tween(fadeMs), label = "streamBackdrop",
                modifier = Modifier.matchParentSize()) { image ->
                if (image == null) ThemeBackdrop()
                else FullArtworkBlurBackdrop(image, scrim = (1.3f - 0.95f * settings.player.backdropShow.coerceAtLeast(0f)).coerceAtLeast(0.8f))
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
            @Composable fun CoverArt(modifier: Modifier) {
                val scale by animateFloatAsState(
                    pausedCoverScale(playing, settings.artShrinkOnPause, settings.reduceMotion),
                    tween(fadeMs), label = "streamCoverScale")
                val shape = ui.shape(16.dp)
                Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                    .shadow(10.dp, shape).clip(shape).background(Color.Black.copy(alpha = 0.25f))
                    .clickable(enabled = current != null, onClick = onOpenPlayer), contentAlignment = Alignment.Center) {
                    if (shownCover == null) Icon(PrismIcons.Song, null, Modifier.fillMaxSize(0.32f), tint = Color.White.copy(alpha = 0.35f))
                    Crossfade(targetState = shownCover, animationSpec = tween(fadeMs), label = "streamArtwork",
                        modifier = Modifier.matchParentSize()) { image ->
                        if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize())
                    }
                    if (artUrl != null && shownCover != artRequest) AsyncImage(
                        artRequest, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0f },
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Success) shownCover = artRequest
                            else if (state is AsyncImagePainter.State.Error) artFailed = true
                        })
                }
            }
            @Composable fun Reaction(like: Boolean) {
                val item = current
                val on = if (like) item?.id in liked else item?.id in disliked
                val glyph = if (like) {
                    if (on) PrismIcons.HeartFilled else PrismIcons.Heart
                } else if (on) PrismIcons.ThumbDownFilled else PrismIcons.ThumbDown
                val label = if (like) l("Нравится", "Like") else l("Не моё", "Not for me")
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (item == null) Icon(glyph, label, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                    else CircleGlyph(glyph, label, active = on) {
                        if (like) c.library.toggleLike(item)
                        else {
                            c.library.setDisliked(item, !on)
                            if (!on && c.player.hasNext.value) c.player.next()
                        }
                    }
                }
            }
            @Composable fun Metadata(modifier: Modifier = Modifier, reactions: Boolean = false) {
                Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).then(if (current != null) Modifier.clickable(onClick = onOpenPlayer) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(current?.title ?: l("Музыка в твоём ритме", "Music in your rhythm"),
                            style = playerTitleStyle(MaterialTheme.typography, settings), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(current?.primaryArtist ?: l("Начни слушать", "Start listening"),
                            style = playerArtistStyle(MaterialTheme.typography, settings), color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (reactions) {
                        Spacer(Modifier.width(8.dp))
                        Reaction(true)
                        Spacer(Modifier.width(8.dp))
                        Reaction(false)
                    }
                }
            }
            @Composable fun Transport() {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
                TransportRow(isPlaying = wave && playing, isLoading = pending && !wave,
                    previousEnabled = wave && hasPrevious, nextEnabled = wave && hasNext,
                    compact = true, showModes = false, playAction = { if (wave) c.player.togglePlay() else if (!starting) start() },
                    playAccent = accent.takeIf { settings.streamThemeColors }, playTag = "home:my_wave:play")
                    }
                }
            }
            @Composable fun Progress() {
                val id = current?.id
                val timing by produceState(0L to 0L, id, playing) {
                    while (true) {
                        value = if (id != null && c.player.currentSong.value?.id == id)
                            c.player.position to c.player.duration.coerceAtLeast(0L) else 0L to 0L
                        kotlinx.coroutines.delay(if (playing) 250L else 500L)
                    }
                }
                // Reuse the actual player scrubber and its theme/shape/time settings.
                // Never bind an inactive Stream to playback from another queue.
                Box(Modifier.fillMaxWidth().testTag("stream:seek")) {
                    if (id != null && timing.second > 0L) key(id) {
                        PlayerScrubber(timing.first, timing.second)
                    } else Column(Modifier.fillMaxWidth()) {
                        com.prism.music.ui.player.ThinSlider(value = 0f, onValueChange = {})
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0:00", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f))
                            Text("–:–", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f))
                        }
                    }
                }
            }
            // Keep the three transport controls separate from the two song reactions.
            // A five-button transport row can exceed the available width on compact phones.
            val inset = if (maxWidth < 340.dp) 12.dp else 16.dp
            val landscape = maxWidth >= 520.dp && maxWidth > maxHeight * 1.3f
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(inset), horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        CoverArt(Modifier.size(minOf(maxWidth, maxHeight)))
                    }
                    Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metadata(reactions = true)
                        Progress()
                        Transport()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CoverArt(Modifier.size(minOf(maxWidth, maxHeight)))
                    }
                    Metadata(reactions = true)
                    Progress()
                    Transport()
                }
            }
        }
        }
    }

    @Composable fun Controls() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PrismScenarioDisc(l("Настройки", "Tune"), PrismIcons.Equalizer, selected = false,
                onClick = { settingsOpen = true }, modifier = Modifier.testTag("home:my_wave:settings"))
            LazyRow(Modifier.weight(1f).scenarioEdgeFade({ scenarioScroll.canScrollBackward }, { scenarioScroll.canScrollForward }),
                state = scenarioScroll,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(scenarioOrder, key = { it.id }) { scenario ->
                    PrismScenarioDisc(
                        label = if (ru) scenario.ru else scenario.en,
                        icon = PrismIcons.waveScenario(scenario.glyph),
                        selected = style.copy(russianPercent = -1) == scenario.style,
                        onClick = { start(scenario.style.copy(russianPercent = style.russianPercent)) },
                        modifier = Modifier.testTag("wave:scenario:${scenario.id}"))
                }
            }
        }
    }

    // The same bottom strip in portrait and landscape; only scenarios scroll.
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header()
            Hero(Modifier.weight(1f).fillMaxWidth())
            Controls()
        }
    }
    if (settingsOpen) {
        var diversity by remember(style.diversity) { mutableFloatStateOf(style.diversity.toFloat()) }
        var discovery by remember(style.discoveryPercent) { mutableFloatStateOf(style.discoveryPercent.toFloat()) }
        var russian by remember(style.russianPercent) { mutableFloatStateOf(if (style.russianPercent < 0) 50f else style.russianPercent.toFloat()) }
        ModalBottomSheet(onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = scheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(l("Настроить поток", "Tune your Stream"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (style != MyWave.Style()) start(MyWave.Style()) }) { Text(l("Сбросить", "Reset")) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(l("Оформление плеера", "Player card backdrop"), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrismChip(selected = !settings.streamThemeColors,
                            onClick = { c.settings.setStreamThemeColors(false) },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("stream:color:artwork"),
                            label = l("Обложка трека", "Track artwork"))
                        PrismChip(selected = settings.streamThemeColors,
                            onClick = { c.settings.setStreamThemeColors(true) },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("stream:color:theme"),
                            label = l("Тема Prism", "Prism theme"))
                    }
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
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(0 to l("Лайки", "Liked"), 50 to l("Баланс", "Balance"),
                            70 to l("Для меня", "For me"), 100 to l("Новое", "New")).forEach { (percent, label) ->
                            PrismChip(selected = discovery.toInt() == percent, onClick = { discovery = percent.toFloat(); selectPercent(percent) },
                                label = label, modifier = Modifier.height(44.dp))
                        }
                    }
                    Text(l("Открытия — похожие записи, которых нет в известной Prism истории. Лайки не заменяют их при нехватке.",
                        "Discoveries are related recordings not in Prism's known history. Likes do not replace missing discoveries."),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    HorizontalDivider(color = scheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(l("Язык музыки", "Music language"), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f))
                        PrismChip(selected = style.russianPercent < 0,
                            onClick = { if (style.russianPercent >= 0) start(style.copy(russianPercent = -1)) },
                            label = l("Авто", "Auto"), modifier = Modifier.height(44.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("${russian.toInt()}%", style = MaterialTheme.typography.headlineSmall,
                                color = scheme.primary)
                            Text(l("Русская", "Russian"), style = MaterialTheme.typography.labelMedium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${100 - russian.toInt()}%", style = MaterialTheme.typography.headlineSmall)
                            Text(l("Иностранная", "Foreign"), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Slider(value = russian, onValueChange = { russian = it },
                        onValueChangeFinished = {
                            val percent = (kotlin.math.round(russian / 5f).toInt() * 5).coerceIn(0, 100)
                            if (percent != style.russianPercent) start(style.copy(russianPercent = percent))
                        }, valueRange = 0f..100f, steps = 19, modifier = Modifier.testTag("wave:language-percent"))
                    Text(l("Приблизительное соотношение по языку названий. При нехватке подходящих записей баланс может отличаться.",
                        "Approximate title-language balance. The actual mix may vary when matching tracks are scarce."),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    HorizontalDivider(color = scheme.outlineVariant)
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        values.chunked(if (values.size > 3) 2 else 3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { value ->
                    PrismChip(selected = value == selected, onClick = { onSelect(value) },
                        label = name(value), modifier = Modifier.weight(1f).height(44.dp))
                }
            }
        }
    }
}

