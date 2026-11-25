package com.prism.music.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Fade only the clipped edges of the moving strip; do not dim the fixed settings tile. */
internal fun Modifier.scenarioEdgeFade(backward: () -> Boolean, forward: () -> Boolean): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = 20.dp.toPx().coerceAtMost(size.width / 2f)
            if (fade > 0f && backward()) {
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), 0f, fade),
                    size = Size(fade, size.height), blendMode = BlendMode.DstOut)
            }
            if (fade > 0f && forward()) {
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), size.width - fade, size.width),
                    topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstOut)
            }
        }
