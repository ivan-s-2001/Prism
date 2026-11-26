package com.prism.music.ui.ambient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.LocalUi

/** Phone desk mode and system screensaver, independent of Android TV. */
@Composable
internal fun PhoneAmbientScreen(song: Song, line: String?, clock: String, modifier: Modifier = Modifier, drift: DpOffset = DpOffset.Zero) {
    val ink = Color(0xFFD8D8DE)
    Box(modifier.fillMaxSize().background(Color(0xFF050507)).testTag(TAG_AMBIENT)) {
        Text(clock, Modifier.align(Alignment.TopEnd).padding(24.dp).offset(drift.x, drift.y), style = MaterialTheme.typography.headlineMedium, color = ink.copy(alpha = 0.6f))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp).offset(drift.x, drift.y), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(song.thumbnail, Modifier.size(200.dp), shape = LocalUi.current.art, size = 544)
            Spacer(Modifier.height(20.dp))
            Text(song.title, style = MaterialTheme.typography.headlineSmall, color = ink, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            Text(song.artistText, style = MaterialTheme.typography.titleMedium, color = ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!line.isNullOrBlank()) {
                Spacer(Modifier.height(24.dp))
                Text(line, style = MaterialTheme.typography.titleLarge, color = ink, maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
const val TAG_AMBIENT = "phone_ambient"
