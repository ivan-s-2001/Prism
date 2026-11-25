package com.prism.music.data.foryou

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.AppContainer
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.model.Song
import com.prism.music.download.PlayLikelihood
import com.prism.music.playback.MusicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * What Prism makes from the listener's own music, on the phone, without YouTube: Prism's radio ([radio], what autoplay
 * plays after a song from your own plays, likes, downloads and files), Home's lists ([sections]: On repeat, Repeat
 * rewind, Forgotten favourites) and mixes from your music ([Sections.mixes]: by genre, by artist, and what's ready
 * offline). All from Replay's history, which never leaves the phone.
 */
class YourMusic(private val c: AppContainer) {
    private val lock = Mutex()
    private var snap: Snapshot? = null
    @Volatile private var lastSections: Sections? = null

    /** The phone's music, read once every few minutes (or after new plays). */
    private class Snapshot(
        val at: Long,
        val events: List<PlayEventEntity>,
        val songs: Map<String, Song>,
        val liked: List<Song>,
        val offline: List<Song>,
        val genres: Map<String, String>,
        val model: PlayLikelihood,
        val coListen: Map<String, Map<String, Double>>,
    ) {
        /** Plays in the last [Variety.LATELY_DAYS] days, and each song's last play. */
        val lately: Map<String, Int> = events.filter { at - it.timestamp < Variety.LATELY_DAYS * DAY }.groupingBy { it.songId }.eachCount()
        val last: Map<String, Long> = HashMap<String, Long>().also { m -> events.forEach { e -> if (e.timestamp > (m[e.songId] ?: 0L)) m[e.songId] = e.timestamp } }
    }

    /** How much of its weight [id] keeps today ([Variety.rest]): heard lately, on repeat, served in a recent mix. */
    private fun rest(s: Snapshot, id: String, served: Boolean = true) = Variety.rest(
        s.lately[id] ?: 0, s.last[id]?.let { (System.currentTimeMillis() - it) / 3_600_000.0 }, if (served) c.recFeedback.servedDaysAgo(id) else null,
    )

    private suspend fun snapshot(): Snapshot = lock.withLock {
        val now = System.currentTimeMillis()
        val count = runCatching { c.imports.taste.changeCount(now) }.getOrDefault(0)
        // Kept for a few minutes, and only while no new play has come in.
        snap?.takeIf { now - it.at < 5 * 60_000 && count == total }?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val events = c.imports.taste.tasteEventsSince(now - YEAR)
            val liked = c.db.songs().liked().map { it.toSong() }
            val local = c.localMusic.tracks.value.map { it.song }
            val downloaded = c.downloads.completedSongs()
            val songs = HashMap<String, Song>()
            events.map { it.songId }.distinct().chunked(500).flatMap { c.db.songs().getAll(it) }.forEach { songs[it.id] = it.toSong() }
            (liked + downloaded + local).forEach { songs.putIfAbsent(it.id, it) }
            val usable = songs.values.filter { ok(it) }
            val genres = runCatching { c.meta.cachedGenres(usable) }.getOrDefault(emptyMap())
            val model = PlayLikelihood(events, songs, liked.map { it.id }.toSet(), now)
            val co = LocalRadio.coListen(events.map { LocalRadio.Play(it.songId, it.timestamp) })
            Snapshot(now, events, songs, liked, (downloaded + local).distinctBy { it.id }, genres, model, co).also { snap = it; total = count }
        }
    }

    @Volatile private var total = -1

    internal fun recommendable(s: Song): Boolean = RecommendationQuality.allowed(s, s.id in c.library.likedIds.value)

    /** Hard exclusions shared by all generated music, not by explicitly selected tracks. */
    internal fun recommendationAllowed(s: Song, known: Boolean = s.id in c.library.likedIds.value,
        offlineOnly: Boolean = false): Boolean =
        !s.isVideo && !s.isEpisode && !s.isLive &&
            (s.durationSec == 0 || s.durationSec >= 60) &&
            recommendable(s) && (!offlineOnly || c.availableOffline(s.id)) &&
            !c.library.isDisliked(s.id) && !c.exclusions.blocks(s) && !c.exclusions.isSongExcluded(s.id) &&
            !c.recFeedback.turnedDown(s.id, known) &&
            com.prism.music.data.ExplicitFilter.plays(c.settings.current.behavior.explicitContent, s)

    private fun ok(s: Song) = recommendable(s) && !c.library.isDisliked(s.id) && !c.exclusions.blocks(s) && !c.exclusions.isSongExcluded(s.id)

    internal data class WaveTaste(val profile: MyWave.Profile, val favorites: List<Song>, val likedSeeds: List<Song>, val likes: List<Song>)
    private data class WarmTaste(val account: String, val likedIds: Set<String>, val at: Long, val taste: WaveTaste)
    @Volatile private var warmTaste: WarmTaste? = null

    /** Local, recency-weighted taste and listening-context profile, shared by Wave start and refill.
     * Context has a small influence until enough history exists; no guesses about lyrics or activity.
     */
    internal suspend fun waveTaste(): WaveTaste = withContext(Dispatchers.Default) {
        val account = waveAccount()
        val likedIds = c.library.likedIds.value.toSet()
        val s = snapshot()
        val now = java.time.ZonedDateTime.now()
        val zone = now.zone
        val hour = now.hour
        val weekend = now.dayOfWeek.value >= 6
        val artist = HashMap<String, Double>()
        val moment = HashMap<String, Double>()
        val recent = s.events.filter { it.timestamp <= s.at && s.at - it.timestamp < 120L * DAY }
        var contextWeight = 0.0
        for (event in recent) {
            val song = s.songs[event.songId] ?: continue
            val a = song.primaryArtist.lowercase()
            if (a.isBlank()) continue
            // Listening for ten seconds is not the same preference signal as finishing a song.
            val duration = song.durationSec * 1000L
            val completion = if (duration > 0) (event.playedMs.toDouble() / duration).coerceIn(0.0, 1.0) else 0.5
            val weight = Math.pow(0.5, (s.at - event.timestamp) / (30.0 * DAY)) * (0.15 + 0.85 * completion)
            artist.merge(a, weight, Double::plus)
            val at = java.time.Instant.ofEpochMilli(event.timestamp).atZone(zone)
            val delta = kotlin.math.abs(hour - at.hour)
            if (minOf(delta, 24 - delta) <= 2 && (at.dayOfWeek.value >= 6) == weekend) {
                moment.merge(a, weight, Double::plus)
                contextWeight += weight
            }
        }
        // Read explicit likes afresh: the play-history snapshot can be cached for five minutes.
        val likeRows = withContext(Dispatchers.IO) { c.db.songs().liked() }
        val nowMs = System.currentTimeMillis()
        val liked = likeRows.map { row -> row.toSong() to MyWave.likeWeight(row.likedAt, nowMs) }
            .filter { (song, _) -> ok(song) && !song.isVideo }
            .sortedByDescending { it.second }.distinctBy { LocalRadio.sameSong(it.first) }
        val directLikes = liked.associate { LocalRadio.sameSong(it.first) to it.second }
        val artistLikes = HashMap<String, Double>()
        val genreLikes = HashMap<String, Double>()
        liked.forEach { (song, weight) ->
            song.primaryArtist.lowercase().takeIf { it.isNotBlank() }?.let {
                artist.merge(it, 1.8 * weight, Double::plus)
                artistLikes.merge(it, weight, Double::plus)
            }
            s.genres[song.id]?.let { genreLikes.merge(it, weight, Double::plus) }
        }
        fun likeAffinity(song: Song): Double {
            val direct = directLikes[LocalRadio.sameSong(song)] ?: 0.0
            val a = MyWave.likeSaturation(artistLikes[song.primaryArtist.lowercase()] ?: 0.0)
            val g = MyWave.likeSaturation(s.genres[song.id]?.let { genreLikes[it] } ?: 0.0)
            return (0.65 * direct + 0.27 * a + 0.08 * g).coerceIn(0.0, 1.0)
        }
        val maxArtist = artist.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val maxMoment = moment.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val confidence = (contextWeight / 20.0).coerceIn(0.0, 1.0)
        val profile = MyWave.Profile(
            likedAffinity = ::likeAffinity,
            taste = { song ->
                val a = artist[song.primaryArtist.lowercase()]?.let { kotlin.math.sqrt(it / maxArtist) }
                val track = s.songs[song.id]?.let { s.model.score(it).coerceIn(0.0, 1.0) }
                if (a == null && track == null) null else 0.55 * (track ?: 0.5) + 0.45 * (a ?: 0.5)
            },
            context = { song ->
                moment[song.primaryArtist.lowercase()]?.let { 0.5 + confidence * (it / maxMoment - 0.5) }
            },
            transition = { from, to ->
                val co = s.coListen[from.id]?.get(to.id)
                val g1 = s.genres[from.id]
                val g2 = s.genres[to.id]
                when {
                    co != null -> (0.5 + co / 8.0).coerceAtMost(1.0)
                    g1 != null && g2 != null -> if (g1 == g2) 0.8 else 0.3
                    else -> null
                }
            },
        )
        val favorites = (liked.map { it.first } + s.songs.values).distinctBy { it.id }
            .filter { ok(it) && !it.isVideo }
            .sortedByDescending { (0.35 * s.model.score(it) + 0.65 * likeAffinity(it)) * rest(s, it.id) }
            .distinctBy(LocalRadio::sameSong).take(100)
        val seedPool = liked.map { it.first }.filter { !it.isLocal && !it.isLive && !it.isEpisode &&
            !c.recFeedback.turnedDown(it.id, known = true) }
        val freshSeeds = seedPool.distinctBy { it.primaryArtist.lowercase().ifBlank { it.id } }.take(8)
        val durableSeeds = seedPool.sortedByDescending { s.model.score(it) }
            .filter { candidate -> freshSeeds.none { it.primaryArtist.equals(candidate.primaryArtist, true) } }
            .distinctBy { it.primaryArtist.lowercase().ifBlank { it.id } }.take(4)
        val historySeeds = if (seedPool.isEmpty()) favorites.filter { !it.isLocal && !it.isVideo &&
            !c.recFeedback.turnedDown(it.id, known = false) }.distinctBy { it.primaryArtist.lowercase() }.take(8) else emptyList()
        WaveTaste(profile, favorites, freshSeeds + durableSeeds + historySeeds, liked.map { it.first }).also {
            if (account == waveAccount() && likedIds == c.library.likedIds.value)
                warmTaste = WarmTaste(account, likedIds, System.currentTimeMillis(), it)
        }
    }

    private data class WavePage(val songs: List<Song>, val continuation: String?, val at: Long)
    private val wavePages = LinkedHashMap<String, WavePage>()
    private val wavePageLock = Mutex()
    private val waveFetchLock = Mutex()

    internal fun vibeFeedbackKey(): String = waveAccount()

    private fun waveAccount(): String {
        val s = c.settings.current
        return "${s.authUser}|${s.dataSyncId}|${s.accountName}"
    }

    private suspend fun cachedWaveRelated(avoid: Set<String> = emptySet(), allowed: (Song) -> Boolean = { true }): List<Song> {
        val account = waveAccount() + "|"
        val now = System.currentTimeMillis()
        return wavePageLock.withLock {
            MyWave.fuse(wavePages.filter { (key, page) -> key.startsWith(account) && now - page.at in 0L..600_000L }
                .values.map { page -> page.songs.filter { LocalRadio.sameSong(it) !in avoid && allowed(it) } }, 160)
        }
    }

    /** The same seed cache for Home mixes and Wave, without leaking unrelated seeds into thematic mixes. */
    internal suspend fun recommendationRadio(seed: Song): List<Song> = waveFetchLock.withLock {
        val account = waveAccount()
        val key = "$account|${seed.id}"
        val now = System.currentTimeMillis()
        val cached = wavePageLock.withLock { wavePages[key]?.takeIf { now - it.at in 0L..600_000L } }
        if (cached != null) return@withLock cached.songs
        if (!c.network.isOnline()) return@withLock emptyList()
        val page = kotlinx.coroutines.withTimeoutOrNull(2000L) {
            withContext(Dispatchers.IO) { c.radio(seed.id, playlistId = "RDAMVM${seed.id}") }
        } ?: return@withLock emptyList()
        if (waveAccount() != account) return@withLock emptyList()
        val songs = page.songsOnly().songs.filter(::recommendable).distinctBy(LocalRadio::sameSong).take(120)
        wavePageLock.withLock {
            wavePages[key] = WavePage(songs, page.continuation, System.currentTimeMillis())
            while (wavePages.size > 24) wavePages.remove(wavePages.keys.first())
        }
        songs
    }

    /** Shared scoring, used by thematic recipes as well as the continuous stream. */
    internal fun recommendationScore(profile: MyWave.Profile, song: Song): Double =
        MyWave.affinity(profile, song)

    /** Prepare local taste/history and related candidates while the Wave screen is visible. */
    internal suspend fun warmWave() {
        val taste = waveTaste()
        val history = c.discover.heard()
        if (!c.network.isOnline() || c.castingNow) return
        val known = c.library.likedIds.value
        waveRelated(taste.likedSeeds, 0, emptySet(), { history.has(it) || it.id in known })
    }

    /** Related-song recall from several positively selected seeds, even when Home is populated.
     * Cache is account-scoped and bounded. During casting only one new request per batch is allowed.
     */
    private suspend fun waveRelated(seeds: List<Song>, offset: Int, avoid: Set<String>, known: (Song) -> Boolean,
        maxRequests: Int = 3, timeoutMs: Long = 2000L, scoped: Boolean = false,
        allowed: (Song) -> Boolean = { true }, preferFresh: Boolean = true): List<Song> = waveFetchLock.withLock {
        fun eligible(song: Song) = LocalRadio.sameSong(song) !in avoid && allowed(song)
        if (seeds.isEmpty()) return@withLock if (scoped) emptyList() else cachedWaveRelated(avoid, allowed)
        val account = waveAccount()
        val turn = (offset / MyWave.HORIZON).coerceAtLeast(0)
        val pool = seeds.distinctBy { it.id }
        val rotated = pool.drop(turn % pool.size) + pool.take(turn % pool.size)
        val now = System.currentTimeMillis()
        val pending = mutableListOf<Pair<Song, WavePage?>>()
        for (song in rotated.take(6)) {
            val cached = wavePageLock.withLock { wavePages["$account|${song.id}"]?.takeIf { now - it.at in 0L..600_000L } }
            if (cached == null || (cached.continuation != null &&
                cached.songs.count { eligible(it) && (!preferFresh || !known(it)) } < 12)) {
                pending += song to cached
            }
        }
        val budget = if (c.castingNow) minOf(maxRequests, 1) else maxRequests
        if (c.network.isOnline() && budget > 0) kotlinx.coroutines.coroutineScope {
            pending.take(budget).map { (song, cached) ->
                async(Dispatchers.IO) {
                    val page = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                        try {
                            c.radio(song.id, playlistId = "RDAMVM${song.id}", continuation = cached?.continuation)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { null }
                    } ?: return@async
                    if (waveAccount() != account) return@async
                    val merged = (cached?.songs.orEmpty() + page.songsOnly().songs).filter(::recommendable).distinctBy(LocalRadio::sameSong).takeLast(120)
                    val key = "$account|${song.id}"
                    wavePageLock.withLock {
                        wavePages.remove(key)
                        wavePages[key] = WavePage(merged, page.continuation, System.currentTimeMillis())
                        while (wavePages.size > 24) wavePages.remove(wavePages.keys.first())
                    }
                }
            }.awaitAll()
        }
        if (!scoped) cachedWaveRelated(avoid, allowed)
        else wavePageLock.withLock {
            val at = System.currentTimeMillis()
            MyWave.fuse(pool.mapNotNull { seed -> wavePages["$account|${seed.id}"]?.takeIf { at - it.at in 0L..600_000L }?.songs?.filter(::eligible) }, 160)
        }
    }

    /** The same bounded startup sequence is used on phone and TV. */
    internal suspend fun startWave(base: List<Song>, style: MyWave.Style, previous: Song?,
        existing: List<Song> = emptyList(),
        fallbackBase: suspend () -> List<Song> = { base },
    ): List<Song> {
        val quick = waveBatch(base, style, 0, previous, existing, count = 2, fast = true)
        if (quick.isNotEmpty()) return quick
        return waveBatch(fallbackBase(), style, 0, previous, existing, count = 2)
    }

    /** Shared start/refill path. New means absent from the available local and imported history, not a claim about
     * every service the listener has ever used. A failed history read cannot turn known songs into discoveries.
     */
    internal suspend fun waveBatch(
        base: List<Song>, style: MyWave.Style, offset: Int, previous: Song?, existing: List<Song>,
        count: Int = MyWave.HORIZON, fast: Boolean = false,
    ): List<Song> = withContext(Dispatchers.Default) {
        val taste = (if (fast) warmTaste?.takeIf {
            it.account == waveAccount() && it.likedIds == c.library.likedIds.value &&
                System.currentTimeMillis() - it.at in 0L..60_000L
        }?.taste else null) ?: waveTaste()
        val offline = !c.network.isOnline()
        if (offline) {
            // A fresh list of completed downloads, never remote candidates or a stale home feed.
            val downloaded = withContext(Dispatchers.IO) { c.downloads.completedSongs() }
            val feedback = c.recFeedback
            val pool = downloaded.filter { recommendationAllowed(it, offlineOnly = true) }.distinctBy(LocalRadio::sameSong)
            val signals = c.discover.radioSignals(pool, style.tune.copy(newness = 0f), fetchTops = false)
            return@withContext MyWave.offlineMix(pool, taste.likes, existing, previous,
                c.sessionHeard::has, style, signals, taste.profile, count, offset, System.nanoTime(),
                artistDoubt = feedback::artistDoubt, artistEvidence = feedback.waveEvidence(vibeFeedbackKey()))
        }
        val avoid = (existing + listOfNotNull(previous) + c.sessionHeard.oldestFirst()).mapTo(hashSetOf(), LocalRadio::sameSong)
        val ids = existing.mapTo(hashSetOf()) { it.id }
        val knownIds = c.library.likedIds.value
        val history = if (style.discoveryPercent > 0) c.discover.heard() else null
        val likedKeys = taste.likes.mapTo(hashSetOf(), LocalRadio::sameSong)
        val known: (Song) -> Boolean = { history?.has(it) != false || it.id in knownIds ||
            LocalRadio.sameSong(it) in likedKeys }
        // With no likes, recommendations can still lead to related songs as the session develops.
        val seeds = (taste.likedSeeds + listOfNotNull(previous).filter { !it.isLocal && !it.isVideo && !it.isEpisode && !it.isLive })
            .distinctBy { it.id }
        val feedback = c.recFeedback
        val recentArtists = existing.takeLast(12).map { it.primaryArtist }
        fun eligible(song: Song) = LocalRadio.sameSong(song) !in avoid &&
            recommendationAllowed(song, known(song)) && when (style.russianPercent) {
                0 -> MyWave.russianTitle(song) == false
                100 -> MyWave.russianTitle(song) == true
                else -> true
            }
        suspend fun select(related: List<Song>, allowSparseProfile: Boolean = false): List<Song> {
            val candidates = MyWave.fuse(listOf(related.filter(::eligible), base.filter(::eligible)), 160)
            val signals = c.discover.radioSignals(candidates + taste.likes.take(100), style.tune.copy(newness = 0f),
                fetchTops = !fast && !c.castingNow)
            val strict = MyWave.mix(candidates, taste.likes, isKnown = known,
            heard = c.sessionHeard::has,
            allowed = { song ->
                song.id !in ids && eligible(song)
            },
            seed = System.nanoTime(), style = style, signals = signals, profile = taste.profile,
            count = count, offset = offset, previousSong = previous, avoid = avoid, artistDoubt = feedback::artistDoubt,
            servedRecently = { feedback.servedDaysAgo(it.id)?.let { days -> days <= 1 } == true },
            artistEvidence = feedback.waveEvidence(vibeFeedbackKey()), recentArtists = recentArtists)
            if (strict.isNotEmpty() || !allowSparseProfile || !VibeContext.sparseProfileFallback(style, taste.likes.isEmpty())) return strict
            // The default mixed preference must still work before the first like. Never relax a 0/100 setting.
            val pool = MyWave.fuse(listOf(candidates, taste.favorites.filter(::eligible)), 160)
            return MyWave.start(pool, pool.filter(known), emptyList(), c.sessionHeard::has,
                { !c.sessionHeard.has(it) && recommendationAllowed(it, known(it)) }, System.nanoTime(),
                count, style, signals = RadioSignals(heard = { known(it) }, onTopList = signals.onTopList, energy = signals.energy),
                profile = taste.profile, previousSong = previous, avoid = avoid,
                artistDoubt = feedback::artistDoubt, artistEvidence = feedback.waveEvidence(vibeFeedbackKey()), quotaOffset = offset,
                recentArtists = recentArtists)
        }
        val cached = if (style.discoveryPercent > 0) cachedWaveRelated(avoid, ::eligible) else emptyList()
        if (fast) {
            val ready = select(cached)
            if (ready.isNotEmpty() || style.discoveryPercent == 0) return@withContext ready
            // Only on a cache miss: a short first lookup, not three full radios.
            val quick = kotlinx.coroutines.withTimeoutOrNull(900L) {
                waveRelated(seeds, offset, avoid, known, maxRequests = 1, timeoutMs = 850L, allowed = ::eligible)
            }
            return@withContext select(quick ?: cachedWaveRelated(avoid, ::eligible), allowSparseProfile = true)
        }
        val related = if (style.discoveryPercent > 0) waveRelated(seeds, offset, avoid, known, allowed = ::eligible) else cached
        select(related, allowSparseProfile = true)
    }


    /** A context is not another recommender: local and remote radios only retrieve candidates.
     * The same taste, feedback, language, diversity and ranking as Stream selects the output.
     * Contextual queues use soft quotas so a new listener or a narrow seed can keep playing.
     */
    internal suspend fun contextBatch(
        anchors: List<Song>, occupied: List<Song>, offset: Int, count: Int = MyWave.HORIZON,
        explicitContext: Boolean = false, smart: Boolean = false, provided: List<Song> = emptyList(),
        recyclePast: List<Song> = emptyList(), protectedQueue: List<Song> = occupied,
    ): List<Song> = withContext(Dispatchers.Default) {
        if (anchors.isEmpty() || count <= 0) return@withContext emptyList()
        val settings = c.settings.current
        val offline = !c.network.isOnline()
        val style = MyWave.restore(null, settings.streamStyleOrigin, settings.streamRussianPercent)
        val taste = waveTaste()
        val history = c.discover.heard()
        val feedback = c.recFeedback
        val liked = c.library.likedIds.value
        val likedKeys = taste.likes.mapTo(hashSetOf(), LocalRadio::sameSong)
        fun known(song: Song) = song.id in liked || LocalRadio.sameSong(song) in likedKeys || history.has(song)
        val avoid = occupied.mapTo(hashSetOf(), LocalRadio::sameSong)
        val avoidIds = occupied.mapTo(hashSetOf()) { it.id }
        val noRepeats = smart || settings.behavior.autoplayNoRepeats
        val mode = settings.autoplaySource
        fun eligible(song: Song): Boolean = LocalRadio.sameSong(song) !in avoid &&
            recommendationAllowed(song, known(song), offlineOnly = offline) &&
            (explicitContext || smart || com.prism.music.data.AutoplayLength.fits(song, settings.behavior.autoplayMaxMinutes)) &&
            (!smart || (feedback.servedDaysAgo(song.id) ?: 99) > 1) &&
            when (style.russianPercent) {
                0 -> MyWave.russianTitle(song) == false
                100 -> MyWave.russianTitle(song) == true
                else -> true
            }
        val recentArtists = occupied.takeLast(12).map { it.primaryArtist }
        val remoteSeeds = anchors.filter { !it.isLocal && !it.isLive && !it.isEpisode && !it.isVideo }
            .distinctBy { it.id }
        val localOnly = offline || remoteSeeds.isEmpty()
        val useLocal = localOnly || explicitContext || smart || mode != com.prism.music.data.prefs.AutoplaySource.YOUTUBE
        val useRemote = !localOnly && (explicitContext || smart || mode != com.prism.music.data.prefs.AutoplaySource.YOURS)
        val recent = c.sessionHeard.oldestFirst()
        val seedWindow = VibeContext.rotated(anchors, offset)
        val own = if (useLocal) radio(seedWindow.map { it.id }, avoidIds, 60, offlineOnly = offline, seedSongs = seedWindow) else emptyList()
        val remote = if (useRemote) waveRelated(remoteSeeds, offset, avoid, ::known, scoped = true, allowed = ::eligible,
            preferFresh = style.discoveryPercent >= 50) else emptyList()
        val local = LocalRadio.besideYouTube(own, onlyYours = !useRemote || (remote.isEmpty() && provided.isEmpty()), radio = remote + provided)
        val localScore = local.associate { LocalRadio.sameSong(it.song) to it.score }
        val bestLocal = local.maxOfOrNull { it.score }?.coerceAtLeast(0.001) ?: 1.0
        val contextArtists = anchors.map { it.primaryArtist.lowercase() }.filter { it.isNotBlank() }.toSet()
        val suppliedKeys = provided.mapTo(hashSetOf(), LocalRadio::sameSong)
        fun contextWeight(song: Song): Double = (if (song.primaryArtist.lowercase() in contextArtists) 1.5 else 1.0) *
            (localScore[LocalRadio.sameSong(song)]?.let { 0.7 + 1.1 * (it / bestLocal).coerceIn(0.0, 1.0) } ?: 1.0) *
            (if (LocalRadio.sameSong(song) in suppliedKeys) 1.25 else 1.0)
        val downloads = if (offline) withContext(Dispatchers.IO) { c.downloads.completedSongs() } else emptyList()
        val pool = MyWave.fuse(listOf(provided, remote, local.map { it.song }, downloads).map { it.filter(::eligible) }, 160)
        val signals = c.discover.radioSignals(pool, style.tune.copy(newness = 0f), fetchTops = !offline && !c.castingNow)
        val contextualSignals = RadioSignals(heard = ::known, onTopList = signals.onTopList, energy = signals.energy)
        val current = seedWindow.lastOrNull()
        val fresh = MyWave.start(pool, pool.filter(::known), emptyList(),
            heard = c.sessionHeard::has, allowed = { eligible(it) && (!noRepeats || !c.sessionHeard.has(it)) },
            seed = System.nanoTime(), count = count, style = style, signals = contextualSignals,
            artistDoubt = feedback::artistDoubt, servedRecently = { (feedback.servedDaysAgo(it.id) ?: 99) <= 1 },
            profile = taste.profile, previousSong = current, avoid = avoid, artistEvidence = feedback.waveEvidence(vibeFeedbackKey()),
            quotaOffset = offset, candidateWeight = ::contextWeight, recentArtists = recentArtists)
        if (fresh.isNotEmpty() || smart) return@withContext fresh
        // A narrow radio may run out. Try a bounded second recall from tracks already selected in this context.
        // Never use unrelated global cached pages, or expand a local-only source into the catalog.
        val secondary = if (useRemote && !c.castingNow) {
            val alternatives = VibeContext.alternativeSeeds(anchors, occupied)
            if (alternatives.isEmpty()) emptyList() else waveRelated(alternatives, offset, avoid, ::known,
                maxRequests = 1, timeoutMs = 1500L, scoped = true, allowed = ::eligible,
                preferFresh = style.discoveryPercent >= 50)
        } else emptyList()
        if (secondary.isNotEmpty()) {
            val second = MyWave.start(secondary, secondary.filter(::known), emptyList(), c.sessionHeard::has,
                { eligible(it) && (!noRepeats || !c.sessionHeard.has(it)) }, System.nanoTime(), count, style,
                signals = contextualSignals, profile = taste.profile, previousSong = current, avoid = avoid,
                artistDoubt = feedback::artistDoubt, artistEvidence = feedback.waveEvidence(vibeFeedbackKey()), quotaOffset = offset,
                candidateWeight = ::contextWeight, recentArtists = recentArtists)
            if (second.isNotEmpty()) return@withContext second
        }
        // As in Prism's original radio, repeat the oldest eligible tracks only after recall has run dry.
        // A network failure alone is not evidence that the catalog is exhausted.
        if (recyclePast.isEmpty() || (!offline && useRemote && remote.isEmpty() && secondary.isEmpty())) return@withContext emptyList()
        val recyclable = VibeContext.recycleCandidates(recyclePast, recent, protectedQueue, current)
            .filter { recommendationAllowed(it, known(it), offlineOnly = offline) &&
                (explicitContext || com.prism.music.data.AutoplayLength.fits(it, settings.behavior.autoplayMaxMinutes)) }
        val fallback = if (offline) MyWave.fuse(listOf(pool, recyclable), 160) else recyclable
        val protected = protectedQueue.mapTo(hashSetOf(), LocalRadio::sameSong)
        return@withContext MyWave.start(fallback, fallback.filter(::known), emptyList(), c.sessionHeard::has,
            { LocalRadio.sameSong(it) !in protected && recommendationAllowed(it, known(it), offlineOnly = offline) &&
                (explicitContext || com.prism.music.data.AutoplayLength.fits(it, settings.behavior.autoplayMaxMinutes)) },
            System.nanoTime(), count, style, signals = contextualSignals, artistDoubt = feedback::artistDoubt,
            profile = taste.profile, previousSong = current, avoid = protected, artistEvidence = feedback.waveEvidence(vibeFeedbackKey()),
            quotaOffset = offset, candidateWeight = ::contextWeight, recentArtists = recentArtists)
    }

    /** Provider mixes are candidate lists, never a second recommendation engine.
     * Explicit live/episode entries retain their original order and semantics.
     */
    internal suspend fun providedStream(songs: List<Song>, count: Int = 24): List<Song> = withContext(Dispatchers.Default) {
        if (songs.isEmpty() || songs.first().isLive || songs.first().isEpisode) return@withContext songs
        val settings = c.settings.current
        val style = MyWave.restore(null, settings.streamStyleOrigin, settings.streamRussianPercent)
        val taste = waveTaste()
        val history = c.discover.heard()
        val liked = c.library.likedIds.value
        val feedback = c.recFeedback
        val offline = !c.network.isOnline()
        val pool = songs.filter { recommendationAllowed(it, it.id in liked || history.has(it), offlineOnly = offline) }
        val signals = c.discover.radioSignals(pool, style.tune.copy(newness = 0f), fetchTops = !offline && !c.castingNow)
        MyWave.start(pool, pool.filter { it.id in liked || history.has(it) }, emptyList(),
            c.sessionHeard::has, { recommendationAllowed(it, it.id in liked || history.has(it), offlineOnly = offline) },
            System.nanoTime(), count, style,
            signals = RadioSignals(heard = { it.id in liked || history.has(it) }, onTopList = signals.onTopList, energy = signals.energy),
            artistDoubt = feedback::artistDoubt, servedRecently = { (feedback.servedDaysAgo(it.id) ?: 99) <= 1 },
            profile = taste.profile, artistEvidence = feedback.waveEvidence(vibeFeedbackKey()), quotaOffset = 0)
    }

    /**
     * Prism's radio: up to [count] songs from your own music to follow [seedIds] (the newest last), none of [inQueue],
     * nothing played in the last two hours. [offlineOnly]: only songs that play without a connection.
     */
    suspend fun radio(seedIds: List<String>, inQueue: Set<String>, count: Int, offlineOnly: Boolean, seedSongs: List<Song> = emptyList()): List<LocalRadio.Pick> {
        val s = snapshot()
        val seeds = seedIds.mapNotNull { id -> seedSongs.firstOrNull { it.id == id } ?: s.songs[id] ?: c.db.songs().get(id)?.toSong() }
        if (seeds.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val recent = s.events.filter { now - it.timestamp < 2 * HOUR }.map { it.songId }.toSet()
        // A seed new to the phone's music (just picked from search) can still have its genre saved: how close your songs
        // are to it goes by that (#461).
        val seedGenres = seeds.filter { it.id !in s.genres }.takeIf { it.isNotEmpty() }
            ?.let { runCatching { c.meta.cachedGenres(it) }.getOrNull() }.orEmpty()
        val profile = waveTaste().profile
        val evidence = c.recFeedback.waveEvidence()
        val pool = LocalRadio.Pool(
            // Not clips under a minute (an album's intro, a skit, a sound file on the phone): they'd break the run of songs.
            songs = s.songs.values.filter { recommendationAllowed(it, offlineOnly = offlineOnly) },
            coListen = s.coListen,
            affinity = { s.model.score(it) * rest(s, it.id) * recommendationScore(profile, it) * evidence(it.primaryArtist).weight(0.0) },
            genreOf = { s.genres[it] ?: seedGenres[it] },
            keyFit = { a, b -> keyFit(a, b) },
            bpmOf = { c.mixTempo.djBpm(it) },
        )
        val day = LocalDate.now().toString()
        return LocalRadio.pick(seeds, pool, inQueue + recent, count) { id -> 0.9 + 0.2 * (((id + day + seedIds.last()).hashCode().toLong() and 0xffff) / 65535.0) }
    }

    private fun keyFit(a: String, b: String): Double {
        val ka = c.mixTempo.key(a) ?: return 0.0
        val kb = c.mixTempo.key(b) ?: return 0.0
        return (1.0 - 2 * MusicKey.clash(ka, kb)).coerceIn(0.0, 1.0)
    }

    data class Sections(
        val onRepeat: List<Song>,
        val repeatRewind: List<Song>,
        val forgotten: List<Song>,
        val mixes: List<Mix>,
    )

    /** Home's lists and mixes from your music; [offlineOnly] keeps to songs that play without a connection. */
    suspend fun sections(offlineOnly: Boolean): Sections = withContext(Dispatchers.Default) {
        val s = snapshot()
        val now = System.currentTimeMillis()
        val play: (Song) -> Boolean = { recommendationAllowed(it, offlineOnly = offlineOnly) }
        val byId = s.events.groupBy { it.songId }
        val last = byId.mapValues { (_, e) -> e.maxOf { it.timestamp } }

        fun counted(from: Long, to: Long) = s.events.asSequence().filter { it.timestamp in from until to }.groupingBy { it.songId }.eachCount()

        val month = counted(now - 30 * DAY, now)
        val onRepeat = month.filter { it.value >= 2 }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { last[it.key] ?: 0 })
            .mapNotNull { s.songs[it.key] }.filter(play).distinctBy { LocalRadio.sameSong(it) }.take(30).takeIf { it.size >= MIN_LIST }.orEmpty()

        val before = counted(now - YEAR, now - 60 * DAY)
        val repeatRewind = before.filter { it.value >= 3 && (last[it.key] ?: 0) < now - 60 * DAY }.entries
            .sortedByDescending { it.value }
            .mapNotNull { s.songs[it.key] }.filter(play).distinctBy { LocalRadio.sameSong(it) }.take(30).takeIf { it.size >= MIN_LIST }.orEmpty()

        val plays = byId.mapValues { it.value.size }
        val forgotten = (
            s.liked.filter { (last[it.id] ?: 0) < now - 90 * DAY } +
                plays.filter { it.value >= 5 && (last[it.key] ?: 0) < now - 120 * DAY }.keys.mapNotNull { s.songs[it] }
            ).distinctBy { it.id }
            .sortedByDescending { plays[it.id] ?: 0 }
            .filter(play).distinctBy { LocalRadio.sameSong(it) }.take(30).takeIf { it.size >= MIN_LIST }.orEmpty()

        val mixes = libraryMixes(s, play, waveTaste().profile)
        Sections(onRepeat, repeatRewind, forgotten, mixes).also { lastSections = it }
    }

    /** A mix from your music by id (for its page), from the last [sections]. */
    fun mix(id: String): Mix? = lastSections?.mixes?.firstOrNull { it.id == id }

    /**
     * Mixes from your music: one for each of your top genres and artists, made from songs you know (played, liked,
     * downloaded, on the phone), best first with a day's shuffle; and, when there's enough, what's ready offline.
     */
    private fun libraryMixes(s: Snapshot, play: (Song) -> Boolean, profile: MyWave.Profile): List<Mix> {
        val day = LocalDate.now().toString()
        fun j(id: String) = 0.85 + 0.3 * (((id + day).hashCode().toLong() and 0xffff) / 65535.0)
        val candidates = s.songs.values.filter(play)
        val score = candidates.associate { it.id to s.model.score(it) * MyWave.affinity(profile, it) * j(it.id) }
        // One copy of each song (the best liked), however many uploads of it you've played.
        val known = candidates.sortedByDescending { score[it.id] ?: 0.0 }.distinctBy { LocalRadio.sameSong(it) }
        val out = ArrayList<Mix>()

        // Each mix is drawn by weighted chance (the day's own draw), resting what was heard lately, instead of the same top forty.
        val feedback = c.recFeedback
        fun draw(songs: List<Song>, salt: String): List<Song> = MyWave.start(
            emptyList(), songs.sortedByDescending { (score[it.id] ?: 0.0) * rest(s, it.id) }, emptyList(),
            heard = { false }, allowed = play, seed = Variety.daySeed(day, salt), count = MIX_SIZE,
            style = MyWave.Style(discoveryPercent = 0), artistDoubt = feedback::artistDoubt,
            servedRecently = { (feedback.servedDaysAgo(it.id) ?: 99) <= 1 },
            profile = profile, artistEvidence = feedback.waveEvidence(), quotaOffset = 0)
        val byGenre = known.groupBy { s.genres[it.id] }.filterKeys { it != null }
        byGenre.entries.sortedByDescending { (_, songs) -> songs.sumOf { score[it.id] ?: 0.0 } }.take(3).forEach { (genre, songs) ->
            if (songs.size < MIN_MIX) return@forEach
            val list = draw(songs, genre!!)
            out += Mix("lib:genre:$genre", tr(R.string.your_mix, com.prism.music.data.meta.Genres.word(genre)), MadeForYou.namesLine(artists(list)), artists(list).take(4), color(genre), s.at, list)
        }

        val artistPlays = s.events.groupingBy { s.songs[it.songId]?.primaryArtist.orEmpty() }.eachCount().filterKeys { it.isNotBlank() }
        artistPlays.entries.sortedByDescending { it.value }.take(3).forEach { (artist, _) ->
            val theirs = draw(known.filter { it.primaryArtist.equals(artist, ignoreCase = true) }, "artist:$artist")
            if (theirs.size < 3) return@forEach
            // Their songs you know, with the ones you play around them.
            val around = LocalRadio.pick(
                theirs.take(3), LocalRadio.Pool(known, s.coListen, { s.model.score(it) * MyWave.affinity(profile, it) }, { s.genres[it] }),
                theirs.map { it.id }.toSet(), 20,
            ).filter { it.related }.map { it.song }
            val list = MyWave.start(around, theirs.take(20), emptyList(), { false }, play,
                Variety.daySeed(day, "artist-mix:$artist"), MIX_SIZE, MyWave.Style(discoveryPercent = 40),
                artistDoubt = feedback::artistDoubt, profile = profile, artistEvidence = feedback.waveEvidence(), quotaOffset = 0)
            if (list.size < MIN_MIX) return@forEach
            out += Mix("lib:artist:$artist", tr(R.string.mix_4, artist), tr(R.string.with_2, MadeForYou.namesLine(artists(list).filterNot { it.equals(artist, true) }.ifEmpty { listOf(artist) })), listOf(artist), color(artist), s.at, list)
        }

        // What plays with no connection at all: downloads, songs kept whole in the cache, files on the phone.
        val ready = known.filter { c.availableOffline(it.id) }.sortedByDescending { score[it.id] ?: 0.0 }
        if (ready.size >= MIN_MIX) out += Mix(OFFLINE_MIX, tr(R.string.ready_offline), tr(R.string.a_mix_of_songs_already_on), artists(ready).take(4), 0xFF37474F.toInt(), s.at, draw(ready, OFFLINE_MIX))
        return out
    }

    /** A finite recommendation surface using the same profile, recall, and ranking as Wave.
     * Unlike the continuous stream, Home may fill spare slots with previously known music.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun quickPicks(
        youtube: List<Song>, radioOf: suspend (Song) -> List<Song>, count: Int = 20, draw: Int = 0, shown: Set<String> = emptySet(),
    ): List<Song> = withContext(Dispatchers.Default) {
        val taste = waveTaste()
        val history = c.discover.heard()
        val liked = c.library.likedIds.value
        val likedKeys = taste.likes.mapTo(hashSetOf(), LocalRadio::sameSong)
        fun known(song: Song) = song.id in liked || LocalRadio.sameSong(song) in likedKeys || history.has(song)
        val recent = c.sessionHeard.oldestFirst()
        val recentKeys = recent.mapTo(hashSetOf(), LocalRadio::sameSong)
        val feedback = c.recFeedback
        fun eligible(song: Song) = recommendationAllowed(song, known(song), offlineOnly = !c.network.isOnline())
        val related = if (c.network.isOnline()) waveRelated(taste.likedSeeds, draw.coerceAtLeast(0) * MyWave.HORIZON,
            recentKeys, ::known) else emptyList()
        val candidates = MyWave.fuse(listOf(related.filter(::recommendable), youtube.filter(::recommendable)), 160)
        val settings = c.settings.current
        val style = MyWave.restore(null, settings.streamStyleOrigin, settings.streamRussianPercent)
        val seed = drawSeed(LocalDate.now().toString(), "personal", draw)
        val signals = RadioSignals(heard = { known(it) })
        fun select(avoidShown: Boolean, size: Int, avoid: Set<String>) = MyWave.start(candidates, taste.favorites,
            emptyList(), c.sessionHeard::has, { eligible(it) && (!avoidShown || it.id !in shown) },
            seed, size, style, signals, feedback::artistDoubt,
            { feedback.servedDaysAgo(it.id)?.let { days -> days <= 1 } == true },
            taste.profile, avoid = avoid, artistEvidence = feedback.waveEvidence(), quotaOffset = draw.coerceAtLeast(0) * count)
        val fresh = select(true, count, recentKeys)
        val rest = if (fresh.size < count && shown.isNotEmpty())
            select(false, count - fresh.size, recentKeys + fresh.map(LocalRadio::sameSong)) else emptyList()
        fresh + rest
    }

    /** Songs ready offline, most likely first: Quick picks' stand-in with no connection. */
    suspend fun offlinePicks(count: Int = 20): List<Song> {
        val s = snapshot()
        val profile = waveTaste().profile
        val feedback = c.recFeedback
        val settings = c.settings.current
        val style = MyWave.restore(null, settings.streamStyleOrigin, settings.streamRussianPercent)
        val pool = s.songs.values.filter { recommendationAllowed(it, offlineOnly = true) }
            .sortedByDescending { s.model.score(it) * rest(s, it.id) }.distinctBy(LocalRadio::sameSong)
        return MyWave.offlineMix(pool, s.liked, emptyList(), null, c.sessionHeard::has, style,
            RadioSignals(), profile, count, 0, Variety.daySeed(LocalDate.now().toString(), "offline"),
            artistDoubt = feedback::artistDoubt, artistEvidence = feedback.waveEvidence())
    }

    private fun artists(list: List<Song>) = list.map { it.primaryArtist }.filter { it.isNotBlank() }.distinct()

    /** The same artist not twice running where it can be helped. */
    private fun spread(list: List<Song>) = MadeForYou.interleave(list, emptyList(), list.size) { it.primaryArtist.lowercase() }

    private fun color(key: String) = COLORS[((key.hashCode() % COLORS.size) + COLORS.size) % COLORS.size]

    companion object {
        /** A draw's seed: the day's own for the first ([draw] 0), another for each pull to refresh (#371). */
        internal fun drawSeed(day: String, salt: String, draw: Int): Long = Variety.daySeed(day, if (draw == 0) salt else "$salt#$draw")

        /**
         * Quick picks' turns: two favourites, two new, one of YouTube's, no artist twice running and three at most. Songs
         * in [shown] (the set a pull to refresh replaces) only come when nothing else fits, so a refresh changes the picks
         * for as far as there are others.
         */
        internal fun takeTurns(favourites: List<Song>, discovered: List<Song>, youtube: List<Song>, count: Int, shown: Set<String> = emptySet()): List<Song> {
            val turns = listOf(0, 1, 0, 1, 2)
            val out = ArrayList<Song>()
            val perArtist = HashMap<String, Int>()
            val taken = HashSet<String>()
            fun fits(song: Song): Boolean {
                val a = song.primaryArtist.lowercase()
                return LocalRadio.sameSong(song) !in taken && (perArtist[a] ?: 0) < 3 && (out.isEmpty() || out.last().primaryArtist.lowercase() != a || a.isBlank())
            }
            fun run(pools: List<MutableList<Song>>) {
                var turn = out.size
                while (out.size < count) {
                    val order = listOf(turns[turn % turns.size]) + (0..2).filter { it != turns[turn % turns.size] }
                    val pool = order.map { pools[it] }.firstOrNull { p -> p.any { fits(it) } } ?: break
                    val song = pool.first { fits(it) }
                    pool.remove(song)
                    out += song
                    taken += LocalRadio.sameSong(song)
                    song.primaryArtist.lowercase().let { perArtist[it] = (perArtist[it] ?: 0) + 1 }
                    turn++
                }
            }
            val pools = listOf(favourites, discovered, youtube)
            run(pools.map { p -> p.filterTo(ArrayList()) { it.id !in shown } })
            if (shown.isNotEmpty()) run(pools.map { p -> p.filterTo(ArrayList()) { it.id in shown } })
            return out
        }

        const val OFFLINE_MIX = "lib:offline"
        fun isLibraryMix(id: String) = id.startsWith("lib:")
        private const val HOUR = 3_600_000L
        private const val DAY = 86_400_000L
        private const val YEAR = 365 * DAY
        private const val MIN_LIST = 4
        /** Plays in 90 days before Quick picks are made around the listener's artists. */
        private const val MIN_PICK_HISTORY = 15
        private const val MIN_MIX = 8
        private const val MIX_SIZE = 40
        private val COLORS = listOf(0xFF2E7D6B, 0xFF6A4C93, 0xFFB5523B, 0xFF1F6FB2, 0xFF8C6D1F, 0xFF9C3D6B).map { it.toInt() }
    }
}
