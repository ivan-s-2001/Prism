package com.prism.music.data.foryou

import com.prism.music.data.model.Song
import kotlin.random.Random

/** A personal station, not a shuffled playlist. The existing radio engine replenishes it. */
internal object MyWave {
    const val ORIGIN = "prism:my-wave"

    enum class Character(val bias: Float) { BALANCED(0f), FAMILIAR(-0.75f), DISCOVERY(0.75f) }
    enum class Mood(val energy: Float) { ANY(0f), CALM(-0.8f), ENERGETIC(0.8f) }
    data class Style(val character: Character = Character.BALANCED, val mood: Mood = Mood.ANY) {
        val origin: String get() = "$ORIGIN:${character.name}:${mood.name}"
        val tune: RadioTune get() = RadioTune(newness = character.bias, energy = mood.energy)
    }
    fun isOrigin(origin: String?) = origin == ORIGIN || origin?.startsWith("$ORIGIN:") == true
    fun style(origin: String?): Style {
        val parts = origin?.removePrefix("$ORIGIN:")?.split(':').orEmpty()
        return Style(Character.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: Character.BALANCED,
            Mood.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: Mood.ANY)
    }

    /**
     * The personalized picks already include listening affinity and discoveries.
     * Mix in favorites, prefer songs not recently heard, and vary each new session.
     * Never relax exclusions, explicit-content restrictions, or offline availability.
     */
    fun start(
        recommendations: List<Song>,
        favorites: List<Song>,
        recent: List<Song>,
        heard: (Song) -> Boolean,
        allowed: (Song) -> Boolean,
        seed: Long,
        count: Int = 12,
        style: Style = Style(),
        signals: RadioSignals = RadioSignals(),
        artistDoubt: (String) -> Double = { 0.0 },
        servedRecently: (Song) -> Boolean = { false },
    ): List<Song> {
        if (count <= 0) return emptyList()
        val recentKeys = recent.mapTo(hashSetOf(), LocalRadio::sameSong)
        val random = Random(seed)
        val favoriteKeys = favorites.mapTo(hashSetOf(), LocalRadio::sameSong)
        fun familiar(s: Song): Boolean? = if (LocalRadio.sameSong(s) in favoriteKeys || LocalRadio.sameSong(s) in recentKeys || heard(s)) true else signals.heard(s)
        fun pool(songs: List<Song>) = songs.filter(allowed)
            .distinctBy(LocalRadio::sameSong)
            .mapIndexed { index, s ->
                // Preserve ranking from the candidate generator; explore by bounded noise, not a uniform shuffle.
                val rank = 1.0 / (1.0 + index * 0.08)
                val novelty = when (familiar(s)) { true -> 1.0 - style.character.bias * 0.75; false -> 1.0 + style.character.bias * 0.75; null -> 1.0 }
                val energy = signals.energy(s)?.let { 1.0 + style.mood.energy * (2.0 * it - 1.0) } ?: 1.0
                val rest = if (heard(s) || LocalRadio.sameSong(s) in recentKeys) 0.12 else if (servedRecently(s)) 0.55 else 1.0
                val feedback = 1.0 - 0.65 * artistDoubt(s.primaryArtist).coerceIn(0.0, 1.0)
                s to rank * novelty * energy * rest * feedback * (0.85 + 0.30 * random.nextDouble())
            }
            .sortedByDescending { it.second }
            .map { it.first }.toMutableList()
        val picks = pool(recommendations.filter { familiar(it) != true })
        val known = pool(favorites + recommendations.filter { familiar(it) == true })
        val last = pool(recent)
        val seen = hashSetOf<String>()
        val out = mutableListOf<Song>()
        val artists = hashMapOf<String, Int>()
        var previous = ""
        fun take(from: MutableList<Song>, strict: Boolean): Song? {
            val i = from.indexOfFirst { s ->
                val artist = s.primaryArtist.lowercase()
                LocalRadio.sameSong(s) !in seen &&
                    (!strict || ((artist.isBlank() || artist != previous) && (artists[artist] ?: 0) < 3))
            }
            return if (i < 0) null else from.removeAt(i)
        }
        while (out.size < count) {
            val preferKnown = when (style.character) {
                Character.FAMILIAR -> out.size % 4 != 3
                Character.DISCOVERY -> out.size % 5 == 4
                Character.BALANCED -> out.size % 3 == 1
            }
            val preferred = if (preferKnown) listOf(known, picks, last) else listOf(picks, known, last)
            val s = preferred.firstNotNullOfOrNull { take(it, true) }
                ?: preferred.firstNotNullOfOrNull { take(it, false) } ?: break
            seen += LocalRadio.sameSong(s)
            previous = s.primaryArtist.lowercase()
            artists[previous] = (artists[previous] ?: 0) + 1
            out += s
        }
        return out
    }
}
