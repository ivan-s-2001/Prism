package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.scenarioEdgeFade
import com.prism.music.ui.components.WavePresetNameDialog
import com.prism.music.ui.components.WaveTuningScale
import com.prism.music.ui.components.WaveScaleLabels
import com.prism.music.ui.components.PrismBalanceSlider
import com.prism.music.ui.components.PrismSlider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapPosition
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.CustomizeTarget
import com.prism.music.ui.components.LocalCustomize
import com.prism.music.ui.components.customizable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.prism.music.ui.components.Segmented
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.Crossfade
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.prism.music.data.model.hiRes
import com.prism.music.ui.player.playerCoverRequest
import androidx.compose.animation.core.tween
import com.prism.music.ui.player.PlayerScrubber
import com.prism.music.data.model.SongItem
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.data.ExplicitFilter
import com.prism.music.data.foryou.MyWave
import com.prism.music.data.foryou.WaveSettingsDraft
import com.prism.music.data.foryou.WaveScenarios
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
    Box(Modifier.fillMaxSize().padding(bottom = bottomPadding)) {
        MyWaveCard(recommendations, favorites, recent, online, loading = loading,
            onRetry = if (failed && recommendations.isEmpty() && !source.isMyWave && online) ({ retry++ }) else null,
            onOpenPlayer = onOpenPlayer)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MyWaveCard(recommendations: List<Song>, favorites: List<Song>, recent: List<Song>, online: Boolean,
    heroHeight: Dp = 420.dp, loading: Boolean = false, onRetry: (() -> Unit)? = null, onOpenPlayer: () -> Unit = {}) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val nav = com.prism.music.ui.LocalNavigator.current
    val source by c.queue.source.collectAsState()
    val song by c.player.currentSong.collectAsState()
    val playing by c.player.isPlaying.collectAsState()
    val buffering by c.player.buffering.collectAsState()
    val wave = source.isMyWave && song != null
    val title = tr(R.string.stream)

    var starting by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val settings = LocalAppSettings.current
    fun restoredStyle(origin: String?) =
        MyWave.restore(origin, settings.streamStyleOrigin, settings.streamRussianPercent)
    var style by remember(source.originId) { mutableStateOf(restoredStyle(source.originId)) }
    val automaticScenarioOrder = remember {
        val calendar = java.util.Calendar.getInstance()
        WaveScenarios.stripOrder(calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.DAY_OF_WEEK) in listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY))
    }
    val scenarioOrder = remember(automaticScenarioOrder, settings.streamScenarioOrder) { WaveScenarios.visibleOrder(automaticScenarioOrder, settings.streamScenarioOrder) }
    val scenarioScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val savedPresets = settings.streamPresets
    var namingPreset by rememberSaveable { mutableStateOf(false) }
    var chosenPresetId by rememberSaveable { mutableStateOf(settings.streamScenarioId) }
    var revealPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var baseline by remember { mutableStateOf<WaveSettingsDraft?>(null) }
    var editor by remember { mutableStateOf<WaveSettingsDraft?>(null) }
    fun openSettings() {
        val initial = WaveSettingsDraft(style, settings.streamThemeColors, savedPresets, chosenPresetId, settings.streamScenarioStyles)
        baseline = initial
        editor = initial
        namingPreset = false
        settingsOpen = true
    }
    fun closeSettings() {
        namingPreset = false
        settingsOpen = false
        editor = null
        baseline = null
    }
    val activeSaved = savedPresets.firstOrNull { it.id == chosenPresetId && it.origin == style.origin }
        ?: savedPresets.firstOrNull { chosenPresetId == null && it.origin == style.origin }
    val activeScenario = if (activeSaved == null) WaveScenarios.active(style, chosenPresetId, settings.streamScenarioStyles) else null
    LaunchedEffect(savedPresets, revealPresetId) {
        val index = savedPresets.indexOfFirst { it.id == revealPresetId }
        if (index >= 0) {
            scenarioScroll.scrollToItem(index + 1)
            revealPresetId = null
        }
    }
    var selectionJob by remember { mutableStateOf<Job?>(null) }
    var requestVersion by remember { mutableIntStateOf(0) }
    fun moodName(v: MyWave.Mood) = when (v) {
        MyWave.Mood.ANY -> tr(R.string.prism_stream_any)
        MyWave.Mood.CALM -> tr(R.string.prism_stream_calm)
        MyWave.Mood.ENERGETIC -> tr(R.string.prism_stream_energetic)
    }

    fun activityName(v: MyWave.Activity) = when (v) {
        MyWave.Activity.ANY -> tr(R.string.prism_stream_any)
        MyWave.Activity.WAKE -> tr(R.string.prism_stream_waking_up)
        MyWave.Activity.ROAD -> tr(R.string.prism_stream_on_the_road)
        MyWave.Activity.WORK -> tr(R.string.prism_stream_focus)
        MyWave.Activity.TRAIN -> tr(R.string.prism_stream_workout)
        MyWave.Activity.SLEEP -> tr(R.string.prism_stream_sleep)
    }

    fun start(selected: MyWave.Style = style, scenarioId: String? = chosenPresetId) {
        if (c.queue.followingHost) return
        selectionJob?.cancel()
        val token = c.player.queueStarting()
        val version = ++requestVersion
        starting = true
        val previous = c.queue.source.value
        val current = c.player.currentSong.value.takeIf { previous.isMyWave }
        selectionJob = scope.launch {
            try {
                val began = android.os.SystemClock.elapsedRealtime()
                val occupied = if (previous.isMyWave) c.player.waveRetuneOccupied() else emptyList()
                val songs = c.yourMusic.startWave(recommendations, selected, current, existing = occupied)
                android.util.Log.i("PrismWave", "first selection: ${android.os.SystemClock.elapsedRealtime() - began}ms; tracks=${songs.size}")
                // Latest selection wins; a slow response cannot restore an older set of filters.
                if (!isActive || version != requestVersion || !c.player.stillStarting(token) || c.queue.followingHost || c.queue.source.value !== previous) return@launch
                val now = c.player.currentSong.value.takeIf { previous.isMyWave }
                val nextSongs = MyWave.initialPicks(songs, now, offline = !c.network.isOnline())
                if (nextSongs.isEmpty()) {
                    style = restoredStyle(previous.originId)
                    c.player.reportError(if (!c.network.isOnline()) tr(R.string.prism_stream_no_matching_downloaded_tracks) else tr(R.string.prism_stream_not_enough_tracks_for_the_selected_mix_change_the_percentage_or_check))
                } else {
                    val next = QueueSource(QueueKind.RADIO, title, seedId = nextSongs.first().id, originId = selected.origin)
                    if (previous.isMyWave) {
                        if (!c.player.retuneWave(previous, next, nextSongs)) {
                            style = restoredStyle(previous.originId)
                            c.player.reportError(tr(R.string.prism_stream_could_not_update_stream))
                            return@launch
                        }
                    } else {
                        c.recFeedback.served(nextSongs.map { it.id })
                        c.player.playQueue(nextSongs, 0, next)
                    }
                    style = selected
                    chosenPresetId = scenarioId
                    c.settings.setStreamStyle(selected, scenarioId)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (version == requestVersion && c.queue.source.value === previous) {
                    style = restoredStyle(previous.originId)
                    c.player.reportError(tr(R.string.prism_stream_could_not_update_stream))
                }
            } finally {
                if (version == requestVersion) starting = false
            }
        }
    }

    // Feed refresh never blocks starting playback; only actual playback work does.
    val djOn by c.dj.on.collectAsState()
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val liked by c.library.likedIds.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    val hasNext by c.player.hasNext.collectAsState()
    val hasPrevious by c.player.hasPrevious.collectAsState()
    val current = song.takeIf { wave }
    val cover = current?.let { c.covers.art(SongItem(it)) }
    // An embedded card always follows Prism's active appearance; no independent Vibe palette.
    val context = LocalContext.current
    val artUrl = hiRes(cover, 360)
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
        MyWave.Depth.ANY -> tr(R.string.prism_stream_all)
        MyWave.Depth.HITS -> tr(R.string.prism_stream_hits)
        MyWave.Depth.DEEP -> tr(R.string.prism_stream_deep_cuts)
    }
    val status = when {
        !online && wave && playing -> tr(R.string.prism_stream_offline_downloaded_music)
        starting && wave -> tr(R.string.prism_stream_updating_your_mix)
        starting -> tr(R.string.prism_stream_finding_your_music)
        wave && buffering -> tr(R.string.prism_stream_buffering)
        wave && playing -> tr(R.string.prism_stream_playing)
        wave -> tr(R.string.prism_stream_paused)
        !online -> tr(R.string.prism_stream_offline)
        else -> tr(R.string.prism_stream_personal_stream)
    }
    val openCustomize = LocalCustomize.current
    @Composable fun Header() {
        com.prism.music.ui.components.ScreenHeader(title,
            subtitle = status.takeIf { settings.streamShowStatus }) {
            RoundAction(PrismIcons.Palette, tr(R.string.customize_prism), modifier = Modifier.testTag("wave:appearance")) {
                if (openCustomize != null) openCustomize(CustomizeTarget.VIBE, null)
                else nav.go(com.prism.music.ui.Routes.settings("home_library", tr(R.string.stream)))
            }
            if (wave) RoundAction(if (djOn) PrismIcons.TurntableFilled else PrismIcons.Turntable,
                tr(if (djOn) R.string.dj_mode_off else R.string.dj_mode),
                modifier = Modifier.testTag("wave:dj")) { c.player.setDjMode(!djOn) }
            if (wave || onRetry != null) RoundAction(PrismIcons.Shuffle, tr(R.string.prism_stream_refresh_stream),
                enabled = !starting, modifier = Modifier.testTag("home:my_wave:refresh")) {
                if (wave && djOn) c.player.toggleShuffle()
                else if (onRetry != null && !wave) onRetry() else start()
            }
        }
    }
    // A station first; the currently playing recording is a small, separately accessible attribution.
    @Composable fun Hero(modifier: Modifier) {
        @Composable fun TrackArtwork(modifier: Modifier) {
            val shape = com.prism.music.ui.theme.CoverShapes.of(settings.shapes.player, settings.player.artCorners.dp)
            Box(modifier.clip(shape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                if (shownCover == null) Icon(PrismIcons.Song, null, Modifier.fillMaxSize(0.45f), tint = scheme.onSurfaceVariant)
                Crossfade(targetState = shownCover, animationSpec = tween(fadeMs), label = "streamArtwork",
                    modifier = Modifier.matchParentSize()) { image ->
                    if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
            val item = current ?: return
            val on = if (like) item.id in liked else item.id in disliked
            val glyph = if (like) {
                if (on) PrismIcons.HeartFilled else PrismIcons.Heart
            } else if (on) PrismIcons.ThumbDownFilled else PrismIcons.ThumbDown
            VibeTrackAction(glyph, if (like) tr(R.string.prism_stream_like) else tr(R.string.prism_stream_not_for_me), selected = on, onClick = {
                if (like) c.library.toggleLike(item)
                else {
                    c.library.setDisliked(item, !on)
                    if (!on && c.player.hasNext.value) c.player.next()
                }
            })
        }
        VibeFlowField(modifier.testTag("home:my_wave").customizable(CustomizeTarget.VIBE), animated = wave && playing && !buffering,
            energy = style.diversity / 100f) {
            CompositionLocalProvider(com.prism.music.ui.player.LocalEmbeddedPlayerInk provides scheme.onSurface,
                LocalContentColor provides scheme.onSurface) {
                VibeStationOverlay(settings, (chosenPresetId?.let { id -> savedPresets.firstOrNull { it.id == id }?.name
                        ?: WaveScenarios.all.firstOrNull { it.id == id }?.label } ?: tr(R.string.prism_stream_station_default)),
                    hasTrack = current != null, modifier = Modifier.align(Alignment.Center),
                    play = {
                        VibeStationAction(playing = wave && playing, starting = starting && !wave, active = wave,
                            onPlay = { if (wave) c.player.togglePlay() else if (!starting) start() })
                    },
                    credits = {
                        current?.let { item -> VibeCredits(settings, item.title, item.primaryArtist,
                            artwork = { TrackArtwork(it) }, onOpen = onOpenPlayer) }
                    },
                    previous = {
                        VibeTrackAction(PrismIcons.Previous, tr(R.string.widget_previous), { c.player.previous() }, enabled = hasPrevious)
                    },
                    next = {
                        VibeTrackAction(PrismIcons.Next, tr(R.string.prism_stream_next), { c.player.next() }, enabled = hasNext)
                    },
                    dislike = { Reaction(false) },
                    like = { Reaction(true) },
                    progress = {
                        val item = current
                        if (item != null) {
                            val timing by produceState(0L to 0L, item.id, playing) {
                                while (true) {
                                    value = if (c.player.currentSong.value?.id == item.id)
                                        c.player.position to c.player.duration.coerceAtLeast(0L) else 0L to 0L
                                    kotlinx.coroutines.delay(if (playing) 250L else 500L)
                                }
                            }
                            if (timing.second > 0L) Box(Modifier.testTag("stream:seek")) { PlayerScrubber(timing.first, timing.second) }
                        }
                    })
            }
        }
    }

    @Composable fun ScenarioTile(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
        VibeScenarioTile(settings.streamCompactScenarios, label, icon, selected, onClick, modifier)
    }

    @Composable fun Controls() {
        LazyRow(Modifier.fillMaxWidth().scenarioEdgeFade({ scenarioScroll.canScrollBackward }, { scenarioScroll.canScrollForward }),
            state = scenarioScroll,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = scenarioScroll, snapPosition = SnapPosition.Start),
            horizontalArrangement = Arrangement.spacedBy(ui.gap(8.dp)),
            contentPadding = PaddingValues(horizontal = ui.gap(12.dp), vertical = ui.gap(2.dp))) {
            item(key = "wave:tune") {
                ScenarioTile(tr(R.string.prism_stream_tune), PrismIcons.Customize, selected = false,
                    onClick = { openSettings() }, modifier = Modifier.testTag("home:my_wave:settings"))
            }
                items(savedPresets, key = { it.id }) { preset ->
                    ScenarioTile(label = preset.name, icon = PrismIcons.Bookmark,
                        selected = activeSaved?.id == preset.id,
                        onClick = { start(MyWave.style(preset.origin), preset.id) },
                        modifier = Modifier.testTag("wave:custom:${preset.id}"))
                }
                items(scenarioOrder, key = { it.id }) { scenario ->
                    ScenarioTile(
                        label = scenario.label,
                        icon = PrismIcons.waveScenario(scenario.glyph),
                        selected = activeScenario?.id == scenario.id,
                        onClick = { start(WaveScenarios.resolve(scenario, settings.streamScenarioStyles, style.russianPercent), scenario.id) },
                        modifier = Modifier.testTag("wave:scenario:${scenario.id}"))
                }
        }
    }

    VibePageLayout(settings, header = { Header() }, player = { Hero(it) }, scenarios = { Controls() })
    val editing = editor
    if (settingsOpen && editing != null) {
        val draftStyle = editing.style
        fun change(transform: (WaveSettingsDraft) -> WaveSettingsDraft) { editor = editor?.let(transform) }
        fun applySettings(initial: WaveSettingsDraft, final: WaveSettingsDraft) {
            c.settings.applyStreamTuning(null, initial.presets, final.presets,
                initial.scenarioStyles, final.scenarioStyles)
            if (final.presetId != null && initial.presets.none { it.id == final.presetId } && final.presets.any { it.id == final.presetId })
                revealPresetId = final.presetId
            closeSettings()
            if (final.style != style && c.queue.source.value.isMyWave) start(final.style, final.presetId)
            else {
                style = final.style
                chosenPresetId = final.presetId
                c.settings.setStreamStyle(final.style, final.presetId)
            }
        }
        com.prism.music.ui.components.PrismSheet(onDismiss = { closeSettings() }) { close ->
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = ui.gap(20.dp)).navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(bottom = ui.gap(18.dp)), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ui.gap(12.dp))) {
                    Text(tr(R.string.prism_stream_tune_your_stream), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    RoundAction(PrismIcons.Refresh, tr(R.string.prism_stream_reset)) { change { it.reset() } }
                }
                var advanced by rememberSaveable { mutableStateOf(draftStyle.depth != MyWave.Depth.ANY) }
                var showPresets by rememberSaveable { mutableStateOf(false) }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = ui.gap(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(ui.gap(16.dp))) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ui.gap(6.dp))) {
                        WaveScale(WaveTuningScale.DISCOVERY, draftStyle, "wave:discovery-percent") { value ->
                            change { WaveTuningScale.DISCOVERY.change(it, value) }
                        }
                        WaveScale(WaveTuningScale.LANGUAGE, draftStyle, "wave:language-percent") { value ->
                            change { WaveTuningScale.LANGUAGE.change(it, value) }
                        }
                        WaveScale(WaveTuningScale.DIVERSITY, draftStyle, "wave:diversity-percent") { value ->
                            change { WaveTuningScale.DIVERSITY.change(it, value) }
                        }
                    }
                    WaveChoices(tr(R.string.prism_stream_mood), MyWave.Mood.entries, draftStyle.mood, ::moodName) { value -> change { it.tune(it.style.copy(mood = value)) } }
                    WaveChoices(tr(R.string.prism_stream_activity), MyWave.Activity.entries, draftStyle.activity, ::activityName) { value -> change { it.tune(it.style.copy(activity = value)) } }

                    Row(Modifier.fillMaxWidth().clip(com.prism.music.ui.theme.LocalUi.current.shape(12.dp))
                        .clickable { advanced = !advanced }.padding(vertical = ui.gap(10.dp)),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(10.dp))) {
                        Text(tr(R.string.prism_stream_advanced), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Icon(if (advanced) PrismIcons.ChevronUp else PrismIcons.ChevronDown, null, Modifier.size(20.dp))
                    }
                    if (advanced) {
                    WaveChoices(tr(R.string.prism_stream_popularity), MyWave.Depth.entries, draftStyle.depth, ::depthName) { value -> change { it.tune(it.style.copy(depth = value)) } }
                    }

                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.65f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
                        if (editing.presets.isNotEmpty()) {
                            Row(Modifier.weight(1f).clip(ui.shape(12.dp)).clickable { showPresets = !showPresets }
                                .heightIn(min = 48.dp).padding(vertical = ui.gap(8.dp)), verticalAlignment = Alignment.CenterVertically) {
                                Text(tr(R.string.prism_stream_my_presets) + " · " + editing.presets.size,
                                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Icon(if (showPresets) PrismIcons.ChevronUp else PrismIcons.ChevronDown, null, Modifier.size(20.dp))
                            }
                        }
                        PrismChip(fitLabel = true, selected = false, onClick = { showPresets = true; namingPreset = true }, icon = PrismIcons.Add,
                            label = tr(R.string.prism_stream_save_scenario),
                            modifier = (if (editing.presets.isEmpty()) Modifier.fillMaxWidth() else Modifier)
                                .heightIn(min = 48.dp).testTag("wave:preset:save"))
                    }
                    if (showPresets) editing.presets.forEach { preset ->
                        key(preset.id) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(12.dp))) {
                                PrismChip(fitLabel = true, selected = editing.presetId == preset.id && preset.origin == draftStyle.origin, label = preset.name, icon = PrismIcons.Bookmark,
                                    modifier = Modifier.weight(1f).heightIn(min = ui.gap(44.dp).coerceAtLeast(36.dp)),
                                    onClick = { change { it.choose(preset) } })
                                RoundAction(PrismIcons.Delete, tr(R.string.prism_stream_delete, preset.name), glass = true) { change { it.delete(preset.id) } }
                            }
                        }
                    }

                }
                Row(Modifier.fillMaxWidth().padding(top = ui.gap(12.dp), bottom = ui.gap(8.dp)),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(12.dp))) {
                    com.prism.music.ui.components.PrismTextButton(onClick = { close {} }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(tr(R.string.prism_stream_cancel))
                    }
                    com.prism.music.ui.components.PrismButton(onClick = {
                        val initial = baseline
                        val final = editor
                        if (initial != null && final != null) close { applySettings(initial, final) }
                    },
                        modifier = Modifier.weight(1.6f).heightIn(min = 48.dp).testTag("wave:settings:done")) {
                        Icon(PrismIcons.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr(R.string.prism_stream_done))
                    }
                }
            }
        }
        if (namingPreset) WavePresetNameDialog(editing.presets.map { it.name }, { namingPreset = false }) { name ->
            change { it.save(name) }
            namingPreset = false
        }
    }
}


/** Shared layout for all numeric Stream controls. Ratio summaries follow the
 * same left-to-right order as the slider; variety is a level, not a track quota. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaveScale(scale: WaveTuningScale, style: MyWave.Style, tag: String, change: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val value = scale.value(style)
    val automatic = scale.automatic && value < 0
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        WaveScaleLabels(scale, value, scheme.primary, scheme.onSurfaceVariant,
            MaterialTheme.typography.labelMedium, MaterialTheme.typography.titleMedium,
            center = if (scale.automatic) {
                { PrismChip(fitLabel = true, selected = automatic, label = tr(R.string.prism_stream_auto),
                    onClick = { change(if (automatic) 50 else -1) }) }
            } else null)
        val position = scale.position(value).toFloat()
        val update: (Float) -> Unit = { change(scale.fromPosition(it)) }
        val modifier = Modifier.fillMaxWidth().testTag(tag)
        val steps = 100 / scale.step - 1
        if (scale.ratio) PrismBalanceSlider(position, update, modifier, steps, automatic)
        else PrismSlider(position, update, modifier, valueRange = 0f..100f, steps = steps)

    }
}

/** Short mutually exclusive choices use Prism's own segmented control; longer lists wrap at their natural width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> WaveChoices(label: String, values: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    val ui = LocalUi.current
    Column(verticalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (values.size in 2..3 && !ui.classicControls) {
            Segmented(values, selected, name, onSelect = onSelect)
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ui.gap(8.dp)),
                verticalArrangement = Arrangement.spacedBy(ui.gap(6.dp))) {
                values.forEach { value ->
                    PrismChip(fitLabel = true, selected = value == selected, onClick = { onSelect(value) },
                        label = name(value), modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}
