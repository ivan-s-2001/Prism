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
import com.prism.music.ui.theme.VibePresetIcons
import androidx.compose.ui.platform.LocalContext
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
        if (!source.continuousRecommendations) try { c.yourMusic.warmWave() }
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
            if (source.continuousRecommendations && retry == 0) return@produceState
            val fresh = kotlinx.coroutines.withTimeoutOrNull(7000L) {
                val shelves = c.ytm.fullHome(pages = 1, maxPages = 2,
                    satisfied = { earlyQuickPicks(it, settings.accountName) != null })
                val own = earlyQuickPicks(shelves, settings.accountName)?.items.orEmpty()
                    .filterIsInstance<SongItem>().map { it.song }
                if (own.isNotEmpty()) own else shelves.flatMap { it.items }.filterIsInstance<SongItem>().map { it.song }.distinctBy { it.id }.take(60)
            }.orEmpty()
            value = fresh
            // Keep first sound fast: a separate recommendation pass is bounded, never required for starting.
            if (!c.castingNow && !c.queue.source.value.continuousRecommendations) {
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
    Box(Modifier.fillMaxSize()) {
        MyWaveCard(recommendations, favorites, recent, online, loading = loading,
            onRetry = if (failed && recommendations.isEmpty() && !source.isMyWave && online) ({ retry++ }) else null,
            onOpenPlayer = onOpenPlayer, bottomPadding = bottomPadding)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MyWaveCard(recommendations: List<Song>, favorites: List<Song>, recent: List<Song>, online: Boolean,
    heroHeight: Dp = 420.dp, loading: Boolean = false, onRetry: (() -> Unit)? = null, onOpenPlayer: () -> Unit = {}, bottomPadding: Dp = 0.dp) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val nav = com.prism.music.ui.LocalNavigator.current
    val source by c.queue.source.collectAsState()
    val song by c.player.currentSong.collectAsState()
    val playing by c.player.isPlaying.collectAsState()
    val buffering by c.player.buffering.collectAsState()
    val wave = source.continuousRecommendations && song != null
    val contextual = wave && source.isSongRadio
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
    val savedPresets = settings.streamPresets
    val iconContext = LocalContext.current
    var namingPreset by rememberSaveable { mutableStateOf(false) }
    var editingPresetId by rememberSaveable { mutableStateOf<String?>(null) }
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
    val shortcuts = remember(style, savedPresets, scenarioOrder, settings.streamScenarioStyles, activeSaved?.id, activeScenario?.id) {
        vibeShortcuts(style, savedPresets, scenarioOrder, settings.streamScenarioStyles, activeSaved?.id, activeScenario?.id) { VibePresetIcons.vector(iconContext, it.iconId) }
    }
    val alternatives = vibeAlternatives(shortcuts, contextual)
    val orbitKeys = alternatives.map { it.key }
    val scenarioScroll = androidx.compose.foundation.lazy.rememberLazyListState(vibeOrbitStart(alternatives.size, 0))
    var previousOrbitKeys by remember { mutableStateOf(orbitKeys) }
    LaunchedEffect(orbitKeys) {
        if (previousOrbitKeys != orbitKeys && orbitKeys.isNotEmpty()) {
            val old = previousOrbitKeys
            val index = scenarioScroll.firstVisibleItemIndex
            val anchor = old.getOrNull(vibeOrbitIndex(index, old.size))
            val next = old.getOrNull(vibeOrbitIndex(index + 1, old.size))
            val target = orbitKeys.indexOf(anchor).takeIf { it >= 0 } ?: orbitKeys.indexOf(next).coerceAtLeast(0)
            scenarioScroll.scrollToItem(vibeOrbitNearest(index, orbitKeys.size, target))
        }
        previousOrbitKeys = orbitKeys
    }
    LaunchedEffect(orbitKeys, revealPresetId) {
        val id = revealPresetId
        val index = if (id == null) -1 else alternatives.indexOfFirst { it.key == "custom:$id" }
        if (index >= 0) scenarioScroll.scrollToItem(vibeOrbitNearest(scenarioScroll.firstVisibleItemIndex, alternatives.size, index))
        if (id != null && shortcuts.any { it.key == "custom:$id" }) revealPresetId = null
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
            if ((wave && !contextual) || onRetry != null && !wave) RoundAction(PrismIcons.Shuffle, tr(R.string.prism_stream_refresh_stream),
                enabled = !starting, modifier = Modifier.testTag("home:my_wave:refresh")) {
                if (wave && djOn) c.player.toggleShuffle()
                else if (onRetry != null && !wave) onRetry() else start()
            }
        }
    }
    // The global mini player owns track metadata and secondary transport on mobile.
    @Composable fun Hero(modifier: Modifier) {
        val current = song.takeIf { wave }
        VibeStationOverlay(settings, activeVibeTitle(source, activeSaved, activeScenario),
            hasTrack = current != null,
            showTrackDetails = false,
            origin = activeVibeOrigin(source, current),
            modifier = modifier.testTag("home:my_wave").customizable(CustomizeTarget.VIBE),
            play = {
                VibeStationAction(wave && playing, starting && !wave, wave,
                    onPlay = { if (wave) c.player.togglePlay() else if (!starting) start() })
            },
            tune = { if (!contextual) VibeTunePill({ openSettings() }) })
    }

    @Composable fun ScenarioTile(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
        VibeScenarioTile(settings.streamCompactScenarios, label, icon, selected, onClick, modifier)
    }

    @Composable fun Controls() {
        // A pair of equally weighted tiles frames the center; no position is a selection.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = settings.streamCompactScenarios
            val slotWidth = if (compact) 144.dp else 104.dp
            val spacing = ui.gap(10.dp)
            val sidePadding = vibeOrbitPairPadding(maxWidth, slotWidth, spacing, alternatives.size)
            LazyRow(Modifier.fillMaxWidth().scenarioEdgeFade({ scenarioScroll.canScrollBackward }, { scenarioScroll.canScrollForward }),
                state = scenarioScroll,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = scenarioScroll,
                    snapPosition = SnapPosition.Start),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                contentPadding = PaddingValues(horizontal = sidePadding, vertical = if (compact) 2.dp else 18.dp)) {
                items(vibeOrbitCount(alternatives.size), key = { index -> "orbit:$index:${alternatives[vibeOrbitIndex(index, alternatives.size)].key}" }) { index ->
                    val shortcut = alternatives[vibeOrbitIndex(index, alternatives.size)]
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val stride = with(density) { (slotWidth + spacing).toPx() }
                    val orbit = if (compact) Modifier else Modifier.vibeOrbit {
                        val info = scenarioScroll.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                        item?.let { (it.offset + it.size / 2f - center) / stride.coerceAtLeast(1f) } ?: 0f
                    }
                    Box(Modifier.width(slotWidth).then(orbit), contentAlignment = Alignment.Center) {
                        ScenarioTile(shortcut.label, shortcut.icon, shortcut.selected && !contextual, onClick = {
                            val target = shortcut.style
                            if (target != null && (!wave || contextual || !shortcut.selected)) start(target, shortcut.scenarioId)
                        }, modifier = Modifier.testTag("wave:${shortcut.key}"))
                    }
                }
            }
        }
    }

    VibePageLayout(settings, header = { Header() }, player = { Hero(it) }, scenarios = { Controls() },
        bottomPadding = bottomPadding, backdrop = { VibeSphere(it, wave && playing && !buffering, style.diversity / 100f) })
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
                        PrismChip(fitLabel = true, selected = false, onClick = { showPresets = true; editingPresetId = null; namingPreset = true }, icon = PrismIcons.Add,
                            label = tr(R.string.prism_stream_save_scenario),
                            modifier = (if (editing.presets.isEmpty()) Modifier.fillMaxWidth() else Modifier)
                                .heightIn(min = 48.dp).testTag("wave:preset:save"))
                    }
                    if (showPresets) editing.presets.forEach { preset ->
                        key(preset.id) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(12.dp))) {
                                PrismChip(fitLabel = true, selected = editing.presetId == preset.id && preset.origin == draftStyle.origin, label = preset.name, icon = VibePresetIcons.vector(iconContext, preset.iconId),
                                    modifier = Modifier.weight(1f).heightIn(min = ui.gap(44.dp).coerceAtLeast(36.dp)),
                                    onClick = { change { it.choose(preset) } })
                                RoundAction(PrismIcons.Edit, tr(R.string.prism_vibe_edit_preset), glass = true) { editingPresetId = preset.id; namingPreset = true }
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
        if (namingPreset) {
            val preset = editing.presets.firstOrNull { it.id == editingPresetId }
            WavePresetNameDialog(editing.presets.filter { it.id != preset?.id }.map { it.name }, { namingPreset = false },
                initialName = preset?.name.orEmpty(), initialIcon = preset?.iconId ?: "bookmark", editing = preset != null) { name, icon ->
                change { if (preset == null) it.save(name, iconId = icon) else it.editPreset(preset.id, name, icon) }
                namingPreset = false
                editingPresetId = null
            }
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
