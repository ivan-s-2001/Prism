package com.prism.music.data.foryou

import com.prism.music.data.model.Song
import kotlin.random.Random

/** A personal station, not a shuffled playlist. The existing radio engine replenishes it. */
internal object MyWave {
    const val ORIGIN = "prism:my-wave"
    // A short recommendation horizon lets new skips and likes affect the next refill sooner.
    const val HORIZON = 8

    enum class Character(val bias: Float, val percent: Int) { BALANCED(0f, 70), FAMILIAR(-0.75f, 0), DISCOVERY(0.75f, 100) }
    enum class Mood(val energy: Float) { ANY(0f), CALM(-0.8f), ENERGETIC(0.8f) }
    enum class Depth(val bias: Float) { ANY(0f), HITS(-0.65f), DEEP(0.65f) }
    enum class Activity(val energy: Float) {
        ANY(0f), WAKE(0.45f), ROAD(0.25f), WORK(-0.35f), TRAIN(0.85f), SLEEP(-0.9f)
    }
    data class Style(
        val character: Character = Character.BALANCED,
        val mood: Mood = Mood.ANY,
        val depth: Depth = Depth.ANY,
        val activity: Activity = Activity.ANY,
        val diversity: Int = 50,
        val discoveryPercent: Int = character.percent,
        val russianPercent: Int = -1, // -1: automatic, otherwise target share by title-script proxy
    ) {
        val origin: String get() = "$ORIGIN:${character.name}:${mood.name}:${depth.name}:${activity.name}:${diversity.coerceIn(0, 100)}:${discoveryPercent.coerceIn(0, 100)}:${russianPercent.coerceIn(-1, 100)}"
        // An explicit mood takes precedence over the activity's approximate energy target.
        val tune: RadioTune get() = RadioTune(newness = character.bias,
            energy = if (mood == Mood.ANY) activity.energy else mood.energy, depth = depth.bias)
    }
    /** Only migrate the legacy radio sliders when no Stream settings have ever been saved. */
    fun legacyStyle(tune: RadioTune, language: Int): Style? {
        if (tune.neutral) return null
        val character = when { tune.newness < -0.95f -> Character.FAMILIAR; tune.newness > 0.95f -> Character.DISCOVERY; else -> Character.BALANCED }
        return Style(character = character, mood = when { tune.energy < -0.05f -> Mood.CALM; tune.energy > 0.05f -> Mood.ENERGETIC; else -> Mood.ANY },
            depth = when { tune.depth < -0.05f -> Depth.HITS; tune.depth > 0.05f -> Depth.DEEP; else -> Depth.ANY },
            discoveryPercent = kotlin.math.round(50f + 50f * tune.newness).toInt().coerceIn(0, 100), russianPercent = language.coerceIn(-1, 100))
    }
    fun isOrigin(origin: String?) = origin == ORIGIN || origin?.startsWith("$ORIGIN:") == true

    /** Queue settings take priority over the saved defaults; older installs saved only language. */
    fun restore(queueOrigin: String?, savedOrigin: String?, legacyLanguage: Int): Style = when {
        isOrigin(queueOrigin) -> style(queueOrigin)
        isOrigin(savedOrigin) -> style(savedOrigin)
        else -> Style(russianPercent = legacyLanguage.coerceIn(-1, 100))
    }

    /** Do not immediately restart the same recording unless it is the only offline option. */
    fun initialPicks(picks: List<Song>, current: Song?, offline: Boolean): List<Song> {
        val key = current?.let(LocalRadio::sameSong)
        val different = picks.filter { LocalRadio.sameSong(it) != key }
        return if (different.isEmpty() && offline) picks else different
    }
    fun style(origin: String?): Style {
        val parts = origin?.removePrefix("$ORIGIN:")?.split(':').orEmpty()
        val character = Character.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: Character.BALANCED
        return Style(character,
            Mood.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: Mood.ANY,
            Depth.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: Depth.ANY,
            Activity.entries.firstOrNull { it.name == parts.getOrNull(3) } ?: Activity.ANY,
            parts.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 100) ?: 50,
            parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 100) ?: character.percent,
            parts.getOrNull(6)?.toIntOrNull()?.coerceIn(-1, 100) ?: -1)
    }

    /** Decayed implicit feedback. Beta prior shrinks a few observations toward neutral.
     * Exploration is bounded and applies only to genuinely unfamiliar candidates.
     * These values are ranking signals, not calibrated probabilities of a listener liking a track.
     */
    data class Evidence(val accepted: Double = 0.0, val skipped: Double = 0.0,
        val sessionAccepted: Double = 0.0, val sessionSkipped: Double = 0.0) {
        val mean: Double get() = (2.0 + accepted.coerceAtLeast(0.0)) /
            (4.0 + accepted.coerceAtLeast(0.0) + skipped.coerceAtLeast(0.0))
        fun weight(explore: Double): Double {
            val n = accepted.coerceAtLeast(0.0) + skipped.coerceAtLeast(0.0)
            val confidence = n / (n + 4.0)
            val preference = 1.0 + 0.8 * (mean - 0.5) * confidence
            val uncertainty = 0.12 * explore.coerceIn(0.0, 1.0) / kotlin.math.sqrt(1.0 + n)
            return (preference + uncertainty) * VibeLearning.sessionWeight(sessionAccepted, sessionSkipped)
        }
    }

    /** Explicit likes have two time scales: current taste and durable preferences.
     * Unknown/future timestamps are not treated as new likes. Old likes never lose all influence.
     */
    fun likeWeight(likedAt: Long, now: Long): Double {
        if (likedAt <= 0L || likedAt > now) return 0.20
        val days = (now - likedAt).toDouble() / 86_400_000.0
        return 0.20 + 0.55 * Math.pow(0.5, days / 14.0) + 0.25 * Math.pow(0.5, days / 90.0)
    }

    /** Diminishing returns prevent bulk likes from one artist swamping the profile. */
    fun likeSaturation(weight: Double): Double = 1.0 - kotlin.math.exp(-weight.coerceAtLeast(0.0) / 2.0)

    /** Bounded, local signals: absent history is neutral, not a dislike. */
    class Profile(
        val taste: (Song) -> Double? = { null },
        val context: (Song) -> Double? = { null },
        val transition: (Song, Song) -> Double? = { _, _ -> null },
        val likedAffinity: (Song) -> Double = { 0.0 },
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

    /** Cumulative quota, rounded to the nearest song. Never reset the fraction at each refill.
     * Only explicitly liked recordings may fill the liked slots; only unrecognized recordings may fill discoveries.
     * If a required pool is exhausted, return a shorter prefix rather than silently changing the selected ratio.
     */
    fun discoverySlot(index: Int, percent: Int): Boolean {
        val p = percent.coerceIn(0, 100).toLong()
        val i = index.coerceAtLeast(0).toLong()
        return ((i + 1) * p + 50) / 100 > (i * p + 50) / 100
    }

    /** No reliable vocal-language field is provided by the catalogue. Use title script as
     * an explicitly approximate signal; never infer language from artist nationality. */
    fun russianTitle(song: Song): Boolean? {
        val letters = song.title.filter { it.isLetter() }
        if (letters.isEmpty()) return null
        val russian = letters.count { it.lowercaseChar() in 'а'..'я' || it.lowercaseChar() == 'ё' }
        if (letters.any { it.lowercaseChar() in "іїєґў" }) return null
        return when {
            russian * 2 >= letters.length -> true
            russian == 0 && letters.any { java.lang.Character.UnicodeScript.of(it.code) != java.lang.Character.UnicodeScript.CYRILLIC } -> false
            else -> null
        }
    }

    /** The supplied pool contains completed, policy-eligible downloads only. Never duplicate
     * an upcoming recording. Relax unavailable quota slots only in offline mode. */
    fun offlineMix(
        pool: List<Song>, likes: List<Song>, upcoming: List<Song>, previous: Song?,
        heard: (Song) -> Boolean, style: Style, signals: RadioSignals, profile: Profile,
        count: Int, offset: Int, seed: Long,
        artistDoubt: (String) -> Double = { 0.0 },
        artistEvidence: (String) -> Evidence = { Evidence() },
    ): List<Song> {
        val queued = upcoming.mapTo(hashSetOf(), LocalRadio::sameSong)
        val available = pool.distinctBy(LocalRadio::sameSong).filter { LocalRadio.sameSong(it) !in queued }
        val previousKey = previous?.let(LocalRadio::sameSong)
        val cycle = available.filter { LocalRadio.sameSong(it) != previousKey }.ifEmpty { available }
        val preferred = cycle.filter {
            when (style.russianPercent) {
                0 -> russianTitle(it) == false
                100 -> russianTitle(it) == true
                else -> true
            }
        }.ifEmpty { cycle }
        val fallbackStyle = if (preferred.none { when (style.russianPercent) { 0 -> russianTitle(it) == false; 100 -> russianTitle(it) == true; else -> true } }) style.copy(russianPercent = -1) else style
        val availableIds = preferred.mapTo(hashSetOf()) { it.id }
        val localLikes = likes.filter { it.id in availableIds }
        val likedIds = localLikes.mapTo(hashSetOf()) { it.id }
        val fresh = preferred.filterNot(heard)
        val strict = mix(fresh, localLikes.filterNot(heard), isKnown = { heard(it) || it.id in likedIds },
            heard = { false }, allowed = { true }, seed = seed, style = style, signals = signals,
            profile = profile, count = count, offset = offset, previousSong = previous,
            artistDoubt = artistDoubt, artistEvidence = artistEvidence)
        val result = strict.toMutableList()
        val used = strict.mapTo(hashSetOf(), LocalRadio::sameSong)
        // Relax only the unavailable offline quota, not exclusions or queue deduplication.
        for (tier in listOf(fresh, preferred)) {
            if (result.size >= count) break
            val ids = tier.mapTo(hashSetOf()) { it.id }
            val rest = start(tier, localLikes.filter { it.id in ids }, emptyList(), heard,
                allowed = { LocalRadio.sameSong(it) !in used }, seed = seed,
                count = count - result.size, style = fallbackStyle, signals = signals, profile = profile,
                previousSong = result.lastOrNull() ?: previous, artistDoubt = artistDoubt,
                artistEvidence = artistEvidence)
            result += rest
            used += rest.map(LocalRadio::sameSong)
        }
        return result
    }

    fun mix(
        candidates: List<Song>, likes: List<Song>, isKnown: (Song) -> Boolean,
        heard: (Song) -> Boolean, allowed: (Song) -> Boolean, seed: Long,
        style: Style, signals: RadioSignals, profile: Profile, count: Int = HORIZON,
        offset: Int = 0, previousSong: Song? = null, avoid: Set<String> = emptySet(),
        artistDoubt: (String) -> Double = { 0.0 }, servedRecently: (Song) -> Boolean = { false },
        artistEvidence: (String) -> Evidence = { Evidence() },
    ): List<Song> {
        val likedKeys = likes.mapTo(hashSetOf(), LocalRadio::sameSong)
        val likedIds = likes.mapTo(hashSetOf()) { it.id }
        val fresh = candidates.filter { it.id !in likedIds && LocalRadio.sameSong(it) !in likedKeys && !isKnown(it) }
        return start(fresh, likes, emptyList(), heard,
            allowed = { allowed(it) && !heard(it) }, seed = seed, count = count, style = style,
            signals = signals, artistDoubt = artistDoubt, servedRecently = servedRecently,
            profile = profile, previousSong = previousSong, avoid = avoid,
            artistEvidence = artistEvidence, quotaOffset = offset, strictQuota = true)
    }

    /** Shared personalized score; absent signals remain neutral. */
    fun affinity(profile: Profile, song: Song): Double {
        val taste = profile.taste(song)?.coerceIn(0.0, 1.0)?.let { 0.65 + 0.7 * it } ?: 1.0
        val likes = 1.0 + 1.35 * profile.likedAffinity(song).coerceIn(0.0, 1.0)
        return taste * likes
    }

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
        artistEvidence: (String) -> Evidence = { Evidence() },
        quotaOffset: Int? = null,
        strictQuota: Boolean = false,
        artistCap: Int? = null,
        allowArtistRelaxation: Boolean = true,
        candidateWeight: (Song) -> Double = { 1.0 },
        explorationNoise: Double = 0.07,
    ): List<Song> {
        if (count <= 0) return emptyList()
        val recentKeys = recent.mapTo(hashSetOf(), LocalRadio::sameSong)
        val random = Random(seed)
        val diversity = style.diversity.coerceIn(0, 100) / 100.0
        val artistLimit = artistCap?.coerceAtLeast(1) ?: if (diversity >= 0.75) 2 else if (diversity <= 0.25) 4 else 3
        val noise = explorationNoise.coerceIn(0.0, 0.95)
        val explore = 0.1 + 0.9 * style.discoveryPercent.coerceIn(0, 100) / 100.0
        val energyBias = style.tune.energy
        // Cache measured energy once per recording per batch: selection evaluates candidates repeatedly.
        val measuredEnergy = (recommendations + favorites + recent + listOfNotNull(previousSong))
            .distinctBy { it.id }.associate { it.id to signals.energy(it) }
        val favoriteKeys = favorites.mapTo(hashSetOf(), LocalRadio::sameSong)
        fun familiar(s: Song): Boolean? = if (LocalRadio.sameSong(s) in favoriteKeys || LocalRadio.sameSong(s) in recentKeys || heard(s)) true else signals.heard(s)
        fun pool(songs: List<Song>) = songs.filter { allowed(it) && LocalRadio.sameSong(it) !in avoid &&
            when (style.russianPercent) { 0 -> russianTitle(it) == false; 100 -> russianTitle(it) == true; else -> true } }
            .distinctBy(LocalRadio::sameSong)
            .mapIndexed { index, s ->
                // Preserve ranking from the candidate generator; explore by bounded noise, not a uniform shuffle.
                val rank = 0.35 + 0.65 / (1.0 + index * 0.045)
                val novelty = when (familiar(s)) { true -> 1.0 - style.character.bias * 0.75; false -> 1.0 + style.character.bias * 0.75; null -> 1.0 }
                val energy = measuredEnergy[s.id]?.takeIf { it.isFinite() }?.let { 1.0 + energyBias * (2.0 * it.coerceIn(0.0, 1.0) - 1.0) } ?: 1.0
                val rest = if (heard(s) || LocalRadio.sameSong(s) in recentKeys) 0.12 else if (servedRecently(s)) 0.55 else 1.0
                val feedback = 1.0 - 0.65 * artistDoubt(s.primaryArtist).coerceIn(0.0, 1.0)
                val taste = affinity(profile, s)
                val context = profile.context(s)?.coerceIn(0.0, 1.0)?.let { 0.8 + 0.4 * it } ?: 1.0
                val depth = signals.onTopList(s)?.let { if (it) 1.0 - style.depth.bias else 1.0 + style.depth.bias } ?: 1.0
                val learned = artistEvidence(s.primaryArtist).weight(if (familiar(s) == false) explore else 0.0)
                s to rank * novelty * energy * depth * rest * feedback * taste * context * learned *
                    candidateWeight(s).takeIf { it.isFinite() && it >= 0.0 }.let { it ?: 1.0 } * (1.0 - noise + 2.0 * noise * random.nextDouble())
            }
            .sortedByDescending { it.second }
            .toMutableList()
        val picks = pool(recommendations.filter { if (strictQuota) LocalRadio.sameSong(it) !in favoriteKeys else familiar(it) != true })
        val known = pool(if (strictQuota) favorites else favorites + recommendations.filter { familiar(it) == true })
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
                    ((!strict && allowArtistRelaxation) || ((artist.isBlank() || artist != previous) && (artists[artist] ?: 0) < artistLimit))
            }.maxByOrNull { index ->
                val (song, score) = from[index]
                val fit = lastSong?.let { profile.transition(it, song) }?.coerceIn(0.0, 1.0)
                val continuity = (fit?.let { 0.8 + 0.4 * it } ?: 1.0) *
                    VibeLearning.sequenceWeight(lastSong?.let { measuredEnergy[it.id] }, measuredEnergy[song.id])
                val spread = 1.0 / (1.0 + (0.1 + 0.7 * diversity) * (artists[song.primaryArtist.lowercase()] ?: 0))
                score * continuity * spread
            } ?: return null
            return from.removeAt(i).first
        }
        while (out.size < count) {
            val preferKnown = if (quotaOffset != null) !discoverySlot(quotaOffset + out.size, style.discoveryPercent)
            else when (style.character) {
                Character.FAMILIAR -> out.size % 4 != 3
                Character.DISCOVERY -> out.size % 5 == 4
                Character.BALANCED -> out.size % 3 == 1
            }
            val preferred = if (strictQuota) listOf(if (preferKnown) known else picks)
                else if (preferKnown) listOf(known, picks, last) else listOf(picks, known, last)
            val s = if (quotaOffset != null && style.russianPercent in 1..99) {
                // Prefer the requested language in the preferred pool, then fall back to the available music.
                val russian = discoverySlot(quotaOffset + out.size, style.russianPercent)
                fun takeLanguage(from: MutableList<Pair<Song, Double>>, strict: Boolean): Song? {
                    val matching = from.filter { russianTitle(it.first) == russian }.toMutableList()
                    val chosen = take(matching, strict) ?: return null
                    from.removeAll { LocalRadio.sameSong(it.first) == LocalRadio.sameSong(chosen) }
                    return chosen
                }
                preferred.firstNotNullOfOrNull { takeLanguage(it, true) }
                    ?: preferred.firstNotNullOfOrNull { takeLanguage(it, false) }
                    ?: preferred.firstNotNullOfOrNull { take(it, true) }
                    ?: preferred.firstNotNullOfOrNull { take(it, false) }
            } else preferred.firstNotNullOfOrNull { take(it, true) }
                ?: preferred.firstNotNullOfOrNull { take(it, false) }
            if (s == null) break
            seen += LocalRadio.sameSong(s)
            previous = s.primaryArtist.lowercase()
            artists[previous] = (artists[previous] ?: 0) + 1
            lastSong = s
            out += s
        }
        return out
    }
}
