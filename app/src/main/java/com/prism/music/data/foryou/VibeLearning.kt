package com.prism.music.data.foryou

import kotlin.math.abs
import kotlin.math.pow

/**
 * Lightweight, bounded session and sequencing signals. No model download, network calls or audio decoding.
 * Long-term evidence is maintained separately by RecFeedback.
 */
internal object VibeLearning {
    enum class Outcome { ACCEPTED, SKIPPED }
    data class Counts(val accepted: Double = 0.0, val skipped: Double = 0.0)

    /** Only actual listening time counts; opening a queue or a failed load is not a dislike. */
    fun outcome(heardMs: Long, durationMs: Long, userSkipped: Boolean): Outcome? {
        if (heardMs < 250L) return null
        if (durationMs > 0 && heardMs.toDouble() / durationMs >= 0.7) return Outcome.ACCEPTED
        val early = if (durationMs > 0) minOf(30_000L, durationMs * 3 / 10) else 30_000L
        return if (userSkipped && heardMs < early) Outcome.SKIPPED else null
    }

    /** Missing acoustic measurements are neutral. Close neighbors receive a bounded boost. */
    fun sequenceWeight(previous: Double?, next: Double?): Double {
        if (previous == null || next == null || !previous.isFinite() || !next.isFinite()) return 1.0
        val gap = abs(previous.coerceIn(0.0, 1.0) - next.coerceIn(0.0, 1.0))
        return 0.75 + 0.45 * (1.0 - gap).pow(2)
    }

    /** Shrink sparse recent feedback toward neutral, with a deliberately limited influence on the durable taste. */
    fun sessionWeight(accepted: Double, skipped: Double): Double {
        val a = accepted.coerceAtLeast(0.0)
        val s = skipped.coerceAtLeast(0.0)
        return 1.0 + 0.35 * (a - s) / (a + s + 3.0)
    }

    /** Number of requested slots before this index; arithmetic is bounded even for long sessions. */
    fun quotaCount(index: Int, percent: Int): Long =
        (index.coerceAtLeast(0).toLong() * percent.coerceIn(0, 100) + 50) / 100

    /**
     * Allocate language independently inside familiar and discovery tracks. Two 50% sliders
     * should not mean every discovery is Russian and every familiar track foreign.
     * Each stratum has cumulative rounding error at most half a track.
     */
    fun languageSlot(index: Int, discovery: Int, language: Int): Boolean {
        val i = index.coerceIn(0, Int.MAX_VALUE - 1)
        val before = quotaCount(i, discovery)
        val next = quotaCount(i + 1, discovery)
        val within = if (next > before) before else i.toLong() - before
        val p = language.coerceIn(0, 100).toLong()
        return ((within + 1) * p + 50) / 100 > (within * p + 50) / 100
    }

    /** Artist fatigue spans batches, but never acts as a hard exclusion. Most recent carries most weight. */
    fun artistPressure(artists: List<String>): Map<String, Double> {
        val result = HashMap<String, Double>()
        artists.takeLast(12).asReversed().forEachIndexed { index, name ->
            val key = name.lowercase().trim()
            if (key.isNotEmpty()) result[key] = (result[key] ?: 0.0) + 0.82.pow(index)
        }
        return result
    }

    fun diversityWeight(pressure: Double, diversity: Double): Double =
        1.0 / (1.0 + pressure.coerceAtLeast(0.0) * (0.12 + 0.38 * diversity.coerceIn(0.0, 1.0)))

    /** Bounded artist-balanced recall seeds. Contextual caches never become personal seeds by accident. */
    fun <T> recallSeeds(positive: List<T>, fallback: List<T>, id: (T) -> String, artist: (T) -> String,
        eligible: (T) -> Boolean, limit: Int = 12): List<T> {
        val pool = positive.filter(eligible).distinctBy(id).ifEmpty { fallback.filter(eligible).distinctBy(id) }
        val diverse = pool.distinctBy { artist(it).lowercase().trim().ifBlank { id(it) } }
        val used = diverse.mapTo(hashSetOf(), id)
        return (diverse + pool.filter { id(it) !in used }).take(limit.coerceAtLeast(0))
    }

    class Session(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
        private data class Event(val artist: String, val outcome: Outcome, val at: Long)
        private val events = ArrayDeque<Event>()
        private var scope: String? = null

        @Synchronized fun note(key: String, artist: String, outcome: Outcome) {
            val now = clock()
            if (scope != key || events.lastOrNull()?.let { now - it.at !in 0L..GAP_MS } == true) {
                events.clear()
                scope = key
            }
            if (artist.isBlank()) return
            events.addLast(Event(artist.lowercase(), outcome, now))
            while (events.size > 48) events.removeFirst()
        }

        @Synchronized fun snapshot(key: String): Map<String, Counts> {
            val now = clock()
            if (scope != key || events.lastOrNull()?.let { now - it.at !in 0L..GAP_MS } != false) return emptyMap()
            val result = HashMap<String, Counts>()
            for (event in events) {
                val age = now - event.at
                if (age !in 0L..HORIZON_MS) continue
                val w = 0.5.pow(age.toDouble() / HALF_LIFE_MS)
                val old = result[event.artist] ?: Counts()
                result[event.artist] = when (event.outcome) {
                    Outcome.ACCEPTED -> old.copy(accepted = old.accepted + w)
                    Outcome.SKIPPED -> old.copy(skipped = old.skipped + w)
                }
            }
            return result
        }

        @Synchronized fun clear() { events.clear(); scope = null }
    }

    private const val GAP_MS = 45 * 60_000L
    private const val HALF_LIFE_MS = 30 * 60_000L
    private const val HORIZON_MS = 3 * 60 * 60_000L
}
