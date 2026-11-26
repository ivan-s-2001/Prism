package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismFilledTonalButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismIcons

/** The same compact transport hierarchy on albums, playlists and artists. */
@Composable
internal fun MusicPageActions(
    enabled: Boolean,
    playTag: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    extra: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val playContent: @Composable RowScope.() -> Unit = {
            Icon(PrismIcons.Play, null, Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr(R.string.widget_play), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val playModifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(playTag)
        if (LocalAppSettings.current.shapes.playOutlined) {
            PrismOutlinedButton(onPlay, playModifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp), content = playContent)
        } else {
            PrismButton(onPlay, playModifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp), content = playContent)
        }
        MusicPageIconAction(PrismIcons.Shuffle, tr(R.string.shuffle), enabled = enabled, onClick = onShuffle)
        extra()
    }
}

@Composable
internal fun MusicPageIconAction(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PrismFilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, label, Modifier.size(22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current)
    }
}
