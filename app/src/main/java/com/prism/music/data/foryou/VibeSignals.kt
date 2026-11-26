package com.prism.music.data.foryou

import com.prism.music.data.model.Song

/**
 * Shared recommendation tuning, each from -1 to 1, 0 being as it comes. Each moves only on what the phone really knows:
 * - [newness]: familiar (-1) to new (1), from the listener's own history: exact. Also how much of a batch is their own
 *   music.
 * - [depth]: hits (-1) to deep cuts (1), from whether a song is on its artist's Top songs on YouTube Music, looked up for
 *   a few artists a batch. An artist not looked up yet says nothing.
 * - [energy]: calm (-1) to energetic (1), from the tempo and loudness of songs already scanned ([RadioTuner.energyOf]). A
 *   song not scanned says nothing, and most songs new to a listener aren't scanned: this one is weak on new music.
 */
data class RadioTune(val newness: Float = 0f, val depth: Float = 0f, val energy: Float = 0f) {
    val neutral: Boolean get() = newness == 0f && depth == 0f && energy == 0f
}

/** What the phone knows of a song. Null: it doesn't know, and the song isn't moved either way. */
class RadioSignals(
    val heard: (Song) -> Boolean? = { null },
    val onTopList: (Song) -> Boolean? = { null },
    val energy: (Song) -> Double? = { null },
)

object RadioTuner {
    /** A tuned candidate list stays usable while it has enough candidates. */
    const val MIN_LEFT = 5

    /** How likely [song] is to stay in the batch: 1 as it comes, 0 never, above 1 leads it. Unknown signals leave it at 1. */
    fun chance(song: Song, tune: RadioTune, signals: RadioSignals): Double {
        var w = 1.0
        if (tune.newness != 0f) signals.heard(song)?.let { known -> w *= if (known) 1.0 - tune.newness else 1.0 + tune.newness }
        if (tune.depth != 0f) signals.onTopList(song)?.let { top -> w *= if (top) 1.0 - tune.depth else 1.0 + tune.depth }
        if (tune.energy != 0f) signals.energy(song)?.let { e -> w *= 1.0 + tune.energy * (2 * e - 1) }
        return w.coerceAtLeast(0.0)
    }

    /**
     * [list] as tuned: each song stays by its [chance], and those that are left are ordered with the most fitting towards
     * the front. Neutral, [list] itself, untouched. [seed] makes one batch's draw repeatable.
     */
    fun apply(list: List<Song>, tune: RadioTune, signals: RadioSignals, seed: Long): List<Song> {
        if (tune.neutral || list.isEmpty()) return list
        val rnd = java.util.Random(seed)
        val scored = list.map { it to chance(it, tune, signals) }
        val (kept, dropped) = scored.partition { (_, w) -> rnd.nextDouble() < w }
        val topUp = dropped.sortedByDescending { it.second }.take((MIN_LEFT - kept.size).coerceAtLeast(0))
        val left = kept + topUp
        // Stable: songs nothing is known of keep YouTube's own order, which is how well they fit the seed.
        return left.sortedByDescending { it.second }.map { it.first }
    }

    /** The share of a batch that's YouTube's rather than the listener's own, moved by the familiar ↔ new slider. */
    fun freshShare(base: Double, tune: RadioTune): Double = when {
        tune.newness > 0 -> base + (1 - base) * tune.newness
        tune.newness < 0 -> base * (1 + 0.8 * tune.newness)
        else -> base
    }

    /**
     * 0 (calm) .. 1 (energetic) from a scan: tempo (70 to 170 BPM) and loudness (-20 to -6 LUFS), whichever are known. Null
     * when neither is. Rough on purpose: a tempo can read half or double, and loud isn't the same as lively.
     */
    fun energyOf(bpm: Double?, lufs: Float?): Double? {
        val parts = listOfNotNull(
            bpm?.let { ((it - 70.0) / 100.0).coerceIn(0.0, 1.0) },
            lufs?.let { ((it + 20.0) / 14.0).coerceIn(0.0, 1.0) },
        )
        return if (parts.isEmpty()) null else parts.average()
    }
}
