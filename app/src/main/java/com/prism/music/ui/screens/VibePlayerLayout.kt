package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
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
