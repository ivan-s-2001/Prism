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
        val wide = maxWidth >= 520.dp
        val fieldMin = if (settings.streamLayout == StreamLayout.COMPACT || settings.streamVisualStyle == StreamVisualStyle.OFF) 180.dp
            else if (wide) 252.dp else (maxWidth * 0.83f).coerceIn(260.dp, 344.dp)
        val page = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
        val scroll = rememberScrollState()
        Column(page.then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier),
            verticalArrangement = Arrangement.spacedBy(ui.gap(6.dp))) {
            header()
            player(Modifier.fillMaxWidth().heightIn(min = fieldMin).padding(horizontal = ui.gap(8.dp)))
            scenarios()
        }
    }
}

/** One immersive field, including all station controls; no duplicate player strip. */
@Composable
internal fun VibeFlowField(
    modifier: Modifier = Modifier, animated: Boolean = false, energy: Float = 0.5f,
    preview: Boolean = false, content: @Composable BoxScope.() -> Unit,
) {
    val s = LocalAppSettings.current
    val ui = LocalUi.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val visible = s.streamVisualStyle != StreamVisualStyle.OFF
        val separate = s.streamLayout == StreamLayout.SEPARATE && visible
        val visualSize = s.streamVisualSize.fraction
        if (separate && maxWidth >= 620.dp) {
            // A real two-column composition on TV and wide windows, with the same focusable controls.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ui.gap(12.dp))) {
                BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    VibeSphere(Modifier.size(minOf(maxWidth, maxHeight, 420.dp) * visualSize), animated, energy, preview)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center, content = content)
            }
        } else if (separate) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                VibeSphere(Modifier.size(minOf(maxWidth * 0.68f, 228.dp) * visualSize), animated, energy, preview)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center, content = content)
            }
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (visible) BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    val cap = if (s.streamLayout == StreamLayout.COMPACT) 210.dp else 520.dp
                    VibeSphere(Modifier.size(minOf(maxWidth, maxHeight, cap) * visualSize), animated, energy, preview)
                }
                content()
            }
        }
    }
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
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier.widthIn(max = 416.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val compact = settings.streamLayout == StreamLayout.COMPACT
        Column(Modifier.fillMaxWidth().padding(horizontal = ui.gap(6.dp), vertical = ui.gap(if (compact) 8.dp else 22.dp)),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(ui.gap(if (compact) 6.dp else 14.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(ui.gap(20.dp)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                    if (hasTrack && settings.streamShowReactions) dislike()
                }
                play()
                Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                    if (hasTrack && settings.streamShowReactions) like()
                }
            }
            if (hasTrack) {
                Spacer(Modifier.height(ui.gap(if (compact) 4.dp else 12.dp)))
                @Composable fun TrackRow() {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { previous() }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { credits() }
                        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { next() }
                    }
                }
                if (settings.streamReverseLayout && settings.streamShowProgress) progress()
                TrackRow()
                if (!settings.streamReverseLayout && settings.streamShowProgress) progress()
                if (extra != null) { Spacer(Modifier.height(ui.gap(2.dp))); extra() }
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
    val coverSize = 32.dp * settings.streamCoverSize.fraction
    @Composable fun Body() {
        Row(Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = ui.gap(3.dp), horizontal = ui.gap(4.dp)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ui.gap(6.dp))) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (settings.streamShowCover) {
                    artwork(Modifier.size(coverSize))
                    Spacer(Modifier.height(ui.gap(4.dp)))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(artist, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
