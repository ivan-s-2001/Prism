package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
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

/** The station owns the viewport between header and scenarios. Only genuinely short windows scroll. */
@Composable
internal fun VibePageLayout(
    settings: AppSettings, modifier: Modifier = Modifier, scrollable: Boolean = true,
    header: @Composable () -> Unit, player: @Composable (Modifier) -> Unit, scenarios: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val page = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
        val scroll = rememberScrollState()
        if (scrollable && !vibeFillsViewport(maxWidth, maxHeight)) {
            Column(page.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
                header()
                player(Modifier.fillMaxWidth())
                scenarios()
            }
        } else {
            // Measure header and shelf first, then give their remaining height to the stage.
            // A weighted child inside verticalScroll would be measured at infinite height and wrap content.
            Column(page.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(ui.gap(4.dp))) {
                header()
                player(Modifier.weight(1f, fill = true).fillMaxWidth())
                scenarios()
            }
        }
    }
}

internal fun vibeFillsViewport(width: Dp, height: Dp): Boolean =
    height != Dp.Infinity && height >= if (width >= 620.dp) 400.dp else 540.dp

/** One visual slot: pass the allocated viewport to the station instead of losing its minimum constraints. */
@Composable
internal fun VibeFlowField(
    modifier: Modifier = Modifier, animated: Boolean = false, energy: Float = 0.5f,
    preview: Boolean = false, content: @Composable (visual: @Composable (Modifier) -> Unit) -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center, propagateMinConstraints = true) {
        content { visualModifier -> VibeSphere(visualModifier, animated, energy, preview) }
    }
}

/** Only the foreground sets the stage size. Visual scale never changes layout or focus positions. */
internal fun vibeStageMinHeight(width: Dp, height: Dp, layout: StreamLayout, visible: Boolean): Dp {
    val preferred = when {
        !visible -> 0.dp
        layout == StreamLayout.COMPACT -> (width * 0.48f).coerceIn(160.dp, 208.dp)
        width >= 620.dp -> 260.dp
        else -> (width * 0.84f).coerceIn(244.dp, 340.dp)
    }
    return if (height == Dp.Infinity) preferred else minOf(preferred, height).coerceAtLeast(0.dp)
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
        val shortWide = maxWidth >= 620.dp && maxHeight < 300.dp
        val compact = layout == StreamLayout.COMPACT || shortWide
        val showVisual = settings.streamVisualStyle != StreamVisualStyle.OFF
        val wide = maxWidth >= 620.dp
        val stage = maxOf(minHeight, vibeStageMinHeight(maxWidth, maxHeight, layout, showVisual))
        val roomy = stage >= 420.dp && !compact
        val sideWidth = if (maxWidth < 300.dp) 48.dp else 52.dp
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
                verticalArrangement = Arrangement.spacedBy(ui.gap(if (compact) 6.dp else if (roomy) 24.dp else 16.dp))) {
                StationTitle()
                Transport()
                TrackDetails()
            }
        }

        // The wave is an ambient layer behind the entire station, never a separate layout item.
        Box(Modifier.fillMaxWidth().heightIn(min = stage).clipToBounds(), contentAlignment = Alignment.Center) {
            if (showVisual) {
                BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    val fraction = settings.streamVisualSize.fraction
                    visual(Modifier.fillMaxSize(fraction))
                    // The renderer already fades to transparent, preserving photos and custom Prism backdrops.
                }
            }
            if (wide && (layout == StreamLayout.SEPARATE || shortWide) && hasTrack) {
                Row(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = ui.gap(16.dp), vertical = ui.gap(if (compact) 6.dp else 18.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ui.gap(24.dp))) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ui.gap(if (compact) 6.dp else 12.dp))) {
                        StationTitle()
                        Transport()
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ui.gap(6.dp))) { TrackDetails() }
                }
            } else {
                Box(Modifier.fillMaxWidth().padding(vertical = ui.gap(if (compact) 6.dp else 18.dp)),
                    contentAlignment = Alignment.Center) { Controls() }
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
        val body = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = ui.gap(4.dp), horizontal = ui.gap(6.dp))
        if (inline && settings.streamShowCover) {
            Row(body, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(10.dp))) {
                artwork(Modifier.size(coverSize))
                Labels(Modifier.weight(1f))
            }
        } else {
            Column(body, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ui.gap(4.dp), Alignment.CenterVertically)) {
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
