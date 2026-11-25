package com.prism.music.ui.tv

import com.prism.music.R
import com.prism.music.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.data.foryou.MyWave
import com.prism.music.data.foryou.WaveSettingsDraft
import androidx.compose.foundation.verticalScroll
import com.prism.music.data.foryou.WaveScenarios
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.scenarioEdgeFade
import com.prism.music.ui.components.WavePresetNameDialog
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
    val scenarioScroll = rememberScrollState()
    val appSettings by c.settings.flow.collectAsState()
    val savedPresets = appSettings.streamPresets
    var namingPreset by rememberSaveable { mutableStateOf(false) }
    var chosenPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    val scenarioOrder = remember {
        val clock = java.util.Calendar.getInstance()
        WaveScenarios.stripOrder(clock.get(java.util.Calendar.HOUR_OF_DAY),
            clock.get(java.util.Calendar.DAY_OF_WEEK) in listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY))
    }
    val song by c.player.currentSong.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    val now = rememberTvNow(c).takeIf { source.isMyWave }
    val current = song.takeIf { source.isMyWave }

    val title = tr(R.string.stream)
    fun restored() = MyWave.restore(source.originId, appSettings.streamStyleOrigin, appSettings.streamRussianPercent)
    var style by remember(source.originId) { mutableStateOf(restored()) }
    var editor by remember { mutableStateOf(WaveSettingsDraft(style, appSettings.streamThemeColors, savedPresets, chosenPresetId)) }
    var baseline by remember { mutableStateOf(editor) }
    val draft = editor.style
    val activeSaved = savedPresets.firstOrNull { it.id == chosenPresetId && it.origin == style.origin }
        ?: savedPresets.firstOrNull { chosenPresetId == null && it.origin == style.origin }
    var tuning by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var base by remember { mutableStateOf(emptyList<Song>()) }
    val screen = LocalTvScreen.current
    val memory = LocalTvFocusMemory.current
    fun toggleTuning(open: Boolean) {
        if (open) {
            baseline = WaveSettingsDraft(style, appSettings.streamThemeColors, savedPresets, chosenPresetId)
            editor = baseline
        }
        namingPreset = false
        memory.opening(if (open) "$screen/tune" else screen)
        tuning = open
    }
    BackHandler(tuning) { toggleTuning(false) }
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
    fun start(selected: MyWave.Style = style) {
        job?.cancel()
        val version = ++request
        val old = c.queue.source.value
        val previous = c.player.currentSong.value.takeIf { old.isMyWave }
        starting = true
        job = scope.launch {
            try {
                val candidates = c.yourMusic.startWave(base, selected, previous) {
                    if (base.isEmpty()) base = loadBase()
                    base
                }
                if (version != request || c.queue.source.value !== old) return@launch
                val current = c.player.currentSong.value.takeIf { old.isMyWave }
                val picks = MyWave.initialPicks(candidates, current, offline = !c.network.isOnline())
                if (picks.isEmpty()) c.player.reportError(if (!c.network.isOnline()) tr(R.string.prism_stream_no_matching_downloaded_tracks) else tr(R.string.prism_stream_not_enough_matching_tracks))
                else {
                    style = selected
                    c.recFeedback.served(picks.map { it.id })
                    c.player.playQueue(picks, 0, QueueSource(QueueKind.RADIO, title, seedId = picks.first().id, originId = selected.origin))
                    c.settings.setStreamStyle(selected)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (version == request && c.queue.source.value === old)
                    c.player.reportError(tr(R.string.prism_stream_could_not_update_stream))
            }
            finally { if (version == request) starting = false }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(TvSafe)) {
        val narrow = maxWidth < 760.dp
        val scheme = MaterialTheme.colorScheme
        if (tuning) {
            CompositionLocalProvider(LocalTvScreen provides "$screen/tune") {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(R.string.prism_stream_tune_stream), style = MaterialTheme.typography.headlineMedium)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvWaveScale(tr(R.string.prism_stream_discovery_mix), draft.discoveryPercent,
                        tr(R.string.prism_stream_liked_songs), tr(R.string.prism_stream_discoveries), "discovery",
                        { editor = editor.discovery(it) })
                    val language = draft.russianPercent.takeIf { it >= 0 } ?: 50
                    TvWaveScale(tr(R.string.prism_stream_music_language), language,
                        tr(R.string.prism_stream_foreign), tr(R.string.prism_stream_russian), "language",
                        { editor = editor.tune(draft.copy(russianPercent = it)) },
                        automaticLabel = tr(R.string.prism_stream_auto).takeIf { draft.russianPercent < 0 })
                    TvButton(tr(R.string.prism_stream_auto), { editor = editor.tune(draft.copy(russianPercent = -1)) },
                        Modifier.tvItem("tv_wave/auto"), primary = draft.russianPercent < 0)
                    TvWaveScale(tr(R.string.prism_stream_variety), draft.diversity,
                        tr(R.string.prism_stream_less), tr(R.string.prism_stream_more), "diversity",
                        { editor = editor.tune(draft.copy(diversity = it)) }, step = 10, ratio = false)
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
                    TvWaveChoices(tr(R.string.prism_stream_popularity), MyWave.Depth.entries, draft.depth, "depth",
                        { when (it) {
                            MyWave.Depth.ANY -> tr(R.string.prism_stream_all)
                            MyWave.Depth.HITS -> tr(R.string.prism_stream_hits)
                            MyWave.Depth.DEEP -> tr(R.string.prism_stream_deep_cuts)
                        } }, { editor = editor.tune(draft.copy(depth = it)) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(tr(R.string.prism_stream_save_preset), { namingPreset = true },
                            Modifier.weight(1f).tvItem("tv_wave/save"), icon = PrismIcons.Add)
                        TvButton(tr(R.string.prism_stream_reset), { editor = editor.reset() },
                            Modifier.tvItem("tv_wave/reset"), icon = PrismIcons.Refresh)
                    }
                    }
                    Text(tr(R.string.prism_stream_changes_apply_after_confirmation),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(tr(R.string.prism_stream_cancel), { toggleTuning(false) }, Modifier.weight(1f).tvItem("tv_wave/cancel"))
                        TvButton(tr(R.string.prism_stream_done), {
                            val final = editor
                            c.settings.applyStreamTuning(final.themeColors.takeIf { it != baseline.themeColors }, baseline.presets, final.presets)
                            chosenPresetId = final.presetId
                            toggleTuning(false)
                            if (final.style != style) {
                                if (c.queue.source.value.isMyWave) start(final.style)
                                else { style = final.style; c.settings.setStreamStyle(final.style) }
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
                    Text(tr(R.string.prism_stream_scenarios), style = MaterialTheme.typography.headlineSmall)
                    TvButton(when(group) {
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
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TvButton(preset.name, { editor = editor.choose(preset) },
                                        Modifier.weight(1f).tvItem("tv_wave/custom/${preset.id}"), icon = PrismIcons.Bookmark,
                                        primary = editor.presetId == preset.id && draft.origin == preset.origin)
                                    TvIconButton(PrismIcons.Delete, tr(R.string.prism_stream_delete) + preset.name,
                                        { editor = editor.delete(preset.id) }, Modifier.tvItem("tv_wave/delete/${preset.id}"))
                                }
                            }
                        }
                    } else pages.getOrElse(visiblePage) { emptyList() }.forEach { preset ->
                        TvButton(preset.label,
                            { editor = editor.choose(preset) },
                            Modifier.fillMaxWidth().tvItem("tv_wave/preset/${preset.id}"),
                            primary = draft.copy(russianPercent = -1) == preset.style, icon = PrismIcons.waveScenario(preset.glyph))
                    }
                    if (pageCount > 1) TvButton(tr(R.string.prism_stream_more_2), { page = (visiblePage + 1) % pageCount },
                        Modifier.tvItem("tv_wave/more"))
                }
            }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                }
                Row(Modifier.weight(1f).fillMaxWidth().tvGlass(TvShapes.card).padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (narrow) 20.dp else 36.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BoxWithConstraints(Modifier.weight(if (narrow) 0.65f else 0.85f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Artwork(now?.art, Modifier.size(minOf(maxWidth, maxHeight, 340.dp)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), size = 1080)
                    }
                    Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 16.dp)) {
                        Text(now?.title ?: tr(R.string.prism_stream_music_in_your_rhythm),
                            style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(now?.artist ?: tr(R.string.prism_stream_start_listening), style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (now != null && now.total.isNotEmpty()) TvSeekLine(now,
                            onSeek = { c.player.seekBy(it) }, onPlayPause = { c.player.togglePlay() },
                            modifier = Modifier.fillMaxWidth().tvItem("tv_wave/seek"))
                        else TvProgress(now?.fraction ?: 0f, now?.played ?: "0:00", now?.total ?: "–:–")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            TvIconButton(if (now?.liked == true) PrismIcons.HeartFilled else PrismIcons.Heart, tr(R.string.prism_stream_like),
                                { current?.let { c.library.toggleLike(it) } }, Modifier.tvItem("tv_wave/like"), on = now?.liked == true)
                            TvIconButton(PrismIcons.Previous, tr(R.string.prism_stream_previous),
                                { if (current != null) c.player.previous() }, Modifier.tvItem("tv_wave/previous"))
                            TvIconButton(if (now?.playing == true) PrismIcons.Pause else PrismIcons.Play, tr(R.string.prism_stream_play_pause),
                                { if (current != null) c.player.togglePlay() else if (!starting) start() },
                                Modifier.tvItem("tv_wave/play", entry = true), big = true)
                            TvIconButton(PrismIcons.Next, tr(R.string.prism_stream_next),
                                { if (current != null) c.player.next() }, Modifier.tvItem("tv_wave/next"))
                            TvIconButton(if (current?.id in disliked) PrismIcons.ThumbDownFilled else PrismIcons.ThumbDown, tr(R.string.prism_stream_not_for_me),
                                { current?.let { val on = it.id in disliked; c.library.setDisliked(it, !on); if (!on && c.player.hasNext.value) c.player.next() } },
                                Modifier.tvItem("tv_wave/dislike"), on = current?.id in disliked)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TvButton(if (starting) tr(R.string.prism_stream_finding_music) else tr(R.string.prism_stream_refresh),
                                { if (!starting) start() }, Modifier.weight(1f).tvItem("tv_wave/refresh"), icon = PrismIcons.Refresh)
                            if (current != null) TvButton(tr(R.string.prism_stream_player), { nav.go(TvRoute.Player) }, Modifier.weight(1f).tvItem("tv_wave/player"))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvScenarioDisc(tr(R.string.prism_stream_tune), PrismIcons.Customize, selected = false,
                        onClick = { toggleTuning(true) }, modifier = Modifier.tvItem("tv_wave/tune"))
                    val active = WaveScenarios.all.firstOrNull { it.style == style.copy(russianPercent = -1) }
                    Row(Modifier.weight(1f).scenarioEdgeFade({ scenarioScroll.canScrollBackward }, { scenarioScroll.canScrollForward })
                        .horizontalScroll(scenarioScroll).padding(vertical = 8.dp, horizontal = 14.dp)
                        .rememberedFocus().focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        savedPresets.forEach { preset ->
                            key(preset.id) {
                                TvScenarioDisc(preset.name, PrismIcons.Bookmark, activeSaved?.id == preset.id,
                                    { chosenPresetId = preset.id; start(MyWave.style(preset.origin)) }, Modifier.tvItem("tv_wave/custom/${preset.id}"))
                            }
                        }
                        scenarioOrder.forEach { preset ->
                            TvScenarioDisc(label = preset.label,
                                icon = PrismIcons.waveScenario(preset.glyph), selected = activeSaved == null && active?.id == preset.id,
                                onClick = { chosenPresetId = preset.id; start(preset.style.copy(russianPercent = style.russianPercent)) },
                                modifier = Modifier.tvItem("tv_wave/quick/${preset.id}"))
                        }
                    }
                }
            }
        }
    }
    if (tuning && namingPreset) WavePresetNameDialog( editor.presets.map { it.name }, { namingPreset = false }) { name ->
        editor = editor.save(name)
        namingPreset = false
    }
}

/** Consistent, remote-friendly version of the phone's numeric tuning controls. */
@Composable
private fun TvWaveScale(title: String, value: Int, left: String, right: String,
    tag: String, change: (Int) -> Unit, step: Int = 5, ratio: Boolean = true, automaticLabel: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (automaticLabel != null) Text(automaticLabel, style = MaterialTheme.typography.titleMedium, color = scheme.primary)
        }
        val percent = value.coerceIn(0, 100)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(left, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                if (ratio && automaticLabel == null) Text("${100 - percent}%", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(right, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                if (ratio && automaticLabel == null) Text("$percent%", style = MaterialTheme.typography.titleMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("−$step", { change((value - step).coerceIn(0, 100)) }, Modifier.tvItem("tv_wave/$tag/down", entry = tag == "discovery"))
            androidx.compose.foundation.Canvas(Modifier.weight(1f).height(12.dp)) {
                val y = size.height / 2
                drawLine(scheme.onSurfaceVariant.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                if (!ratio) drawLine(scheme.primary, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(size.width * value / 100f, y), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(if (automaticLabel != null) scheme.onSurfaceVariant else scheme.primary, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * value / 100f, y))
            }
            TvButton("+$step", { change((value + step).coerceIn(0, 100)) }, Modifier.tvItem("tv_wave/$tag/up"))
        }
    }
}

@Composable
private fun <T> TvWaveChoices(title: String, values: List<T>, selected: T, tag: String,
    name: (T) -> String, choose: (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        values.chunked(2).forEachIndexed { row, items ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEachIndexed { index, value ->
                    TvButton(name(value), { choose(value) }, Modifier.weight(1f).tvItem("tv_wave/$tag/$row/$index"),
                        primary = selected == value)
                }
                if (items.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
