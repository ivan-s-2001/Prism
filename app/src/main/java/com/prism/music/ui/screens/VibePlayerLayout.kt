package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import com.prism.music.ui.theme.GlassSurface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.PrismScenarioDisc
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.theme.LocalUi

/** Top-level Prism page geometry: native header, one flexible player and a single full-width shelf.
 * On short windows the page scrolls instead of shrinking the artwork and controls to zero.
 */
@Composable
internal fun VibePageLayout(
    settings: AppSettings, modifier: Modifier = Modifier, scrollable: Boolean = true,
    header: @Composable () -> Unit, player: @Composable (Modifier) -> Unit, scenarios: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 520.dp
        val minimum = (if (wide) 360.dp else 570.dp) *
            settings.textScale.coerceAtLeast(1f)
        val height = if (scrollable) maxOf(maxHeight, minimum) else maxHeight
        val scroll = rememberScrollState()
        val page = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
        Column(page.then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier).height(height),
            verticalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
            header()
            player(Modifier.weight(1f).fillMaxWidth().padding(horizontal = ui.gap(16.dp)))
            scenarios()
        }
    }
}

/** Station and current track are different levels of the same page. The visual field never becomes album art. */
@Composable
internal fun VibeStationPanel(
    settings: AppSettings, modifier: Modifier = Modifier, hasTrack: Boolean,
    station: @Composable (Modifier) -> Unit, track: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 520.dp
        val spacing = ui.gap(16.dp)
        @Composable fun TrackSlot(modifier: Modifier) {
            Box(modifier, contentAlignment = Alignment.Center) { track() }
        }
        if (wide && hasTrack) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically) {
                if (settings.streamReverseLayout) TrackSlot(Modifier.weight(0.9f))
                station(Modifier.weight(1.1f).fillMaxHeight())
                if (!settings.streamReverseLayout) TrackSlot(Modifier.weight(0.9f))
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                if (hasTrack && settings.streamReverseLayout) TrackSlot(Modifier.fillMaxWidth())
                station(Modifier.weight(1f).fillMaxWidth())
                if (hasTrack && !settings.streamReverseLayout) TrackSlot(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Shared station field: actual audio-reactive shader on supported devices, static in previews. */
@Composable
internal fun VibeFlowField(
    modifier: Modifier = Modifier, animated: Boolean = false, energy: Float = 0.5f,
    palette: List<Color>? = null, content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Bound the fragment shader to a square rather than rendering the entire TV/window.
        VibeSphere(Modifier.size(minOf(maxWidth, maxHeight, 460.dp)), animated, energy, palette)
        content()
    }
}

@Composable
internal fun VibeStationCenter(
    icon: ImageVector, title: String, playing: Boolean, starting: Boolean, active: Boolean,
    onPlay: () -> Unit, modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    Column(modifier.widthIn(max = 220.dp).padding(ui.gap(12.dp)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ui.gap(10.dp))) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        PrismButton(onClick = onPlay, enabled = !starting, modifier = Modifier.testTag("home:my_wave:play")) {
            if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(if (playing) PrismIcons.Pause else PrismIcons.Play, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr(when {
                starting -> R.string.prism_stream_finding_music
                playing -> R.string.widget_pause
                active -> R.string.widget_play
                else -> R.string.prism_stream_start_listening
            }), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact attribution, with the existing cover size/visibility, glass and progress options retained. */
@Composable
internal fun VibeTrackStrip(
    settings: AppSettings, title: String, artist: String, artwork: @Composable (Modifier) -> Unit,
    onOpen: () -> Unit, actions: @Composable RowScope.() -> Unit, progress: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    @Composable fun Body() {
        Column(Modifier.fillMaxWidth().padding(ui.gap(10.dp)), verticalArrangement = Arrangement.spacedBy(ui.gap(6.dp))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(10.dp))) {
                Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ui.gap(10.dp))) {
                    if (settings.streamShowCover) artwork(Modifier.size(68.dp * settings.streamCoverSize.fraction))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(PrismIcons.ChevronRight, null, Modifier.size(20.dp).clickable(onClick = onOpen), tint = scheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
            if (settings.streamShowProgress) progress()
        }
    }
    if (settings.streamGlassPanel) GlassSurface(Modifier.fillMaxWidth(), shape = ui.shape(20.dp), onMedia = false, inPage = true) { Body() }
    else Body()
}

/** One native visual choice for both the actual shortcuts and their read-only preview. */
@Composable
internal fun VibeScenarioTile(
    compact: Boolean, label: String, icon: ImageVector, selected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    if (compact) PrismChip(selected, onClick, label, modifier, icon, fitLabel = true)
    else PrismScenarioDisc(label, icon, selected, onClick, modifier)
}
