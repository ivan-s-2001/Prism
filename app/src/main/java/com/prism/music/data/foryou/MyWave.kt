package com.prism.music.data.foryou

import com.prism.music.data.model.Song
import kotlin.random.Random

/** A personal station, not a shuffled playlist. The existing radio engine replenishes it. */
internal object MyWave {
    const val ORIGIN = "prism:my-wave"

    enum class Character(val bias: Float) { BALANCED(0f), FAMILIAR(-0.75f), DISCOVERY(0.75f) }
    enum class Mood(val energy: Float) { ANY(0f), CALM(-0.8f), ENERGETIC(0.8f) }
    enum class Depth(val bias: Float) { ANY(0f), HITS(-0.65f), DEEP(0.65f) }
    data class Style(val character: Character = Character.BALANCED, val mood: Mood = Mood.ANY, val depth: Depth = Depth.ANY) {
        val origin: String get() = "$ORIGIN:${character.name}:${mood.name}:${depth.name}"
        val tune: RadioTune get() = RadioTune(newness = character.bias, energy = mood.energy, depth = depth.bias)
    }
    fun isOrigin(origin: String?) = origin == ORIGIN || origin?.startsWith("$ORIGIN:") == true
    fun style(origin: String?): Style {
        val parts = origin?.removePrefix("$ORIGIN:")?.split(':').orEmpty()
        return Style(Character.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: Character.BALANCED,
            Mood.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: Mood.ANY,
            Depth.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: Depth.ANY)
    }

    /** Bounded, local signals: absent history is neutral, not a dislike. */
    class Profile(
        val taste: (Song) -> Double? = { null },
        val context: (Song) -> Double? = { null },
        val transition: (Song, Song) -> Double? = { _, _ -> null },
    )

    /** Fusion rewards agreement between independent ranked sources without pretending scores are probabilities. */
    fun fuse(sources: List<List<Song>>, limit: Int = 120): List<Song> {
        val songs = LinkedHashMap<String, Song>()
        val scores = HashMap<String, Double>()
        for (source in sources.filter { it.isNotEmpty() }) {
            source.distinctBy(LocalRadio::sameSong).take(limit).forEachIndexed { i, song ->
                val key = LocalRadio.sameSong(song)
                songs.putIfAbsent(key, song)
                scores.merge(key, 1.0 / (12.0 + i), Double::plus)
            }
        }
        return songs.entries.sortedByDescending { scores[it.key] ?: 0.0 }.take(limit).map { it.value }
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
        profile: Profile = Profile(),
        previousSong: Song? = null,
        avoid: Set<String> = emptySet(),
    ): List<Song> {
        if (count <= 0) return emptyList()
        val recentKeys = recent.mapTo(hashSetOf(), LocalRadio::sameSong)
        val random = Random(seed)
        val favoriteKeys = favorites.mapTo(hashSetOf(), LocalRadio::sameSong)
        fun familiar(s: Song): Boolean? = if (LocalRadio.sameSong(s) in favoriteKeys || LocalRadio.sameSong(s) in recentKeys || heard(s)) true else signals.heard(s)
        fun pool(songs: List<Song>) = songs.filter { allowed(it) && LocalRadio.sameSong(it) !in avoid }
            .distinctBy(LocalRadio::sameSong)
            .mapIndexed { index, s ->
                // Preserve ranking from the candidate generator; explore by bounded noise, not a uniform shuffle.
                val rank = 0.35 + 0.65 / (1.0 + index * 0.045)
                val novelty = when (familiar(s)) { true -> 1.0 - style.character.bias * 0.75; false -> 1.0 + style.character.bias * 0.75; null -> 1.0 }
                val energy = signals.energy(s)?.let { 1.0 + style.mood.energy * (2.0 * it - 1.0) } ?: 1.0
                val rest = if (heard(s) || LocalRadio.sameSong(s) in recentKeys) 0.12 else if (servedRecently(s)) 0.55 else 1.0
                val feedback = 1.0 - 0.65 * artistDoubt(s.primaryArtist).coerceIn(0.0, 1.0)
                val taste = profile.taste(s)?.coerceIn(0.0, 1.0)?.let { 0.65 + 0.7 * it } ?: 1.0
                val context = profile.context(s)?.coerceIn(0.0, 1.0)?.let { 0.8 + 0.4 * it } ?: 1.0
                val depth = signals.onTopList(s)?.let { if (it) 1.0 - style.depth.bias else 1.0 + style.depth.bias } ?: 1.0
                s to rank * novelty * energy * depth * rest * feedback * taste * context * (0.93 + 0.14 * random.nextDouble())
            }
            .sortedByDescending { it.second }
            .toMutableList()
        val picks = pool(recommendations.filter { familiar(it) != true })
        val known = pool(favorites + recommendations.filter { familiar(it) == true })
        val last = pool(recent)
        val seen = hashSetOf<String>()
        val out = mutableListOf<Song>()
        val artists = hashMapOf<String, Int>()
        var previous = previousSong?.primaryArtist?.lowercase().orEmpty()
        var lastSong = previousSong
        fun take(from: MutableList<Pair<Song, Double>>, strict: Boolean): Song? {
            val i = from.indices.filter { index ->
                val s = from[index].first
                val artist = s.primaryArtist.lowercase()
                LocalRadio.sameSong(s) !in seen &&
                    (!strict || ((artist.isBlank() || artist != previous) && (artists[artist] ?: 0) < 3))
            }.maxByOrNull { index ->
                val (song, score) = from[index]
                val fit = lastSong?.let { profile.transition(it, song) }?.coerceIn(0.0, 1.0)
                val continuity = fit?.let { 0.8 + 0.4 * it } ?: 1.0
                val diversity = 1.0 / (1.0 + 0.25 * (artists[song.primaryArtist.lowercase()] ?: 0))
                score * continuity * diversity
            } ?: return null
            return from.removeAt(i).first
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
            lastSong = s
            out += s
        }
        return out
    }
}
