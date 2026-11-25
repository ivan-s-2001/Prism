package com.prism.music.data.foryou

import kotlin.math.sqrt

/**
 * A local co-listening graph, not a population-wide collaborative filter.
 * Repetition inside one sitting cannot create unlimited evidence. Normalize edge
 * support by each node's exposure and shrink weak evidence toward zero.
 */
internal object VibeGraph {
    data class Event(val recording: String, val artist: String, val at: Long, val weight: Double)
    data class PairKey(val a: String, val b: String)
    private fun key(a: String, b: String) = if (a <= b) PairKey(a, b) else PairKey(b, a)

    class Network internal constructor(
        private val exposure: Map<String, Double>,
        private val links: Map<PairKey, Double>,
    ) {
        fun similarity(a: String, b: String): Double? {
            if (a == b || a.isBlank() || b.isBlank()) return null
            val support = links[key(a, b)] ?: return null
            val scale = sqrt((exposure[a] ?: return null) * (exposure[b] ?: return null))
            if (scale <= 0.0) return null
            return ((support / scale) * (support / (support + 2.0))).coerceIn(0.0, 1.0)
        }
    }

    class Model internal constructor(private val tracks: Network, private val artists: Network) {
        fun affinity(a: String, b: String, artistA: String, artistB: String): Double? {
            val exact = tracks.similarity(a, b)
            // Artist connections can generalize to a recording the listener has never heard.
            val related = artists.similarity(artistA, artistB)?.times(0.65)
            return listOfNotNull(exact, related).maxOrNull()
        }
    }

    private class Builder {
        private val exposure = HashMap<String, Double>()
        private val edges = HashMap<PairKey, Double>()
        private val sessionExposure = HashMap<String, Double>()
        private val sessionEdges = HashMap<PairKey, Double>()

        fun see(node: String, weight: Double) {
            if (node.isBlank()) return
            sessionExposure.merge(node, weight) { a, b -> maxOf(a, b) }
        }
        fun link(a: String, b: String, weight: Double) {
            if (a.isBlank() || b.isBlank() || a == b) return
            sessionEdges.merge(key(a, b), weight) { x, y -> maxOf(x, y) }
        }
        fun finishSession() {
            sessionExposure.forEach { (id, w) -> exposure.merge(id, w, Double::plus) }
            sessionEdges.forEach { (pair, w) -> edges.merge(pair, w, Double::plus) }
            sessionExposure.clear()
            sessionEdges.clear()
        }
        fun build(): Network { finishSession(); return Network(exposure, edges) }
    }

    /** Ignore barely heard recordings as positive affinity evidence. Unknown duration uses actual listening time. */
    fun listeningWeight(playedMs: Long, durationMs: Long): Double {
        if (playedMs < 10_000) return 0.0
        if (durationMs <= 0) return (playedMs / 60_000.0).coerceIn(0.0, 1.0)
        val fraction = (playedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        return if (fraction < 0.2) 0.0 else fraction
    }

    fun build(events: List<Event>): Model {
        val tracks = Builder()
        val artists = Builder()
        val last = ArrayDeque<Event>()
        var previousAt: Long? = null
        // Bounded work even with a very large imported history. A skipped item is not a positive graph node.
        for (event in events.sortedBy { it.at }.takeLast(20_000)) {
            if (!event.weight.isFinite() || event.weight <= 0 || event.recording.isBlank()) continue
            val weight = event.weight.coerceIn(0.0, 1.0)
            val previous = previousAt
            if (previous != null && event.at - previous > 30 * 60_000L) {
                tracks.finishSession(); artists.finishSession(); last.clear()
            }
            previousAt = event.at
            tracks.see(event.recording, weight)
            artists.see(event.artist, weight)
            last.toList().asReversed().forEachIndexed { index, other ->
                val edge = minOf(weight, other.weight) / (index + 1)
                tracks.link(event.recording, other.recording, edge)
                artists.link(event.artist, other.artist, edge)
            }
            last.addLast(event.copy(weight = weight))
            while (last.size > 3) last.removeFirst()
        }
        return Model(tracks.build(), artists.build())
    }
}
