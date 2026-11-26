package com.prism.music.ui.tv

import com.prism.music.R
import com.prism.music.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.data.foryou.MyWave
import com.prism.music.data.prefs.StreamLayout
import com.prism.music.data.prefs.StreamVisualStyle
import com.prism.music.data.prefs.StreamCoverSize
import com.prism.music.data.foryou.WaveSettingsDraft
import androidx.compose.foundation.verticalScroll
import com.prism.music.data.foryou.WaveScenarios
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.screens.vibeShortcuts
import com.prism.music.ui.screens.vibeStationTitle
import com.prism.music.ui.screens.vibeOrbit
import com.prism.music.ui.screens.vibeOrbitIndex
import com.prism.music.ui.screens.vibeOrbitCount
import com.prism.music.ui.screens.vibeOrbitStart
import com.prism.music.ui.screens.vibeOrbitNearest
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import com.prism.music.ui.screens.vibeActionDiameter
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.ui.components.scenarioEdgeFade
import com.prism.music.ui.components.WavePresetNameDialog
import com.prism.music.ui.theme.VibePresetIcons
import androidx.compose.ui.platform.LocalContext
import com.prism.music.ui.components.WaveTuningScale
import com.prism.music.ui.components.WaveScaleLabels
import com.prism.music.ui.components.WaveScaleRail
import androidx.compose.runtime.saveable.rememberSaveable
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A TV place, not the phone UI stretched to a television. Uses the same player, queue
 * source, recommendation proportions, and presets as the phone. */
@Composable
internal fun TvWavePlace(nav: TvNav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val source by c.queue.source.collectAsState()
    val appSettings by c.settings.flow.collectAsState()
    val savedPresets = appSettings.streamPresets
    val iconContext = LocalContext.current
    var namingPreset by rememberSaveable { mutableStateOf(false) }
    var editingPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var chosenPresetId by rememberSaveable { mutableStateOf(appSettings.streamScenarioId) }
    val automaticScenarioOrder = remember {
        val clock = java.util.Calendar.getInstance()
        WaveScenarios.stripOrder(clock.get(java.util.Calendar.HOUR_OF_DAY),
            clock.get(java.util.Calendar.DAY_OF_WEEK) in listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY))
    }
    val scenarioOrder = remember(automaticScenarioOrder, appSettings.streamScenarioOrder) { WaveScenarios.visibleOrder(automaticScenarioOrder, appSettings.streamScenarioOrder) }
    val song by c.player.currentSong.collectAsState()
    val now = rememberTvNow(c).takeIf { source.continuousRecommendations }
    val current = song.takeIf { source.continuousRecommendations }
    val contextual = current != null && source.isContextVibe

    val title = tr(R.string.stream)
    fun restored() = MyWave.restore(source.originId, appSettings.streamStyleOrigin, appSettings.streamRussianPercent)
    var style by remember(source.originId) { mutableStateOf(restored()) }
    var editor by remember { mutableStateOf(WaveSettingsDraft(style, appSettings.streamThemeColors, savedPresets, chosenPresetId, appSettings.streamScenarioStyles)) }
    var baseline by remember { mutableStateOf(editor) }
    val draft = editor.style
    val activeSaved = savedPresets.firstOrNull { it.id == chosenPresetId && it.origin == style.origin }
        ?: savedPresets.firstOrNull { chosenPresetId == null && it.origin == style.origin }
    val activeScenario = if (activeSaved == null) WaveScenarios.active(style, chosenPresetId, appSettings.streamScenarioStyles) else null
    var tuning by remember { mutableStateOf(false) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var base by remember { mutableStateOf(emptyList<Song>()) }
    val screen = LocalTvScreen.current
    val memory = LocalTvFocusMemory.current
    fun toggleTuning(open: Boolean) {
        if (open) {
            baseline = WaveSettingsDraft(style, appSettings.streamThemeColors, savedPresets, chosenPresetId, appSettings.streamScenarioStyles)
            editor = baseline
        }
        namingPreset = false
        memory.opening(if (open) "$screen/tune" else screen)
        tuning = open
    }
    BackHandler(tuning) { toggleTuning(false) }
    fun toggleAppearance(open: Boolean) {
        memory.opening(if (open) "$screen/appearance" else screen)
        appearance = open
    }
    BackHandler(appearance) { toggleAppearance(false) }
    suspend fun loadBase(): List<Song> {
        val settings = c.settings.current
        val account = "${settings.authUser}|${settings.dataSyncId}|${settings.accountName}"
        val cached = c.homeCache.readPicks(java.time.LocalDate.now().toString(), account).orEmpty()
        if (cached.isNotEmpty()) return cached
        if (!c.network.isOnline()) return emptyList()
        return c.ytm.fullHome(pages = 1, maxPages = 2).flatMap { it.items }
            .filterIsInstance<SongItem>().map { it.song }.distinctBy { it.id }.take(60)
    }
    LaunchedEffect(Unit) {
        try { c.yourMusic.warmWave(); base = loadBase() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    fun start(selected: MyWave.Style = style, scenarioId: String? = chosenPresetId) {
        if (c.queue.followingHost) return
        job?.cancel()
        val token = c.player.queueStarting()
        val version = ++request
        val old = c.queue.source.value
        val previous = c.player.currentSong.value.takeIf { old.isMyWave }
        starting = true
        job = scope.launch {
            try {
                val occupied = if (old.isMyWave) c.player.waveRetuneOccupied() else emptyList()
                val candidates = c.yourMusic.startWave(base, selected, previous, existing = occupied) {
                    if (base.isEmpty()) base = loadBase()
                    base
                }
                if (version != request || !c.player.stillStarting(token) || c.queue.followingHost || c.queue.source.value !== old) return@launch
                val current = c.player.currentSong.value.takeIf { old.isMyWave }
                val picks = MyWave.initialPicks(candidates, current, offline = !c.network.isOnline())
                if (picks.isEmpty()) c.player.reportError(if (!c.network.isOnline()) tr(R.string.prism_stream_no_matching_downloaded_tracks) else tr(R.string.prism_stream_not_enough_matching_tracks))
                else {
                    val next = QueueSource(QueueKind.VIBE, title, seedId = picks.first().id, originId = selected.origin)
                    if (old.isMyWave) {
                        if (!c.player.retuneWave(old, next, picks)) {
                            c.player.reportError(tr(R.string.prism_stream_could_not_update_stream))
                            return@launch
                        }
                    } else {
                        c.recFeedback.served(picks.map { it.id })
                        c.player.playQueue(picks, 0, next)
                    }
                    style = selected
                    chosenPresetId = scenarioId
                    c.settings.setStreamStyle(selected, scenarioId)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (version == request && c.queue.source.value === old)
                    c.player.reportError(tr(R.string.prism_stream_could_not_update_stream))
            }
            finally { if (version == request) starting = false }
        }
    }

    val ui = com.prism.music.ui.theme.LocalUi.current
    @Composable fun ScenarioTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
        if (appSettings.streamCompactScenarios) WaveTvButton(label, onClick, modifier, icon, selected)
        else TvScenarioDisc(label, icon, selected, onClick, modifier)
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(TvSafe)) {
        val narrow = maxWidth < 760.dp
        val scheme = MaterialTheme.colorScheme
        if (appearance) {
            CompositionLocalProvider(LocalTvScreen provides "$screen/appearance") {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ui.gap(12.dp))) {
                    Text(tr(R.string.customize_prism), style = MaterialTheme.typography.headlineMedium)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(ui.gap(24.dp))) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ui.gap(12.dp))) {
                            TvWaveChoices(tr(R.string.visualizer), StreamVisualStyle.entries, appSettings.streamVisualStyle, "visual", { it.label }) { c.settings.setStreamVisualStyle(it) }
                            TvWaveChoices(tr(R.string.layout), StreamLayout.entries, appSettings.streamLayout, "layout", { it.label }) { c.settings.setStreamLayout(it) }
                            TvWaveChoices(tr(R.string.play_button) + " · " + tr(R.string.style), TransportStyle.entries, appSettings.streamActionStyle, "play_style", { it.label }) { c.settings.setStreamActionStyle(it) }
                            TvWaveChoices(tr(R.string.play_button) + " · " + tr(R.string.size), StreamCoverSize.entries, appSettings.streamActionSize, "play_size", { it.label }) { c.settings.setStreamActionSize(it) }
                            if (appSettings.streamVisualStyle != StreamVisualStyle.OFF) {
                            TvWaveChoices(tr(R.string.size), StreamCoverSize.entries, appSettings.streamVisualSize, "size", { it.label }) { c.settings.setStreamVisualSize(it) }
                            TvWaveAdjust(tr(R.string.opacity), appSettings.streamOpacity, "opacity", maximum = 1f, step = 0.1f) { c.settings.setStreamOpacity(it) }
                            TvWaveAdjust(tr(R.string.speed), appSettings.streamMotion, "speed") { c.settings.setStreamMotion(it) }
                            TvWaveAdjust(tr(R.string.intensity), appSettings.streamSensitivity, "intensity") { c.settings.setStreamSensitivity(it) }
                            }
                            TvWaveChoices(tr(R.string.artwork), listOf(true, false), appSettings.streamShowCover, "cover", { tr(if (it) R.string.on_2 else R.string.off) }) { c.settings.setStreamShowCover(it) }
                            if (appSettings.streamShowCover) TvWaveChoices(tr(R.string.cover_size), StreamCoverSize.entries, appSettings.streamCoverSize, "cover_size", { it.label }) { c.settings.setStreamCoverSize(it) }
                            TvWaveChoices(tr(R.string.progress_bar), listOf(true, false), appSettings.streamShowProgress, "progress", { tr(if (it) R.string.on_2 else R.string.off) }) { c.settings.setStreamShowProgress(it) }
                            TvWaveChoices(tr(R.string.prism_stream_appearance_panel), listOf(true, false), appSettings.streamGlassPanel, "panel", { tr(if (it) R.string.on_2 else R.string.off) }) { c.settings.setStreamGlassPanel(it) }
                            TvWaveChoices(tr(R.string.prism_stream_appearance_status), listOf(true, false), appSettings.streamShowStatus, "status", { tr(if (it) R.string.on_2 else R.string.off) }) { c.settings.setStreamShowStatus(it) }
                            if (appSettings.streamShowProgress) TvWaveChoices(tr(R.string.prism_stream_appearance_reverse), listOf(true, false), appSettings.streamReverseLayout, "progress_order", { tr(if (it) R.string.on_2 else R.string.off) }) { c.settings.setStreamReverseLayout(it) }
                            TvWaveChoices(tr(R.string.prism_stream_appearance_compact_scenarios), listOf(true, false), appSettings.streamCompactScenarios, "scenario_size", { tr(if (it) R.string.on_2 else R.string.off) }) { c.settings.setStreamCompactScenarios(it) }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            com.prism.music.ui.screens.VibeFlowField(Modifier.fillMaxWidth(), preview = true) { visual ->
                                @Composable fun PreviewIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, size: androidx.compose.ui.unit.Dp = 24.dp) {
                                    Box(Modifier.size(size + 16.dp), contentAlignment = Alignment.Center) {
                                        androidx.compose.material3.Icon(icon, null, Modifier.size(size))
                                    }
                                }
                                com.prism.music.ui.screens.VibeStationOverlay(appSettings,
                                    tr(R.string.prism_stream_station_default), hasTrack = true, visual = visual,
                                    artist = current?.primaryArtist ?: tr(R.string.artist),
                                    play = { com.prism.music.ui.screens.VibeStationAction(false, false, true, {}) },
                                    tune = { androidx.compose.material3.Text(tr(R.string.prism_stream_tune), style = androidx.compose.material3.MaterialTheme.typography.labelLarge) },
                                    credits = {
                                        com.prism.music.ui.screens.VibeCredits(appSettings, current?.title ?: tr(R.string.song),
                                            current?.primaryArtist ?: tr(R.string.artist), artwork = {
                                                com.prism.music.ui.screens.StandIn(current, it,
                                                    com.prism.music.ui.theme.CoverShapes.of(appSettings.shapes.player, appSettings.player.artCorners.dp),
                                                    com.prism.music.ui.screens.standIns.first())
                                            })
                                    },
                                    previous = { PreviewIcon(PrismIcons.Previous) }, next = { PreviewIcon(PrismIcons.Next) },
                                    progress = { Box(Modifier.fillMaxWidth().height(3.dp).background(scheme.onSurface.copy(alpha = 0.18f))) {
                                        Box(Modifier.fillMaxWidth(0.42f).fillMaxHeight().background(scheme.primary))
                                    } })
                            }
                        }
                    }
                    WaveTvButton(tr(R.string.done), { toggleAppearance(false) }, Modifier.tvItem("tv_wave/appearance/done"))
                }
            }
        } else if (tuning) {
            CompositionLocalProvider(LocalTvScreen provides "$screen/tune") {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(ui.gap(24.dp))) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(com.prism.music.ui.theme.LocalUi.current.gap(12.dp))) {
                    Text(tr(R.string.prism_stream_tune_stream), style = MaterialTheme.typography.headlineMedium)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ui.gap(16.dp))) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvWaveScale(WaveTuningScale.DISCOVERY, draft, "discovery") {
                            editor = WaveTuningScale.DISCOVERY.change(editor, it)
                        }
                        TvWaveScale(WaveTuningScale.LANGUAGE, draft, "language") {
                            editor = WaveTuningScale.LANGUAGE.change(editor, it)
                        }
                        TvWaveScale(WaveTuningScale.DIVERSITY, draft, "diversity") {
                            editor = WaveTuningScale.DIVERSITY.change(editor, it)
                        }
                    }
                    TvWaveChoices(tr(R.string.prism_stream_mood), MyWave.Mood.entries, draft.mood, "mood",
                        { when (it) {
                            MyWave.Mood.ANY -> tr(R.string.prism_stream_any)
                            MyWave.Mood.CALM -> tr(R.string.prism_stream_calm)
                            MyWave.Mood.ENERGETIC -> tr(R.string.prism_stream_energetic)
                        } }, { editor = editor.tune(draft.copy(mood = it)) })
                    TvWaveChoices(tr(R.string.prism_stream_activity), MyWave.Activity.entries, draft.activity, "activity",
                        { when (it) {
                            MyWave.Activity.ANY -> tr(R.string.prism_stream_any)
                            MyWave.Activity.WAKE -> tr(R.string.prism_stream_waking_up)
                            MyWave.Activity.ROAD -> tr(R.string.prism_stream_on_the_road)
                            MyWave.Activity.WORK -> tr(R.string.prism_stream_focus)
                            MyWave.Activity.TRAIN -> tr(R.string.prism_stream_workout)
                            MyWave.Activity.SLEEP -> tr(R.string.prism_stream_sleep)
                        } }, { editor = editor.tune(draft.copy(activity = it)) })
                    var advanced by rememberSaveable { mutableStateOf(false) }
                    WaveTvButton(tr(R.string.prism_stream_advanced), { advanced = !advanced },
                        Modifier.fillMaxWidth().tvItem("tv_wave/advanced"), icon = if (advanced) PrismIcons.ChevronUp else PrismIcons.ChevronDown)
                    if (advanced) {
                    TvWaveChoices(tr(R.string.prism_stream_popularity), MyWave.Depth.entries, draft.depth, "depth",
                        { when (it) {
                            MyWave.Depth.ANY -> tr(R.string.prism_stream_all)
                            MyWave.Depth.HITS -> tr(R.string.prism_stream_hits)
                            MyWave.Depth.DEEP -> tr(R.string.prism_stream_deep_cuts)
                        } }, { editor = editor.tune(draft.copy(depth = it)) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(com.prism.music.ui.theme.LocalUi.current.gap(12.dp))) {
                        WaveTvButton(tr(R.string.prism_stream_save_scenario), { editingPresetId = null; namingPreset = true },
                            Modifier.weight(1f).tvItem("tv_wave/save"), icon = PrismIcons.Add)
                        WaveTvButton(tr(R.string.prism_stream_reset), { editor = editor.reset() },
                            Modifier.tvItem("tv_wave/reset"), icon = PrismIcons.Refresh)
                    }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(com.prism.music.ui.theme.LocalUi.current.gap(12.dp))) {
                        WaveTvButton(tr(R.string.prism_stream_cancel), { toggleTuning(false) }, Modifier.weight(1f).tvItem("tv_wave/cancel"))
                        WaveTvButton(tr(R.string.prism_stream_done), {
                            val final = editor
                            c.settings.applyStreamTuning(null, baseline.presets, final.presets,
                                baseline.scenarioStyles, final.scenarioStyles)
                            toggleTuning(false)
                            if (final.style != style && c.queue.source.value.isMyWave) start(final.style, final.presetId)
                            else {
                                style = final.style
                                chosenPresetId = final.presetId
                                c.settings.setStreamStyle(final.style, final.presetId)
                            }
                        }, Modifier.weight(1f).tvItem("tv_wave/apply"), primary = true, icon = PrismIcons.Check)
                    }
                }
                var groupIndex by remember { mutableIntStateOf(0) }
                var page by remember(groupIndex) { mutableIntStateOf(0) }
                val group = WaveScenarios.Group.entries.getOrNull(groupIndex)
                val pages = WaveScenarios.all.filter { it.group == group }.chunked(4)
                val customPages = editor.presets.chunked(3)
                val pageCount = if (group == null) customPages.size else pages.size
                val visiblePage = page.coerceAtMost((pageCount - 1).coerceAtLeast(0))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    WaveTvButton(when(group) {
                        WaveScenarios.Group.DAY -> tr(R.string.prism_stream_time_of_day)
                        WaveScenarios.Group.ACTIVITY -> tr(R.string.prism_stream_activity)
                        WaveScenarios.Group.DISCOVERY -> tr(R.string.prism_stream_taste)
                        null -> tr(R.string.prism_stream_my_presets)
                    }, { groupIndex = (groupIndex + 1) % (WaveScenarios.Group.entries.size + 1) },
                        Modifier.fillMaxWidth().tvItem("tv_wave/group"))
                    if (group == null) {
                        if (editor.presets.isEmpty()) Text(tr(R.string.prism_stream_no_saved_presets), color = scheme.onSurfaceVariant)
                        customPages.getOrElse(visiblePage) { emptyList() }.forEach { preset ->
                            key(preset.id) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(com.prism.music.ui.theme.LocalUi.current.gap(12.dp)), verticalAlignment = Alignment.CenterVertically) {
                                    WaveTvButton(preset.name, { editor = editor.choose(preset) },
                                        Modifier.weight(1f).tvItem("tv_wave/custom/${preset.id}"), icon = VibePresetIcons.vector(iconContext, preset.iconId),
                                        primary = editor.presetId == preset.id && draft.origin == preset.origin)
                                    TvIconButton(PrismIcons.Edit, tr(R.string.prism_vibe_edit_preset),
                                        { editingPresetId = preset.id; namingPreset = true }, Modifier.tvItem("tv_wave/edit/${preset.id}"))
                                    TvIconButton(PrismIcons.Delete, tr(R.string.prism_stream_delete, preset.name),
                                        { editor = editor.delete(preset.id) }, Modifier.tvItem("tv_wave/delete/${preset.id}"))
                                }
                            }
                        }
                    } else pages.getOrElse(visiblePage) { emptyList() }.forEach { preset ->
                        WaveTvButton(preset.label,
                            { editor = editor.choose(preset) },
                            Modifier.fillMaxWidth().tvItem("tv_wave/preset/${preset.id}"),
                            primary = editor.presetId == preset.id || (editor.presetId == null && draft == WaveScenarios.resolve(preset, editor.scenarioStyles, draft.russianPercent)), icon = PrismIcons.waveScenario(preset.glyph))
                    }
                    if (pageCount > 1) WaveTvButton(tr(R.string.prism_stream_more_2), { page = (visiblePage + 1) % pageCount },
                        Modifier.tvItem("tv_wave/more"))
                }
            }
            }
        } else {
            com.prism.music.ui.screens.VibeScene {
            Box(Modifier.fillMaxSize()) {
            com.prism.music.ui.screens.VibeSphere(Modifier.matchParentSize(), now?.playing == true, style.diversity / 100f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    TvIconButton(PrismIcons.ThumbDown, tr(R.string.prism_vibe_reactions), { nav.go(TvRoute.MusicProfile) }, Modifier.tvItem("tv_wave/profile"))
                    TvIconButton(PrismIcons.Palette, tr(R.string.customize_prism), { toggleAppearance(true) }, Modifier.tvItem("tv_wave/appearance"))
                    val status = tr(if (starting) R.string.prism_stream_finding_music else if (now?.playing == true) R.string.prism_stream_playing else if (now != null) R.string.prism_stream_paused else R.string.prism_stream_personal_stream)
                    val subtitle = status.takeIf { appSettings.streamShowStatus }.orEmpty()
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (current != null && !contextual) TvIconButton(PrismIcons.Shuffle, tr(R.string.prism_stream_refresh), { if (!starting) start() }, Modifier.tvItem("tv_wave/refresh"))
                }
                Box(Modifier.weight(1f, fill = true).fillMaxWidth(), propagateMinConstraints = true) {
                    com.prism.music.ui.screens.VibeStationOverlay(appSettings,
                        com.prism.music.ui.screens.activeVibeTitle(source, activeSaved, activeScenario), hasTrack = current != null,
                        origin = com.prism.music.ui.screens.activeVibeOrigin(source, current),
                        artist = now?.artist ?: current?.primaryArtist,
                        play = {
                            TvIconButton(if (now?.playing == true) PrismIcons.Pause else PrismIcons.Play, tr(when {
                                starting && current == null -> R.string.prism_stream_finding_music
                                now?.playing == true -> R.string.widget_pause
                                current != null -> R.string.widget_play
                                else -> R.string.prism_stream_start_listening
                            }), { if (current != null) c.player.togglePlay() else if (!starting) start() },
                                Modifier.tvItem("tv_wave/play", entry = true), big = true,
                                vibeStyle = appSettings.streamActionStyle,
                                diameter = vibeActionDiameter(ui.gap(if (appSettings.streamLayout == StreamLayout.COMPACT) 68.dp else 84.dp), appSettings.streamActionSize.fraction))
                        }, tune = {
                            if (!contextual) WaveTvButton(tr(R.string.prism_stream_tune), { toggleTuning(true) },
                                Modifier.tvItem("tv_wave/tune"), icon = PrismIcons.Customize)
                        }, credits = {
                            if (current != null) com.prism.music.ui.screens.VibeCredits(appSettings,
                                now?.title ?: current.title, now?.artist ?: current.primaryArtist,
                                artwork = { Artwork(now?.art, it, shape = com.prism.music.ui.theme.CoverShapes.of(appSettings.shapes.player, appSettings.player.artCorners.dp), size = 180) })
                        }, previous = {
                            TvIconButton(PrismIcons.Previous, tr(R.string.widget_previous),
                                { if (c.player.hasPrevious.value) c.player.previous() }, Modifier.tvItem("tv_wave/previous"))
                        }, next = {
                            TvIconButton(PrismIcons.Next, tr(R.string.prism_stream_next),
                                { if (c.player.hasNext.value) c.player.next() }, Modifier.tvItem("tv_wave/next"))
                        }, extra = {
                            TvIconButton(PrismIcons.ChevronRight, tr(R.string.prism_stream_player), { nav.go(TvRoute.Player) }, Modifier.tvItem("tv_wave/player"))
                        }, progress = {
                            if (now != null && now.total.isNotEmpty()) TvSeekLine(now, onSeek = { c.player.seekBy(it) }, onPlayPause = { c.player.togglePlay() },
                                modifier = Modifier.fillMaxWidth().tvItem("tv_wave/seek"))
                        })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ui.gap(16.dp)), verticalAlignment = Alignment.CenterVertically) {
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val compactScenarios = appSettings.streamCompactScenarios
                        val slotWidth = if (compactScenarios) 168.dp else 112.dp
                        val spacing = ui.gap(16.dp)
                        val stride = with(androidx.compose.ui.platform.LocalDensity.current) { (slotWidth + spacing).toPx() }.coerceAtLeast(1f)
                        val shortcuts = remember(style, savedPresets, scenarioOrder, appSettings.streamScenarioStyles, activeSaved?.id, activeScenario?.id) {
                            vibeShortcuts(style, savedPresets, scenarioOrder, appSettings.streamScenarioStyles, activeSaved?.id, activeScenario?.id) { VibePresetIcons.vector(iconContext, it.iconId) }
                        }
                        val keys = shortcuts.map { it.key }
                        val padding = ((maxWidth - slotWidth) / 2).coerceAtLeast(0.dp)
                        val initialScenario = shortcuts.indexOfFirst { it.selected && !contextual }.coerceAtLeast(0)
                        val scenarioScroll = rememberLazyListState(vibeOrbitStart(shortcuts.size, initialScenario))
                        var previousKeys by remember { mutableStateOf(keys) }
                        LaunchedEffect(keys) {
                            if (previousKeys != keys && keys.isNotEmpty()) {
                                val index = scenarioScroll.firstVisibleItemIndex
                                val anchor = previousKeys.getOrNull(vibeOrbitIndex(index, previousKeys.size))
                                val next = previousKeys.getOrNull(vibeOrbitIndex(index + 1, previousKeys.size))
                                val target = anchor?.let { keys.indexOf(it) }?.takeIf { it >= 0 } ?: (next?.let { keys.indexOf(it) } ?: -1).coerceAtLeast(0)
                                scenarioScroll.scrollToItem(vibeOrbitNearest(index, keys.size, target))
                            }
                            previousKeys = keys
                        }
                        LazyRow(Modifier.fillMaxWidth().scenarioEdgeFade({ scenarioScroll.canScrollBackward }, { scenarioScroll.canScrollForward })
                            .rememberedFocus().focusGroup(),
                            state = scenarioScroll,
                            contentPadding = PaddingValues(horizontal = padding, vertical = if (compactScenarios) 4.dp else 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
                            items(vibeOrbitCount(shortcuts.size), key = { index -> "orbit:$index:${shortcuts[vibeOrbitIndex(index, shortcuts.size)].key}" }) { index ->
                                val shortcut = shortcuts[vibeOrbitIndex(index, shortcuts.size)]
                                val orbit = if (compactScenarios) Modifier else Modifier.vibeOrbit {
                                    val info = scenarioScroll.layoutInfo
                                    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                    item?.let { (it.offset + it.size / 2f - center) / stride } ?: 0f
                                }
                                Box(Modifier.width(slotWidth).then(orbit), contentAlignment = Alignment.Center) {
                                    ScenarioTile(shortcut.label, shortcut.icon, shortcut.selected && !contextual, onClick = {
                                        val target = shortcut.style
                                        if (target != null && (current == null || contextual || !shortcut.selected)) start(target, shortcut.scenarioId)
                                    }, modifier = Modifier.tvItem("tv_wave/" + when {
                                        shortcut.key.startsWith("scenario:") -> "quick/" + shortcut.scenarioId
                                        shortcut.key.startsWith("custom:") -> "custom/" + shortcut.scenarioId
                                        else -> shortcut.key
                                    } + "/orbit/$index").onFocusChanged { focus ->
                                        if (focus.isFocused) scope.launch { scenarioScroll.animateScrollToItem(index) }
                                    })
                                }
                            }
                        }
                    }
                }
            }
            }
            }
        }
    }
    if (tuning && namingPreset) {
        val preset = editor.presets.firstOrNull { it.id == editingPresetId }
        WavePresetNameDialog(editor.presets.filter { it.id != preset?.id }.map { it.name }, { namingPreset = false },
            initialName = preset?.name.orEmpty(), initialIcon = preset?.iconId ?: "bookmark", editing = preset != null) { name, icon ->
            editor = if (preset == null) editor.save(name, iconId = icon) else editor.editPreset(preset.id, name, icon)
            namingPreset = false
            editingPresetId = null
        }
    }
}

/** Consistent, remote-friendly version of the phone's numeric tuning controls. */
@Composable
private fun TvWaveScale(scale: WaveTuningScale, style: MyWave.Style, tag: String, change: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ui = com.prism.music.ui.theme.LocalUi.current
    val value = scale.value(style)
    val position = scale.position(value)
    val automatic = scale.automatic && value < 0
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
        val adjustedLabel = tr(if (scale.ratio) scale.left else scale.title)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(com.prism.music.ui.theme.LocalUi.current.gap(12.dp))) {
            TvIconButton(PrismIcons.Remove, tr(R.string.prism_stream_decrease_share, adjustedLabel),
                { change(scale.fromPosition((position - scale.step).toFloat())) },
                Modifier.tvItem("tv_wave/$tag/down", entry = tag == "discovery"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ui.gap(4.dp))) {
                WaveScaleLabels(scale, value, scheme.primary, scheme.onSurfaceVariant,
                    MaterialTheme.typography.labelMedium, MaterialTheme.typography.titleMedium,
                    center = if (scale.automatic) {
                        { WaveTvButton(tr(R.string.prism_stream_auto), { change(if (automatic) 50 else -1) },
                            Modifier.tvItem("tv_wave/auto"), primary = automatic) }
                    } else null)
                WaveScaleRail(scale, value, scheme.primary, scheme.onSurfaceVariant, scheme.surface,
                    showThumb = true)
            }
            TvIconButton(PrismIcons.Add, tr(R.string.prism_stream_increase_share, adjustedLabel),
                { change(scale.fromPosition((position + scale.step).toFloat())) },
                Modifier.tvItem("tv_wave/$tag/up"))
        }

    }
}

@Composable
private fun <T> TvWaveChoices(title: String, values: List<T>, selected: T, tag: String,
    name: (T) -> String, choose: (T) -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        values.chunked(2).forEachIndexed { row, items ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(com.prism.music.ui.theme.LocalUi.current.gap(12.dp))) {
                items.forEachIndexed { index, value ->
                    WaveTvButton(name(value), { choose(value) }, Modifier.weight(1f).tvItem("tv_wave/$tag/$row/$index"),
                        primary = selected == value)
                }
                if (items.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Same 25% increments as phone sliders, with only two remote focus stops per parameter. */
@Composable
private fun TvWaveAdjust(title: String, value: Float, tag: String, maximum: Float = 2f, step: Float = 0.25f, change: (Float) -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TvIconButton(PrismIcons.Remove, tr(R.string.prism_stream_decrease_share, title),
            { change((value - step).coerceIn(0f, maximum)) }, Modifier.tvItem("tv_wave/$tag/down"))
        Text("${kotlin.math.round(value * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        TvIconButton(PrismIcons.Add, tr(R.string.prism_stream_increase_share, title),
            { change((value + step).coerceIn(0f, maximum)) }, Modifier.tvItem("tv_wave/$tag/up"))
    }
}

/** Only Stream needs two-line, bounded choice labels; normal TV buttons retain upstream layout. */
@Composable
private fun WaveTvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, primary: Boolean = false) {
    TvButton(text, onClick, modifier, icon, primary, fitLabel = true)
}
