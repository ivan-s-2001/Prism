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
import androidx.compose.ui.unit.Dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.StreamLayout
import com.prism.music.data.prefs.StreamVisualStyle
import com.prism.music.ui.theme.LocalUi

/** Content-driven page: header, station and shelf remain adjacent. Only short windows scroll. */
@Composable
internal fun VibePageLayout(
    settings: AppSettings, modifier: Modifier = Modifier, scrollable: Boolean = true,
    header: @Composable () -> Unit, player: @Composable (Modifier) -> Unit, scenarios: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val page = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
        val scroll = rememberScrollState()
        Column(page.then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier),
            verticalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
            header()
            player(Modifier.fillMaxWidth().padding(horizontal = ui.gap(12.dp)))
            scenarios()
        }
    }
}

/** One visual slot: the station layout owns its geometry, not the full page or track credits. */
@Composable
internal fun VibeFlowField(
    modifier: Modifier = Modifier, animated: Boolean = false, energy: Float = 0.5f,
    preview: Boolean = false, content: @Composable (visual: @Composable (Modifier) -> Unit) -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        content { visualModifier -> VibeSphere(visualModifier, animated, energy, preview) }
    }
}

/** The visual field's size never depends on track title, artwork or whether playback has started. */
internal fun vibeHeroSize(width: Dp, height: Dp, layout: StreamLayout, sideBySide: Boolean, fraction: Float): Dp {
    val widthBudget = if (sideBySide) width * 0.43f else width
    val preferred = when {
        sideBySide -> widthBudget.coerceAtMost(420.dp)
        layout == StreamLayout.COMPACT -> (widthBudget * 0.40f).coerceIn(104.dp, 154.dp)
        layout == StreamLayout.SEPARATE -> (widthBudget * 0.64f).coerceIn(144.dp, 250.dp)
        else -> (widthBudget * 0.82f).coerceIn(188.dp, 340.dp)
    }
    val heightBudget = if (height == Dp.Infinity) preferred else
        (height * if (sideBySide) 0.95f else 0.50f).coerceAtLeast(0.dp)
    return minOf(preferred * fraction.coerceIn(0.6f, 1f), widthBudget, heightBudget).coerceAtLeast(0.dp)
}

@Composable
internal fun VibeStationOverlay(
    settings: AppSettings, title: String, hasTrack: Boolean,
    modifier: Modifier = Modifier, play: @Composable () -> Unit,
    credits: @Composable () -> Unit,
    previous: @Composable () -> Unit, next: @Composable () -> Unit,
    dislike: @Composable () -> Unit, like: @Composable () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    progress: @Composable () -> Unit = {},
    visual: @Composable (Modifier) -> Unit = {},
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val layout = settings.streamLayout
        val compact = layout == StreamLayout.COMPACT
        val showVisual = settings.streamVisualStyle != StreamVisualStyle.OFF
        val split = layout == StreamLayout.SEPARATE && showVisual
        val sideBySide = split && maxWidth >= 620.dp
        val hero = vibeHeroSize(maxWidth, maxHeight, layout, sideBySide, settings.streamVisualSize.fraction)
        val sideWidth = if (maxWidth < 300.dp) 44.dp else 52.dp
        val playGap = ui.gap(if (compact || maxWidth < 300.dp) 8.dp else 18.dp)
        @Composable fun StationTitle() {
            Text(title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        @Composable fun Transport() {
            Row(horizontalArrangement = Arrangement.spacedBy(playGap), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(sideWidth), contentAlignment = Alignment.Center) {
                    if (hasTrack && settings.streamShowReactions) dislike()
                }
                play()
                Box(Modifier.width(sideWidth), contentAlignment = Alignment.Center) {
                    if (hasTrack && settings.streamShowReactions) like()
                }
            }
        }
        @Composable fun TrackDetails() {
            if (hasTrack) {
                if (settings.streamReverseLayout && settings.streamShowProgress) progress()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(sideWidth), contentAlignment = Alignment.Center) { previous() }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { credits() }
                    Box(Modifier.width(sideWidth), contentAlignment = Alignment.Center) { next() }
                }
                if (!settings.streamReverseLayout && settings.streamShowProgress) progress()
                if (extra != null) extra()
            }
        }
        @Composable fun Controls() {
            Column(Modifier.widthIn(max = 416.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ui.gap(if (compact) 4.dp else 10.dp))) {
                StationTitle()
                if (showVisual && !split) {
                    // Align the membrane with PLAY, not with the entire group of track controls.
                    Box(Modifier.fillMaxWidth().heightIn(min = hero), contentAlignment = Alignment.Center) {
                        visual(Modifier.size(hero))
                        Transport()
                    }
                } else {
                    Transport()
                }
                TrackDetails()
            }
        }
        if (sideBySide) {
            Row(Modifier.widthIn(max = 1000.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ui.gap(20.dp))) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    visual(Modifier.size(hero))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Controls() }
            }
        } else {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(vertical = ui.gap(4.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ui.gap(if (compact) 2.dp else 6.dp))) {
                if (split) visual(Modifier.size(hero))
                Controls()
            }
        }
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
        modifier = modifier.size(ui.gap(76.dp).coerceIn(64.dp, 84.dp))
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
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp).semantics { this.selected = selected }) {
        Icon(icon, label, Modifier.size(22.dp), tint = when {
            !enabled -> scheme.onSurface.copy(alpha = 0.38f)
            selected -> scheme.primary
            else -> scheme.onSurfaceVariant
        })
    }
}

/** Center the credits even when a small artwork thumbnail is enabled. */
@Composable
internal fun VibeCredits(
    settings: AppSettings, title: String, artist: String, artwork: @Composable (Modifier) -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val inline = settings.streamLayout != StreamLayout.CENTERED
    val coverSize = (if (inline) 54.dp else 40.dp) * settings.streamCoverSize.fraction
    @Composable fun Labels(modifier: Modifier = Modifier) {
        Column(modifier, horizontalAlignment = if (inline) Alignment.Start else Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (inline) TextAlign.Start else TextAlign.Center)
            Text(artist, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = if (inline) TextAlign.Start else TextAlign.Center)
        }
    }
    @Composable fun Body() {
        val body = Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = ui.gap(4.dp), horizontal = ui.gap(6.dp))
        if (inline && settings.streamShowCover) {
            Row(body, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(10.dp))) {
                artwork(Modifier.size(coverSize))
                Labels(Modifier.weight(1f))
            }
        } else {
            Column(body, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ui.gap(4.dp))) {
                if (settings.streamShowCover) artwork(Modifier.size(coverSize))
                Labels()
            }
        }
    }
    if (settings.streamGlassPanel) GlassSurface(Modifier.fillMaxWidth(), shape = ui.shape(16.dp), onMedia = false, inPage = true) { Body() }
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
