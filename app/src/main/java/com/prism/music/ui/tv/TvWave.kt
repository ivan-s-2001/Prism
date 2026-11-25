package com.prism.music.ui.tv

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
    val ru = java.util.Locale.getDefault().language == "ru"
    fun l(a: String, b: String) = if (ru) a else b
    val title = l("Поток", "Stream")
    fun restored(): MyWave.Style = when {
        source.isMyWave -> MyWave.style(source.originId)
        MyWave.isOrigin(appSettings.streamStyleOrigin) -> MyWave.style(appSettings.streamStyleOrigin)
        else -> MyWave.Style(russianPercent = appSettings.streamRussianPercent)
    }
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
                var picks = c.yourMusic.waveBatch(base, selected, 0, previous, emptyList(), count = 2, fast = true)
                if (picks.isEmpty()) {
                    if (base.isEmpty()) base = loadBase()
                    picks = c.yourMusic.waveBatch(base, selected, 0, previous, emptyList(), count = 2)
                }
                if (version != request || c.queue.source.value !== old) return@launch
                if (picks.isEmpty()) c.player.reportError(if (!c.network.isOnline()) l("Нет подходящих скачанных треков", "No matching downloaded tracks") else l("Недостаточно подходящих треков", "Not enough matching tracks"))
                else {
                    style = selected
                    c.recFeedback.served(picks.map { it.id })
                    c.player.playQueue(picks, 0, QueueSource(QueueKind.RADIO, title, seedId = picks.first().id, originId = selected.origin))
                    c.settings.setStreamStyle(selected)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { c.player.reportError(l("Не удалось обновить поток", "Could not update Stream")) }
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
                    Text(l("Настроить поток", "Tune Stream"), style = MaterialTheme.typography.headlineMedium)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvWaveScale(l("Характер потока", "Discovery mix"), draft.discoveryPercent,
                        l("Лайки", "Liked songs"), l("Открытия", "Discoveries"), "discovery",
                        { editor = editor.discovery(it) })
                    val language = draft.russianPercent.takeIf { it >= 0 } ?: 50
                    TvWaveScale(l("Язык музыки", "Music language"), language,
                        l("Иностранная", "Foreign"), l("Русская", "Russian"), "language",
                        { editor = editor.tune(draft.copy(russianPercent = it)) },
                        automaticLabel = l("Авто", "Auto").takeIf { draft.russianPercent < 0 })
                    TvButton(l("Авто", "Auto"), { editor = editor.tune(draft.copy(russianPercent = -1)) },
                        Modifier.tvItem("tv_wave/auto"), primary = draft.russianPercent < 0)
                    TvWaveScale(l("Разнообразие", "Variety"), draft.diversity,
                        l("Меньше", "Less"), l("Больше", "More"), "diversity",
                        { editor = editor.tune(draft.copy(diversity = it)) }, step = 10, ratio = false)
                    TvWaveChoices(l("Настроение", "Mood"), MyWave.Mood.entries, draft.mood, "mood",
                        { when (it) {
                            MyWave.Mood.ANY -> l("Любое", "Any")
                            MyWave.Mood.CALM -> l("Спокойное", "Calm")
                            MyWave.Mood.ENERGETIC -> l("Бодрое", "Energetic")
                        } }, { editor = editor.tune(draft.copy(mood = it)) })
                    TvWaveChoices(l("Занятие", "Activity"), MyWave.Activity.entries, draft.activity, "activity",
                        { when (it) {
                            MyWave.Activity.ANY -> l("Любое", "Any")
                            MyWave.Activity.WAKE -> l("Просыпаюсь", "Waking up")
                            MyWave.Activity.ROAD -> l("В дороге", "On the road")
                            MyWave.Activity.WORK -> l("Работаю", "Focus")
                            MyWave.Activity.TRAIN -> l("Тренируюсь", "Workout")
                            MyWave.Activity.SLEEP -> l("Засыпаю", "Sleep")
                        } }, { editor = editor.tune(draft.copy(activity = it)) })
                    TvWaveChoices(l("Популярность", "Popularity"), MyWave.Depth.entries, draft.depth, "depth",
                        { when (it) {
                            MyWave.Depth.ANY -> l("Любая", "Any")
                            MyWave.Depth.HITS -> l("Хиты", "Hits")
                            MyWave.Depth.DEEP -> l("Редкое", "Deep cuts")
                        } }, { editor = editor.tune(draft.copy(depth = it)) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(l("Сохранить пресет", "Save preset"), { namingPreset = true },
                            Modifier.weight(1f).tvItem("tv_wave/save"), icon = PrismIcons.Add)
                        TvButton(l("Сбросить", "Reset"), { editor = editor.reset() },
                            Modifier.tvItem("tv_wave/reset"), icon = PrismIcons.Refresh)
                    }
                    }
                    Text(l("Изменения вступят в силу после подтверждения", "Changes apply after confirmation"),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(l("Отмена", "Cancel"), { toggleTuning(false) }, Modifier.weight(1f).tvItem("tv_wave/cancel"))
                        TvButton(l("Готово", "Done"), {
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
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    Text(l("Сценарии", "Scenarios"), style = MaterialTheme.typography.headlineSmall)
                    TvButton(when(group) {
                        WaveScenarios.Group.DAY -> l("Время дня", "Time of day")
                        WaveScenarios.Group.ACTIVITY -> l("Занятие", "Activity")
                        WaveScenarios.Group.DISCOVERY -> l("Вкус", "Taste")
                        null -> l("Мои пресеты", "My presets")
                    }, { groupIndex = (groupIndex + 1) % (WaveScenarios.Group.entries.size + 1) },
                        Modifier.fillMaxWidth().tvItem("tv_wave/group"))
                    if (group == null) {
                        if (editor.presets.isEmpty()) Text(l("Нет сохранённых пресетов", "No saved presets"), color = scheme.onSurfaceVariant)
                        customPages.getOrElse(visiblePage) { emptyList() }.forEach { preset ->
                            key(preset.id) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TvButton(preset.name, { editor = editor.choose(preset) },
                                        Modifier.weight(1f).tvItem("tv_wave/custom/${preset.id}"), icon = PrismIcons.Bookmark,
                                        primary = editor.presetId == preset.id && draft.origin == preset.origin)
                                    TvIconButton(PrismIcons.Delete, l("Удалить ", "Delete ") + preset.name,
                                        { editor = editor.delete(preset.id) }, Modifier.tvItem("tv_wave/delete/${preset.id}"))
                                }
                            }
                        }
                    } else pages.getOrElse(visiblePage) { emptyList() }.forEach { preset ->
                        TvButton(if (ru) preset.ru else preset.en,
                            { editor = editor.choose(preset) },
                            Modifier.fillMaxWidth().tvItem("tv_wave/preset/${preset.id}"),
                            primary = draft.copy(russianPercent = -1) == preset.style, icon = PrismIcons.waveScenario(preset.glyph))
                    }
                    if (pageCount > 1) TvButton(l("Ещё", "More"), { page = (visiblePage + 1) % pageCount },
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
                        Text(now?.title ?: l("Музыка в твоём ритме", "Music in your rhythm"),
                            style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(now?.artist ?: l("Начни слушать", "Start listening"), style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (now != null && now.total.isNotEmpty()) TvSeekLine(now,
                            onSeek = { c.player.seekBy(it) }, onPlayPause = { c.player.togglePlay() },
                            modifier = Modifier.fillMaxWidth().tvItem("tv_wave/seek"))
                        else TvProgress(now?.fraction ?: 0f, now?.played ?: "0:00", now?.total ?: "–:–")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            TvIconButton(if (now?.liked == true) PrismIcons.HeartFilled else PrismIcons.Heart, l("Нравится", "Like"),
                                { current?.let { c.library.toggleLike(it) } }, Modifier.tvItem("tv_wave/like"), on = now?.liked == true)
                            TvIconButton(PrismIcons.Previous, l("Предыдущий", "Previous"),
                                { if (current != null) c.player.previous() }, Modifier.tvItem("tv_wave/previous"))
                            TvIconButton(if (now?.playing == true) PrismIcons.Pause else PrismIcons.Play, l("Играть / пауза", "Play / pause"),
                                { if (current != null) c.player.togglePlay() else if (!starting) start() },
                                Modifier.tvItem("tv_wave/play", entry = true), big = true)
                            TvIconButton(PrismIcons.Next, l("Следующий", "Next"),
                                { if (current != null) c.player.next() }, Modifier.tvItem("tv_wave/next"))
                            TvIconButton(if (current?.id in disliked) PrismIcons.ThumbDownFilled else PrismIcons.ThumbDown, l("Не моё", "Not for me"),
                                { current?.let { val on = it.id in disliked; c.library.setDisliked(it, !on); if (!on && c.player.hasNext.value) c.player.next() } },
                                Modifier.tvItem("tv_wave/dislike"), on = current?.id in disliked)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TvButton(if (starting) l("Подбираем…", "Finding music…") else l("Встряхнуть", "Refresh"),
                                { if (!starting) start() }, Modifier.tvItem("tv_wave/refresh"), icon = PrismIcons.Refresh)
                            if (current != null) TvButton(l("Плеер", "Player"), { nav.go(TvRoute.Player) }, Modifier.tvItem("tv_wave/player"))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvScenarioDisc(l("Настройки", "Tune"), PrismIcons.Equalizer, selected = false,
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
                            TvScenarioDisc(label = if (ru) preset.ru else preset.en,
                                icon = PrismIcons.waveScenario(preset.glyph), selected = activeSaved == null && active?.id == preset.id,
                                onClick = { chosenPresetId = preset.id; start(preset.style.copy(russianPercent = style.russianPercent)) },
                                modifier = Modifier.tvItem("tv_wave/quick/${preset.id}"))
                        }
                    }
                }
            }
        }
    }
    if (tuning && namingPreset) WavePresetNameDialog(ru, editor.presets.map { it.name }, { namingPreset = false }) { name ->
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
