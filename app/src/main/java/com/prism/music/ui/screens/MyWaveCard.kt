package com.prism.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.ExplicitFilter
import com.prism.music.data.foryou.MyWave
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

@Composable
internal fun MyWaveCard(recommendations: List<Song>, favorites: List<Song>, recent: List<Song>, online: Boolean) {
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

    fun start() {
        if (starting) return
        starting = true
        val previous = c.queue.source.value
        val seed = System.nanoTime()
        scope.launch {
            try {
                val songs = withContext(Dispatchers.Default) {
                    val base = if (online) recommendations else c.yourMusic.offlinePicks(60)
                    val known = c.library.likedIds.value
                    MyWave.start(base, favorites, recent, c.sessionHeard::has,
                        allowed = { s ->
                            !s.isEpisode && !s.isLive && !s.isVideo &&
                                (online || c.availableOffline(s.id)) &&
                                !c.library.isDisliked(s.id) && !c.exclusions.blocks(s) &&
                                !c.exclusions.isSongExcluded(s.id) &&
                                !c.recFeedback.turnedDown(s.id, known = s.id in known) &&
                                ExplicitFilter.plays(c.settings.current.behavior.explicitContent, s)
                        }, seed = seed)
                }
                // If the listener chose another queue while this was being prepared, it wins.
                if (c.queue.source.value !== previous) return@launch
                if (songs.isEmpty()) c.player.reportError(l("Нет доступных треков для волны. Попробуйте после загрузки рекомендаций.", "No tracks available for Wave. Try after recommendations load."))
                else {
                    c.recFeedback.served(songs.map { it.id })
                    c.player.playQueue(songs, 0, QueueSource(QueueKind.RADIO, title,
                        seedId = songs.first().id, originId = MyWave.ORIGIN))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { c.player.reportError(l("Не удалось запустить волну", "Could not start Wave")) }
            finally { starting = false }
        }
    }

    val pending = starting || (wave && buffering)
    val subtitle = when {
        starting -> l("Подбираем музыку", "Finding your music")
        wave -> song?.let { "${it.title} · ${it.primaryArtist}" }.orEmpty()
        !online -> l("Твоя музыка без интернета", "Your music offline")
        else -> l("Музыка под тебя", "Music made for you")
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .height(226.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF22254F), Color(0xFF322A64), Color(0xFF102B42))))
            .testTag("home:my_wave")
    ) {
        // Vector ribbons, no album-art fetches or frame-by-frame rendering on Home.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            for (i in 0..10) {
                val path = Path()
                for (x in 0..100) {
                    val t = x / 100f
                    val y = h * (0.47f + i * 0.027f) + sin((t * 2.5 * PI) + i * 0.18).toFloat() * h * (0.13f + i * 0.006f)
                    if (x == 0) path.moveTo(0f, y) else path.lineTo(t * w, y)
                }
                drawPath(path, Brush.horizontalGradient(listOf(Color(0xFF7468EB).copy(alpha = 0.12f),
                    Color(0xFFDAB4FF).copy(alpha = 0.30f), Color(0xFF63D9EF).copy(alpha = 0.13f))), style = Stroke(1.4.dp.toPx()))
            }
        }
        Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White)
                .clickable(enabled = !starting) { if (wave) c.player.togglePlay() else start() }
                .testTag("home:my_wave:play"), contentAlignment = Alignment.Center) {
                if (pending) com.prism.music.ui.components.PrismSpinner(Modifier.size(25.dp), stroke = 2.dp)
                else Icon(if (wave && playing) PrismIcons.Pause else PrismIcons.Play,
                    if (wave && playing) l("Пауза", "Pause") else l("Слушать", "Play"),
                    tint = Color(0xFF22254F), modifier = Modifier.size(30.dp))
            }
        }
        if (wave) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(42.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)).clickable(enabled = !starting) { start() }.testTag("home:my_wave:refresh"),
            contentAlignment = Alignment.Center) {
            Icon(PrismIcons.Refresh, l("Новая волна", "New Wave"), tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Text(if (wave && playing) l("ВОЛНА ИГРАЕТ", "WAVE PLAYING") else l("ПЕРСОНАЛЬНЫЙ ПОТОК", "PERSONAL RADIO"),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp),
            color = Color.White.copy(alpha = 0.56f), style = MaterialTheme.typography.labelSmall)
    }
}
