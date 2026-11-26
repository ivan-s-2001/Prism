package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
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
import com.prism.music.data.prefs.DeckPart
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
        val minimum = (if (wide) 340.dp else if (settings.streamShowCover) 500.dp else 350.dp) *
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

/** One adaptive layout for playback and its read-only preview. No playback or preference side effects. */
@Composable
internal fun VibePlayerLayout(
    settings: AppSettings, modifier: Modifier = Modifier,
    cover: @Composable (Modifier) -> Unit, metadata: @Composable () -> Unit,
    progress: @Composable () -> Unit, transport: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier) {
        val inset = ui.gap(if (settings.streamGlassPanel) 16.dp else 4.dp)
        val wide = maxWidth >= 520.dp
        val spacing = ui.gap(12.dp)
        @Composable fun ArtworkSlot(modifier: Modifier) {
            BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                cover(Modifier.size(minOf(maxWidth, maxHeight) * settings.streamCoverSize.fraction))
            }
        }
        @Composable fun Details() {
            metadata()
            val parts = (settings.player.deck + listOf(DeckPart.SCRUBBER, DeckPart.TRANSPORT)).distinct()
            parts.forEach { part ->
                when (part) {
                    DeckPart.SCRUBBER -> if (settings.streamShowProgress) progress()
                    DeckPart.TRANSPORT -> transport()
                    else -> Unit
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(inset), horizontalArrangement = Arrangement.spacedBy(ui.gap(24.dp)),
                verticalAlignment = Alignment.CenterVertically) {
                if (settings.streamShowCover && !settings.streamReverseLayout) ArtworkSlot(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(spacing)) { Details() }
                if (settings.streamShowCover && settings.streamReverseLayout) ArtworkSlot(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterVertically)) {
                if (settings.streamShowCover && !settings.streamReverseLayout) ArtworkSlot(Modifier.weight(1f).fillMaxWidth())
                Details()
                if (settings.streamShowCover && settings.streamReverseLayout) ArtworkSlot(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}


/** The station is not an album. Before playback it has its own mark, drawn with the current icon pack and accent. */
@Composable
internal fun VibeStationMark(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size((minOf(maxWidth, maxHeight) * 0.58f).coerceAtMost(168.dp)), tint = tint)
    }
}

@Composable
internal fun VibeStationTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun VibeStartButton(starting: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    PrismButton(onClick = onStart, enabled = !starting, modifier = modifier) {
        if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(PrismIcons.Play, null, Modifier.size(22.dp))
        Spacer(Modifier.width(ui.gap(8.dp)))
        Text(tr(if (starting) R.string.prism_stream_finding_music else R.string.prism_stream_start_listening))
    }
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
