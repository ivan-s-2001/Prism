package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.PrismScenarioDisc
import com.prism.music.ui.components.scenarioEdgeFade
import com.prism.music.ui.components.WavePresetNameDialog
import com.prism.music.ui.components.WaveTuningScale
import com.prism.music.ui.components.WaveScaleLabels
import com.prism.music.ui.components.WaveScaleRail
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapPosition
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.pageTitleStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomPadding)) {
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
    val scenarioOrder = remember {
        val calendar = java.util.Calendar.getInstance()
        WaveScenarios.stripOrder(calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.DAY_OF_WEEK) in listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY))
    }
    val scenarioScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val savedPresets = settings.streamPresets
    var namingPreset by rememberSaveable { mutableStateOf(false) }
    var chosenPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var revealPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var baseline by remember { mutableStateOf<WaveSettingsDraft?>(null) }
    var editor by remember { mutableStateOf<WaveSettingsDraft?>(null) }
    fun openSettings() {
        val initial = WaveSettingsDraft(style, settings.streamThemeColors, savedPresets, chosenPresetId)
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
    LaunchedEffect(savedPresets, revealPresetId) {
        val index = savedPresets.indexOfFirst { it.id == revealPresetId }
        if (index >= 0) {
            scenarioScroll.scrollToItem(index)
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

    fun start(selected: MyWave.Style = style) {
        selectionJob?.cancel()
        val version = ++requestVersion
        style = selected
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
                if (!isActive || version != requestVersion || c.queue.source.value !== previous) return@launch
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
                    c.settings.setStreamStyle(selected)
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
    val pending = starting || (wave && buffering)
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
    @Composable fun Header() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = pageTitleStyle(), color = scheme.onBackground, maxLines = 1)
                Text(status, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RoundAction(PrismIcons.Shuffle, tr(R.string.prism_stream_refresh_stream),
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
                    if (shownCover == null) Icon(if (current == null) PrismIcons.Stream else PrismIcons.Song, null, Modifier.fillMaxSize(0.32f), tint = Color.White.copy(alpha = 0.35f))
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
                val label = if (like) tr(R.string.prism_stream_like) else tr(R.string.prism_stream_not_for_me)
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
                        Text(current?.title ?: tr(R.string.prism_stream_music_in_your_rhythm),
                            style = playerTitleStyle(MaterialTheme.typography, settings), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(current?.primaryArtist ?: tr(R.string.prism_stream_start_listening),
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
            PrismScenarioDisc(tr(R.string.prism_stream_tune), PrismIcons.Customize, selected = false,
                onClick = { openSettings() }, modifier = Modifier.testTag("home:my_wave:settings"))
            LazyRow(Modifier.weight(1f).scenarioEdgeFade({ scenarioScroll.canScrollBackward }, { scenarioScroll.canScrollForward }),
                state = scenarioScroll,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = scenarioScroll, snapPosition = SnapPosition.Start),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(savedPresets, key = { it.id }) { preset ->
                    PrismScenarioDisc(label = preset.name, icon = PrismIcons.Bookmark,
                        selected = activeSaved?.id == preset.id,
                        onClick = { chosenPresetId = preset.id; start(MyWave.style(preset.origin)) },
                        modifier = Modifier.testTag("wave:custom:${preset.id}"))
                }
                items(scenarioOrder, key = { it.id }) { scenario ->
                    PrismScenarioDisc(
                        label = scenario.label,
                        icon = PrismIcons.waveScenario(scenario.glyph),
                        selected = activeSaved == null && style.copy(russianPercent = -1) == scenario.style,
                        onClick = { chosenPresetId = scenario.id; start(scenario.style.copy(russianPercent = style.russianPercent)) },
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
    val editing = editor
    if (settingsOpen && editing != null) {
        val draftStyle = editing.style
        fun change(transform: (WaveSettingsDraft) -> WaveSettingsDraft) { editor = editor?.let(transform) }
        fun applySettings() {
            val initial = baseline ?: return
            val final = editor ?: return
            c.settings.applyStreamTuning(final.themeColors.takeIf { it != initial.themeColors }, initial.presets, final.presets)
            chosenPresetId = final.presetId
            if (final.presetId != null && initial.presets.none { it.id == final.presetId } && final.presets.any { it.id == final.presetId })
                revealPresetId = final.presetId
            closeSettings()
            if (final.style != style) {
                if (c.queue.source.value.isMyWave) start(final.style)
                else { style = final.style; c.settings.setStreamStyle(final.style) }
            }
        }
        ModalBottomSheet(onDismissRequest = { closeSettings() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = scheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(R.string.prism_stream_tune_your_stream), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    RoundAction(PrismIcons.Refresh, tr(R.string.prism_stream_reset)) { change { it.reset() } }
                }
                var advanced by rememberSaveable { mutableStateOf(false) }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.65f))
                    WaveChoices(tr(R.string.prism_stream_mood), MyWave.Mood.entries, draftStyle.mood, ::moodName) { value -> change { it.tune(it.style.copy(mood = value)) } }
                    WaveChoices(tr(R.string.prism_stream_activity), MyWave.Activity.entries, draftStyle.activity, ::activityName) { value -> change { it.tune(it.style.copy(activity = value)) } }

                    Row(Modifier.fillMaxWidth().clip(com.prism.music.ui.theme.LocalUi.current.shape(12.dp))
                        .clickable { advanced = !advanced }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr(R.string.prism_stream_advanced), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Icon(if (advanced) PrismIcons.ChevronUp else PrismIcons.ChevronDown, null, Modifier.size(20.dp))
                    }
                    if (advanced) {
                    WaveChoices(tr(R.string.prism_stream_popularity), MyWave.Depth.entries, draftStyle.depth, ::depthName) { value -> change { it.tune(it.style.copy(depth = value)) } }
                    Text(tr(R.string.prism_stream_player_card_backdrop), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrismChip(selected = !editing.themeColors,
                            onClick = { change { it.copy(themeColors = false) } },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("stream:color:artwork"),
                            label = tr(R.string.prism_stream_track_artwork))
                        PrismChip(selected = editing.themeColors,
                            onClick = { change { it.copy(themeColors = true) } },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("stream:color:theme"),
                            label = tr(R.string.prism_stream_prism_theme))
                    }

                    }
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.65f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (editing.presets.isNotEmpty())
                            Text(tr(R.string.prism_stream_my_presets), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        PrismChip(selected = false, onClick = { namingPreset = true }, icon = PrismIcons.Add,
                            label = tr(R.string.prism_stream_save_scenario),
                            modifier = (if (editing.presets.isEmpty()) Modifier.fillMaxWidth() else Modifier)
                                .height(44.dp).testTag("wave:preset:save"))
                    }
                    editing.presets.forEach { preset ->
                        key(preset.id) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PrismChip(selected = editing.presetId == preset.id && preset.origin == draftStyle.origin, label = preset.name, icon = PrismIcons.Bookmark,
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    onClick = { change { it.choose(preset) } })
                                RoundAction(PrismIcons.Delete, tr(R.string.prism_stream_delete, preset.name), glass = true) { change { it.delete(preset.id) } }
                            }
                        }
                    }

                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { closeSettings() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(tr(R.string.prism_stream_cancel))
                    }
                    Button(onClick = { applySettings() }, shape = LocalUi.current.shape(24.dp),
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
                { PrismChip(selected = automatic, label = tr(R.string.prism_stream_auto),
                    onClick = { change(if (automatic) 50 else -1) }) }
            } else null)
        Slider(
            value = scale.position(value).toFloat(),
            onValueChange = { change(scale.fromPosition(it)) },
            valueRange = 0f..100f, steps = 100 / scale.step - 1,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            thumb = {
                Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(scheme.surface).padding(3.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (automatic) scheme.onSurfaceVariant else scheme.primary))
            },
            track = {
                WaveScaleRail(scale, value, scheme.primary, scheme.onSurfaceVariant, scheme.surface)
            },
        )

    }
}

@Composable
private fun <T> WaveChoices(label: String, values: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        values.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { value ->
                    PrismChip(selected = value == selected, onClick = { onSelect(value) },
                        label = name(value), modifier = Modifier.weight(1f).height(48.dp))
                }
            }
        }
    }
}

