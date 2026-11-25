package com.prism.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.foryou.MyWave
import com.prism.music.data.foryou.WaveSettingsDraft
import kotlin.math.roundToInt

/** The same labels, numeric mapping and draft updates on touch and TV. */
internal enum class WaveTuningScale(
    val title: Int, val left: Int, val right: Int, val step: Int,
    val ratio: Boolean = true, val automatic: Boolean = false,
    val detail: Int,
) {
    DISCOVERY(R.string.prism_stream_discovery_mix, R.string.prism_stream_liked_songs,
        R.string.prism_stream_discoveries, 5, detail = R.string.prism_stream_discoveries_are_recordings_outside_prism_s_known_history_offline_propo),
    LANGUAGE(R.string.prism_stream_music_language, R.string.prism_stream_foreign,
        R.string.prism_stream_russian, 5, automatic = true,
        detail = R.string.prism_stream_approximate_title_language_balance_the_actual_mix_may_vary_when_matchi),
    DIVERSITY(R.string.prism_stream_variety, R.string.prism_stream_less,
        R.string.prism_stream_more, 10, ratio = false,
        detail = R.string.prism_stream_variety_of_artists_and_recordings);

    fun value(style: MyWave.Style): Int = when (this) {
        DISCOVERY -> style.discoveryPercent
        LANGUAGE -> style.russianPercent
        DIVERSITY -> style.diversity
    }

    /** Stored ratio is the right-hand share; the divider is positioned by the left-hand share. */
    fun position(value: Int): Int = when {
        automatic && value < 0 -> 50
        ratio -> 100 - value.coerceIn(0, 100)
        else -> value.coerceIn(0, 100)
    }
    fun fromPosition(position: Float): Int = if (ratio) 100 - snap(position) else snap(position)
    fun snap(value: Float): Int = ((value / step).roundToInt() * step).coerceIn(0, 100)
    fun change(draft: WaveSettingsDraft, value: Int): WaveSettingsDraft = when (this) {
        DISCOVERY -> draft.discovery(snap(value.toFloat()))
        LANGUAGE -> draft.tune(draft.style.copy(russianPercent = if (value < 0) -1 else snap(value.toFloat())))
        DIVERSITY -> draft.tune(draft.style.copy(diversity = snap(value.toFloat())))
    }

    fun level(value: Int): String = tr(when {
        value <= 20 -> R.string.prism_stream_low
        value <= 60 -> R.string.prism_stream_medium
        value < 100 -> R.string.prism_stream_high
        else -> R.string.prism_stream_maximum
    })
}

/** Keep each percentage immediately beside its own category, never in the middle of the rail. */
@Composable
internal fun WaveScaleLabels(
    scale: WaveTuningScale, value: Int, accent: Color, muted: Color,
    labelStyle: TextStyle, numberStyle: TextStyle,
    center: (@Composable () -> Unit)? = null,
) {
    if (!scale.ratio) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr(scale.title), Modifier.weight(1f), style = labelStyle, color = muted)
            Text(scale.level(value), style = numberStyle, color = accent, textAlign = TextAlign.End)
        }
        return
    }
    val automatic = scale.automatic && value < 0
    val pos = scale.position(value)
    val leftColor = if (scale.ratio && !automatic) accent else muted
    val rightColor = muted
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr(scale.left), style = labelStyle, color = leftColor)
            if (scale.ratio && !automatic) Text(tr(R.string.prism_stream_share_percent, pos),
                style = numberStyle, color = leftColor, maxLines = 1)
        }
        center?.invoke()
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr(scale.right), style = labelStyle, color = rightColor, textAlign = TextAlign.End)
            if (scale.ratio && !automatic) Text(tr(R.string.prism_stream_share_percent, 100 - pos),
                style = numberStyle, color = rightColor, maxLines = 1)
        }
    }
}

/** The left and right segments are actual complementary shares. Auto has no assigned shares. */
@Composable
internal fun WaveScaleRail(
    scale: WaveTuningScale, value: Int, accent: Color, muted: Color, surface: Color,
    modifier: Modifier = Modifier, showThumb: Boolean = false,
) {
    val auto = scale.automatic && value < 0
    val fraction = scale.position(value) / 100f
    Canvas(modifier.fillMaxWidth().height(if (showThumb) 24.dp else 12.dp)) {
        val y = size.height / 2
        val inset = if (showThumb) 8.dp.toPx() else 0f
        val start = inset.coerceAtMost(size.width / 2)
        val end = size.width - start
        val x = start + (end - start) * fraction
        val thickness = 8.dp.toPx()
        val corner = CornerRadius(thickness / 2f)
        val top = y - thickness / 2f
        val railSize = Size((end - start).coerceAtLeast(0f), thickness)
        drawRoundRect(if (scale.ratio && !auto) muted.copy(alpha = 0.60f) else muted.copy(alpha = 0.24f),
            topLeft = Offset(start, top), size = railSize, cornerRadius = corner)
        if (!auto && x > start) clipRect(left = start, top = top, right = x, bottom = top + thickness) {
            drawRoundRect(accent, topLeft = Offset(start, top), size = railSize, cornerRadius = corner)
        }
        if (showThumb) {
            drawCircle(surface, 8.dp.toPx(), Offset(x, y))
            drawCircle(if (auto) muted else accent, 6.dp.toPx(), Offset(x, y))
        }
    }
}
