package com.prism.music.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismFilledTonalButton
import com.prism.music.ui.theme.PrismIcons

/** Shared contextual recommendation action, visible in the top bar rather than only in the overflow. */
@Composable
internal fun ContextVibePill(onClick: () -> Unit, modifier: Modifier = Modifier,
    kind: com.prism.music.playback.VibeOriginKind = com.prism.music.playback.VibeOriginKind.TRACK) {
    PrismFilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(PrismIcons.Stream, null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr(vibeOriginLabel(kind)), style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
