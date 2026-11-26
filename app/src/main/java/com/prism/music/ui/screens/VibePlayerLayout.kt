package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismTokens
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
        val minimum = (if (wide) 360.dp else 530.dp) *
            settings.textScale.coerceAtLeast(1f)
        val height = if (scrollable) maxOf(maxHeight, minimum) else maxHeight
        val scroll = rememberScrollState()
        val page = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
        Column(page.then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier).height(height),
            verticalArrangement = Arrangement.spacedBy(ui.gap(4.dp))) {
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
        val spacing = ui.gap(12.dp)
        @Composable fun TrackSlot(modifier: Modifier) {
            Box(modifier, contentAlignment = Alignment.Center) { track() }
        }
        if (wide && hasTrack) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically) {
                if (settings.streamReverseLayout) TrackSlot(Modifier.weight(0.8f))
                station(Modifier.weight(1.2f).fillMaxHeight())
                if (!settings.streamReverseLayout) TrackSlot(Modifier.weight(0.8f))
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

/** The fluid field is the hero. Its only overlay is the primary station action; the title sits on quiet page ink. */
@Composable
internal fun VibeFlowField(
    modifier: Modifier = Modifier, animated: Boolean = false, energy: Float = 0.5f,
    palette: List<Color>? = null, title: String, content: @Composable BoxScope.() -> Unit,
) {
    val ui = LocalUi.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ui.gap(6.dp))) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Keep the visible shader within its square, including on large TV screens.
            VibeSphere(Modifier.size(minOf(maxWidth, maxHeight, 460.dp)), animated, energy, palette)
            content()
        }
        Text(title, Modifier.fillMaxWidth().padding(horizontal = ui.gap(12.dp)),
            style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Single station action with the app's own controls, shape and play-ring preference. */
@Composable
internal fun VibeStationAction(
    playing: Boolean, starting: Boolean, active: Boolean,
    onPlay: () -> Unit, modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    val settings = LocalAppSettings.current
    val label = tr(when {
        starting -> R.string.prism_stream_finding_music
        playing -> R.string.widget_pause
        active -> R.string.widget_play
        else -> R.string.prism_stream_start_listening
    })
    val shape = ui.button
    PrismButton(onClick = onPlay, enabled = !starting, shape = shape,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(ui.gap(72.dp).coerceIn(64.dp, 84.dp))
            .then(if (settings.view.playRing && !ui.classicControls) Modifier.border(1.5.dp, PrismTokens.spectrumRing(), shape) else Modifier)
            .testTag("home:my_wave:play")) {
        if (starting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Icon(if (playing) PrismIcons.Pause else PrismIcons.Play, label, Modifier.size(34.dp))
    }
}

/** Bare controls keep the station's track strip visually subordinate to its hero. */
@Composable
internal fun VibeTrackAction(
    icon: ImageVector, label: String, onClick: () -> Unit, selected: Boolean = false,
    enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(44.dp).semantics { this.selected = selected }) {
        Icon(icon, label, Modifier.size(22.dp), tint = when {
            !enabled -> scheme.onSurface.copy(alpha = 0.38f)
            selected -> scheme.primary
            else -> scheme.onSurfaceVariant
        })
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inline = maxWidth >= 300.dp && settings.textScale <= 1.15f
            val pad = if (settings.streamGlassPanel) ui.gap(10.dp) else ui.gap(4.dp)
            @Composable fun Credits(modifier: Modifier) {
                Row(modifier.clickable(onClick = onOpen).padding(vertical = ui.gap(4.dp)),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(10.dp))) {
                    if (settings.streamShowCover) artwork(Modifier.size(60.dp * settings.streamCoverSize.fraction))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(pad), verticalArrangement = Arrangement.spacedBy(ui.gap(4.dp))) {
                if (inline) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Credits(Modifier.weight(1f))
                    actions()
                } else {
                    Credits(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
                }
                if (settings.streamShowProgress) progress()
            }
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
