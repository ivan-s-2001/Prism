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
import com.prism.music.data.foryou.WaveScenarios
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.scenarioEdgeFade
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
    fun restored() = MyWave.style(source.originId).let {
        if (source.isMyWave) it else it.copy(russianPercent = c.settings.current.streamRussianPercent)
    }
    var style by remember(source.originId) { mutableStateOf(restored()) }
    var draft by remember { mutableStateOf(style) }
    var tuning by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var base by remember { mutableStateOf(emptyList<Song>()) }
    val screen = LocalTvScreen.current
    val memory = LocalTvFocusMemory.current
    fun toggleTuning(open: Boolean) {
        if (open) draft = style
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
                    c.settings.setStreamRussianPercent(selected.russianPercent)
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
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    Text(l("Настроить поток", "Tune Stream"), style = MaterialTheme.typography.headlineMedium)
                    @Composable fun Ratio(label: String, percent: Int, change: (Int) -> Unit, tag: String) {
                        Text(label, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvButton("−5", { change((percent - 5).coerceIn(0, 100)) }, Modifier.tvItem("tv_wave/$tag/down", entry = tag == "discovery"))
                            Text("$percent%", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            TvButton("+5", { change((percent + 5).coerceIn(0, 100)) }, Modifier.tvItem("tv_wave/$tag/up"))
                        }
                    }
                    Ratio(l("Открытия", "Discoveries"), draft.discoveryPercent, { p ->
                        draft = draft.copy(discoveryPercent = p, character = when (p) {
                            0 -> MyWave.Character.FAMILIAR
                            100 -> MyWave.Character.DISCOVERY
                            else -> MyWave.Character.BALANCED
                        })
                    }, "discovery")
                    Ratio(l("Русская музыка", "Russian music"), draft.russianPercent.takeIf { it >= 0 } ?: 50,
                        { draft = draft.copy(russianPercent = it) }, "language")
                    TvButton(if (draft.russianPercent < 0) l("Язык: авто", "Language: auto") else l("Включить авто", "Use auto"),
                        { draft = draft.copy(russianPercent = -1) }, Modifier.tvItem("tv_wave/auto"), primary = draft.russianPercent < 0)
                    Text(l("Язык определяется приблизительно по названиям.", "Language is estimated from titles."),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    TvButton(l("Применить", "Apply"), { val chosen = draft; toggleTuning(false); start(chosen) },
                        Modifier.tvItem("tv_wave/apply"), primary = true)
                }
                var group by remember { mutableStateOf(WaveScenarios.Group.DAY) }
                var page by remember(group) { mutableIntStateOf(0) }
                val options = WaveScenarios.all.filter { it.group == group }
                val pages = options.chunked(4)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    Text(l("Сценарии", "Scenarios"), style = MaterialTheme.typography.headlineSmall)
                    TvButton(when(group) {
                        WaveScenarios.Group.DAY -> l("Время дня", "Time of day")
                        WaveScenarios.Group.ACTIVITY -> l("Занятие", "Activity")
                        WaveScenarios.Group.DISCOVERY -> l("Вкус", "Taste")
                    }, { group = WaveScenarios.Group.entries[(group.ordinal + 1) % WaveScenarios.Group.entries.size] },
                        Modifier.fillMaxWidth().tvItem("tv_wave/group"))
                    pages.getOrElse(page) { emptyList() }.forEach { preset ->
                        TvButton(if (ru) preset.ru else preset.en,
                            { draft = preset.style.copy(russianPercent = draft.russianPercent) },
                            Modifier.fillMaxWidth().tvItem("tv_wave/preset/${preset.id}"),
                            primary = draft.copy(russianPercent = -1) == preset.style, icon = PrismIcons.waveScenario(preset.glyph))
                    }
                    if (pages.size > 1) TvButton(l("Ещё", "More"), { page = (page + 1) % pages.size },
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
                        scenarioOrder.forEach { preset ->
                            TvScenarioDisc(label = if (ru) preset.ru else preset.en,
                                icon = PrismIcons.waveScenario(preset.glyph), selected = active?.id == preset.id,
                                onClick = { start(preset.style.copy(russianPercent = style.russianPercent)) },
                                modifier = Modifier.tvItem("tv_wave/quick/${preset.id}"))
                        }
                    }
                }
            }
        }
    }
}
