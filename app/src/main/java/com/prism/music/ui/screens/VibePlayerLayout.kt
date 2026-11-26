package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import kotlin.math.abs
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
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
import com.prism.music.ui.components.PrismFilledTonalButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.StreamLayout
import com.prism.music.data.prefs.StreamVisualStyle
import com.prism.music.ui.theme.LocalUi

/** Shared coordinates for the background texture and the control group. Coordinates are in root pixels.
 * The anchor excludes the translated child, so motion cannot feed back into the next layout. */
internal class VibeSceneState {
    var anchor by mutableStateOf<Offset?>(null)
}
internal val LocalVibeScene = staticCompositionLocalOf<VibeSceneState?> { null }

@Composable
internal fun VibeScene(content: @Composable () -> Unit) {
    val scene = remember { VibeSceneState() }
    CompositionLocalProvider(LocalVibeScene provides scene, content = content)
}

@Composable
internal fun VibeSceneAnchor(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scene = LocalVibeScene.current
    Box(modifier.onGloballyPositioned { coordinates ->
        scene?.anchor = coordinates.positionInRoot() + Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
    }, contentAlignment = Alignment.Center) {
        content()
    }
}

/** Full-bleed scene; only foreground content reserves space for the global mini player and navigation. */
@Composable
internal fun VibePageLayout(
    settings: AppSettings, modifier: Modifier = Modifier, scrollable: Boolean = true,
    header: @Composable () -> Unit, player: @Composable (Modifier) -> Unit, scenarios: @Composable () -> Unit,
    backdrop: @Composable (Modifier) -> Unit = {},
    bottomPadding: Dp = 0.dp, footer: @Composable () -> Unit = {},
) {
    val ui = LocalUi.current
    VibeScene {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            backdrop(Modifier.matchParentSize())
            BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.TopCenter) {
                val page = Modifier.widthIn(max = 1040.dp).fillMaxWidth()
                val scroll = rememberScrollState()
                if (scrollable && !vibeFillsViewport(maxWidth, maxHeight)) {
                    Column(page.fillMaxHeight()) {
                        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(ui.gap(8.dp))) {
                            header()
                            player(Modifier.fillMaxWidth())
                            scenarios()
                        }
                        footer()
                    }
                } else {
                    Column(page.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(ui.gap(4.dp))) {
                        header()
                        player(Modifier.weight(1f, fill = true).fillMaxWidth())
                        scenarios()
                        footer()
                    }
                }
            }
        }
    }
}

// The center tile is lifted and enlarged; distance changes continuously during scrolling.
internal fun vibeOrbitDepth(distanceInSlots: Float): Float =
    if (distanceInSlots.isFinite()) (abs(distanceInSlots) / 2f).coerceIn(0f, 1f) else 0f

internal fun vibeFillsViewport(width: Dp, height: Dp): Boolean =
    height != Dp.Infinity && height >= if (width >= 620.dp) 400.dp else 560.dp

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

/** Mobile Vibe is a discovery surface. The global mini player owns all track transport. */
@Composable
internal fun VibeDiscoveryStage(settings: AppSettings, title: String, active: Boolean, playing: Boolean, starting: Boolean,
    onStart: () -> Unit, onTune: () -> Unit, modifier: Modifier = Modifier,
    visual: @Composable (Modifier) -> Unit = {}) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val height = maxOf(minHeight, vibeStageMinHeight(maxWidth, maxHeight, settings.streamLayout,
            settings.streamVisualStyle != StreamVisualStyle.OFF), minOf(maxHeight, 180.dp))
        val compact = settings.streamLayout == StreamLayout.COMPACT || height < 300.dp
        Box(Modifier.fillMaxWidth().heightIn(min = height), contentAlignment = Alignment.Center) {
            if (settings.streamVisualStyle != StreamVisualStyle.OFF) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    visual(Modifier.fillMaxSize())
                }
            }
            val groupAlignment = if (settings.streamLayout == StreamLayout.SEPARATE && height >= 320.dp) Alignment.BottomCenter else Alignment.Center
            Box(Modifier.align(groupAlignment).widthIn(max = 460.dp).fillMaxWidth()
                .padding(horizontal = ui.gap(24.dp), vertical = ui.gap(if (groupAlignment == Alignment.BottomCenter) 32.dp else 16.dp))) {
                VibeSceneAnchor(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ui.gap(if (compact) 10.dp else 20.dp))) {
                        VibeIdentity(title, compact, height >= 320.dp)
                        VibeStationAction(playing, starting, active, onStart, compact = compact)
                        VibeTunePill(onTune)
                    }
                }
            }
        }
    }
}

/** One quiet tuning action, directly below the station's play/pause button. */
@Composable
internal fun VibeTunePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    PrismFilledTonalButton(onClick = onClick, modifier = modifier.testTag("home:my_wave:settings"),
        shape = CircleShape, contentPadding = PaddingValues(horizontal = ui.gap(16.dp), vertical = 8.dp)) {
        Icon(PrismIcons.Customize, null, Modifier.size(18.dp))
        Spacer(Modifier.width(ui.gap(8.dp)))
        Text(tr(R.string.prism_stream_tune), style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
    dislike: @Composable () -> Unit = {}, like: @Composable () -> Unit = {},
    extra: (@Composable () -> Unit)? = null,
    progress: @Composable () -> Unit = {},
    visual: @Composable (Modifier) -> Unit = {},
    artist: String? = null,
    tune: @Composable () -> Unit = {},
    origin: com.prism.music.playback.VibeOrigin? = null,
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val layout = settings.streamLayout
        val shortWide = maxWidth >= 620.dp && maxHeight < 300.dp
        val compact = layout == StreamLayout.COMPACT || shortWide
        val showVisual = settings.streamVisualStyle != StreamVisualStyle.OFF
        val wide = maxWidth >= 620.dp
        val stage = maxOf(minHeight, vibeStageMinHeight(maxWidth, maxHeight, layout, showVisual))
        val roomy = stage >= 360.dp && !compact
        val sideWidth = if (maxWidth < 300.dp) 48.dp else 52.dp
        @Composable fun StationTitle() {
            Box(Modifier.fillMaxWidth().heightIn(min = if (compact) 38.dp else if (roomy) 52.dp else 44.dp),
                contentAlignment = Alignment.Center) {
                if (origin == null) VibeIdentity(title, compact, roomy)
                else VibeOriginIdentity(origin, compact, roomy)
            }
        }
        @Composable fun Transport() {
            // The wave has one primary action; reactions belong to the global player.
            Box(contentAlignment = Alignment.Center) { play() }
        }
        @Composable fun TrackDetails() {
            if (hasTrack) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ui.gap(4.dp))) {
                    if (settings.streamReverseLayout && settings.streamShowProgress) progress()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ui.gap(4.dp))) {
                        Box(Modifier.width(sideWidth), contentAlignment = Alignment.Center) { previous() }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { credits() }
                        Box(Modifier.width(sideWidth), contentAlignment = Alignment.Center) { next() }
                    }
                    if (!settings.streamReverseLayout && settings.streamShowProgress) progress()
                    if (extra != null) extra()
                }
            }
        }
        @Composable fun Controls() {
            // A single stationary composition, rather than weighted gaps splitting the hero.
            VibeSceneAnchor(Modifier.widthIn(max = 460.dp).fillMaxWidth()
                .padding(horizontal = ui.gap(12.dp), vertical = ui.gap(8.dp))) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ui.gap(if (compact) 6.dp else 12.dp))) {
                    StationTitle()
                    Transport()
                    tune()
                    if (hasTrack) TrackDetails()
                }
            }
        }

        // The wave is an ambient layer behind the entire station, never a separate layout item.
        Box(Modifier.fillMaxWidth().heightIn(min = stage), contentAlignment = Alignment.Center) {
            if (showVisual) {
                BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    visual(Modifier.fillMaxSize())
                    // The renderer already fades to transparent, preserving photos and custom Prism backdrops.
                }
            }
            if (wide && (layout == StreamLayout.SEPARATE || shortWide) && hasTrack) {
                Row(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = ui.gap(16.dp), vertical = ui.gap(if (compact) 6.dp else 18.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ui.gap(24.dp))) {
                    VibeSceneAnchor(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(ui.gap(if (compact) 6.dp else 12.dp))) {
                            StationTitle()
                            Transport()
                            tune()
                        }
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ui.gap(if (compact) 6.dp else 14.dp))) {
                        TrackDetails()
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().padding(vertical = ui.gap(if (compact) 6.dp else 18.dp)),
                    contentAlignment = Alignment.Center) { Controls() }
            }
        }
    }
}

/** Source artwork is distinct from the changing now-playing cover. */
@Composable
private fun VibeOriginIdentity(origin: com.prism.music.playback.VibeOrigin, compact: Boolean, roomy: Boolean) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val artist = origin.kind == com.prism.music.playback.VibeOriginKind.ARTIST
    val type = tr(when (origin.kind) {
        com.prism.music.playback.VibeOriginKind.TRACK -> R.string.song
        com.prism.music.playback.VibeOriginKind.ALBUM -> R.string.album
        com.prism.music.playback.VibeOriginKind.ARTIST -> R.string.artist
        com.prism.music.playback.VibeOriginKind.PLAYLIST -> R.string.playlist
        else -> R.string.stream
    })
    val shape = if (artist) CircleShape else ui.albumArt
    val artSize = if (compact) 48.dp else if (roomy) 80.dp else 64.dp
    Row(Modifier.widthIn(max = 400.dp).padding(horizontal = ui.gap(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(ui.gap(14.dp), Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(artSize).clip(shape).background(scheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, scheme.onSurface.copy(alpha = 0.12f), shape), contentAlignment = Alignment.Center) {
            if (!origin.artwork.isNullOrBlank()) {
                com.prism.music.ui.components.Artwork(origin.artwork, Modifier.fillMaxSize(), shape, size = 320)
            } else {
                Icon(if (artist) PrismIcons.Person else PrismIcons.Stream, null, Modifier.size(artSize * 0.42f),
                    tint = scheme.primary)
            }
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(tr(R.string.stream) + " · " + type, style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(origin.name, style = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                .copy(fontWeight = FontWeight.SemiBold), color = scheme.onSurface,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A station's identity stays stable across tracks, unlike the changing credits. */
@Composable
private fun VibeIdentity(source: String, compact: Boolean, roomy: Boolean) {
    Text(source, modifier = Modifier.fillMaxWidth(),
        style = (when {
            compact -> MaterialTheme.typography.titleLarge
            roomy -> MaterialTheme.typography.headlineLarge
            else -> MaterialTheme.typography.headlineSmall
        }).copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Single station action with the app's own controls, shape and play-ring preference. */
@Composable
internal fun VibeStationAction(
    playing: Boolean, starting: Boolean, active: Boolean,
    onPlay: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false,
) {
    val ui = LocalUi.current
    val settings = LocalAppSettings.current
    val label = tr(when {
        starting -> R.string.prism_stream_finding_music
        playing -> R.string.widget_pause
        active -> R.string.widget_play
        else -> R.string.prism_stream_start_listening
    })
    val shape = CircleShape
    val diameter = (ui.gap(if (compact) 68.dp else 84.dp) * settings.streamActionSize.fraction).coerceIn(48.dp, 88.dp)
    val target = modifier.size(diameter)
        .then(if (settings.view.playRing && !ui.classicControls) Modifier.border(1.5.dp, PrismTokens.spectrumRing(), shape) else Modifier)
        .testTag("home:my_wave:play")
    @Composable fun Glyph() {
        if (starting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Icon(if (playing) PrismIcons.Pause else PrismIcons.Play, label, Modifier.size(diameter * 0.43f))
    }
    when (settings.streamActionStyle) {
        TransportStyle.BUTTON -> PrismButton(onPlay, target, enabled = !starting, shape = shape, contentPadding = PaddingValues(0.dp)) { Glyph() }
        TransportStyle.OUTLINE -> PrismOutlinedButton(onPlay, target, enabled = !starting, shape = shape, contentPadding = PaddingValues(0.dp)) { Glyph() }
        TransportStyle.GLYPHS -> IconButton(onClick = onPlay, modifier = target, enabled = !starting) { Glyph() }
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
    showArtist: Boolean = true,
) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val inline = settings.streamLayout != StreamLayout.CENTERED && settings.streamShowCover
    val coverSize = 48.dp * settings.streamCoverSize.fraction
    @Composable fun Labels(modifier: Modifier = Modifier) {
        Column(modifier, horizontalAlignment = if (inline) Alignment.Start else Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (inline) TextAlign.Start else TextAlign.Center)
            if (showArtist) Text(artist.ifBlank { " " }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1,
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

/** A light scenario orbit: icon and caption are one target, without a second card around them. */
@Composable
private fun VibeScenarioCard(label: String, icon: ImageVector, selected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val reduce = LocalAppSettings.current.reduceMotion
    val fill by animateColorAsState(
        if (selected) scheme.primary.copy(alpha = 0.22f) else scheme.onSurface.copy(alpha = 0.07f),
        animationSpec = if (reduce) snap() else tween(220), label = "vibeScenarioFill")
    val edge by animateColorAsState(
        if (selected) scheme.primary else Color.Transparent,
        animationSpec = if (reduce) snap() else tween(220), label = "vibeScenarioEdge")
    Column(modifier.width(88.dp).clip(ui.shape(18.dp))
        .clickable(onClick = onClick).semantics { this.selected = selected }
        .padding(horizontal = 4.dp, vertical = ui.gap(4.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ui.gap(6.dp))) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(fill)
            .border(1.5.dp, edge, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(27.dp), tint = if (selected) scheme.primary else scheme.onSurface)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 36.dp), contentAlignment = Alignment.TopCenter) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** One native visual choice for both the actual shortcuts and their read-only preview. */
@Composable
internal fun VibeScenarioTile(
    compact: Boolean, label: String, icon: ImageVector, selected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    if (compact) PrismChip(selected, onClick, label, modifier, icon, fitLabel = true)
    else VibeScenarioCard(label, icon, selected, onClick, modifier)
}
