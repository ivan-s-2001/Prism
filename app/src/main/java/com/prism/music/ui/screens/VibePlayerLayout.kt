package com.prism.music.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.theme.LocalUi

/** One adaptive layout for playback and its read-only preview. No playback or preference side effects. */
@Composable
internal fun VibePlayerLayout(
    settings: AppSettings, modifier: Modifier = Modifier,
    cover: @Composable (Modifier) -> Unit, metadata: @Composable () -> Unit,
    progress: @Composable () -> Unit, transport: @Composable () -> Unit,
) {
    val ui = LocalUi.current
    BoxWithConstraints(modifier) {
        val inset = ui.gap(if (maxWidth < 340.dp) 12.dp else 16.dp)
        val wide = maxWidth >= 520.dp && maxWidth > maxHeight * 1.3f
        val spacing = ui.gap(12.dp)
        @Composable fun ArtworkSlot(modifier: Modifier) {
            BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                cover(Modifier.size(minOf(maxWidth, maxHeight) * settings.streamCoverSize.fraction))
            }
        }
        @Composable fun Details() {
            metadata()
            if (settings.streamShowProgress) progress()
            transport()
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
