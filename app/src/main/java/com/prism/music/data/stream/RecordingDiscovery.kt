package com.prism.music.data.stream

import com.prism.music.data.ExplicitFilter
import com.prism.music.data.SongVersions
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.ExplicitContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stable source identities. An explicit recording choice remains independent of discovery preferences. */
internal enum class RecordingSource(val label: String) {
    SOUNDCLOUD("SoundCloud"), BANDCAMP("Bandcamp"), AUDIUS("Audius"),
    ZAYCEV("Zaycev.net"), YOUTUBE("YouTube Music");

    companion object {
        fun of(id: String): RecordingSource = when {
            SoundCloudVersion.url(id) != null -> SOUNDCLOUD
            BandcampVersion.url(id) != null -> BANDCAMP
            AudiusVersions.url(id) != null -> AUDIUS
            ZaycevVersions.url(id) != null -> ZAYCEV
            else -> YOUTUBE
        }
    }
}

/** User-controlled search/display order, persisted independently of per-song playback pins. */
internal data class RecordingSourcePlan(val order: List<RecordingSource>, val disabled: Set<RecordingSource> = emptySet()) {
    val enabled: Set<RecordingSource> get() = order.filterNot { it in disabled }.toSet()
    fun move(source: RecordingSource, delta: Int): RecordingSourcePlan {
        val from = order.indexOf(source)
        val to = from + delta
        if (from < 0 || to !in order.indices) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }
    fun toggle(source: RecordingSource): RecordingSourcePlan =
        copy(disabled = if (source in disabled) disabled - source else disabled + source)
    companion object {
        fun restore(order: String?, disabled: Set<String>): RecordingSourcePlan {
            val sources = RecordingSource.entries
            val stored = order.orEmpty().split(',').mapNotNull { name -> sources.firstOrNull { it.name == name } }
            return RecordingSourcePlan((stored + sources).distinct(), sources.filter { it.name in disabled }.toSet())
        }
    }
}

internal data class RecordingHit(val song: Song, val source: RecordingSource, val catalogs: Set<String> = emptySet())

internal data class RecordingSearchResult(
    val source: RecordingSource,
    val hits: List<RecordingHit> = emptyList(),
    val failed: Boolean = false,
)

internal object RecordingSearchRank {
    /** Strict identity filter at the boundary, even if an individual adapter is less strict. */
    fun accepted(target: Song, hit: RecordingHit, mode: ExplicitContent): Boolean =
        RecordingSource.of(hit.song.id) == hit.source &&
            SongVersions.compatibleRecording(target, hit.song) &&
            ExplicitFilter.plays(mode, hit.song)

    fun rank(target: Song, plan: RecordingSourcePlan, results: Collection<RecordingSearchResult>, mode: ExplicitContent): List<RecordingHit> =
        results.asSequence().filter { it.source in plan.enabled && !it.failed }.flatMap { it.hits.asSequence() }
            .filter { accepted(target, it, mode) }
            .sortedWith(compareBy<RecordingHit>(
                { plan.order.indexOf(it.source) },
                { !(mode == ExplicitContent.SHOW && SongVersions.preferredOriginal(target, it.song)) },
                { it.song.isVideo },
                { !SongVersions.sameRecording(target, it.song) },
                { kotlin.math.abs(target.durationSec - it.song.durationSec) },
            ))
            .distinctBy { it.song.id }.toList()
}

/** One discovery session emits each provider's outcome independently. A failed provider never hides another's hits.
 * No automatic audio substitution: only a user-selected exact recording is pinned by the existing playback layer.
 */
internal class RecordingDiscovery(private val ytm: YouTubeMusic, private val streams: StreamResolver) {
    fun search(song: Song, country: String, mode: ExplicitContent, enabled: Set<RecordingSource>): Flow<RecordingSearchResult> = channelFlow {
        val active = enabled.filter { it == RecordingSource.YOUTUBE || mode == ExplicitContent.SHOW }
        active.forEach { source ->
            launch {
                val result = try {
                    val hits = withContext(Dispatchers.IO) {
                        if (source == RecordingSource.YOUTUBE) youtube(song, country, mode)
                        else {
                            val songs = when (source) {
                                RecordingSource.SOUNDCLOUD -> streams.soundCloudVersions(song)
                                RecordingSource.BANDCAMP -> streams.bandcampVersions(song)
                                RecordingSource.AUDIUS -> streams.audiusVersions(song)
                                RecordingSource.ZAYCEV -> streams.zaycevVersions(song)
                                RecordingSource.YOUTUBE -> emptyList()
                            }
                            songs.map { RecordingHit(it, source) }
                        }
                    }
                    RecordingSearchResult(source, hits.filter { RecordingSearchRank.accepted(song, it, mode) })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    RecordingSearchResult(source, failed = true)
                }
                send(result)
            }
        }
    }

    private suspend fun youtube(song: Song, country: String, mode: ExplicitContent): List<RecordingHit> = coroutineScope {
        val explicit = mode == ExplicitContent.SHOW
        val queries = SongVersions.searchQueries(song, explicit)
        val first = queries.firstOrNull() ?: return@coroutineScope emptyList()
        val all = mutableListOf<Song>()
        val catalogs = mutableMapOf<String, MutableSet<String>>()
        var answered = false
        suspend fun local(q: String, f: SearchFilter): List<Song>? =
            try { ytm.search(q, f).items.filterIsInstance<SongItem>().map { it.song } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        suspend fun foreign(q: String, region: String): List<Song>? =
            try { ytm.catalogSongs(q, region) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        fun add(songs: List<Song>?, region: String? = null) {
            if (songs == null) return
            answered = true
            all += songs
            if (region != null) songs.forEach { catalogs.getOrPut(it.id) { mutableSetOf() }.add(region) }
        }
        fun matches(): List<Song> = SongVersions.candidates(song,
            all.filter { ExplicitFilter.plays(mode, it) }.sortedByDescending { it.explicit },
            preferExplicit = explicit)
        // The regular and regional catalogs are independent; retain successful empty responses.
        val ordinary = async { local(first, SearchFilter.SONGS) }
        val videos = async { local(first, SearchFilter.VIDEOS) }
        val regional = if (explicit) SongVersions.alternativeCatalogs(country).map { r ->
            async { r to foreign(first, r) }
        } else emptyList()
        add(ordinary.await())
        add(videos.await())
        regional.forEach { job -> val (r, songs) = job.await(); add(songs, r) }
        for (batch in queries.drop(1).chunked(2)) {
            if (matches().size >= 6 && (!explicit || matches().any { it.explicit })) break
            batch.map { q -> async { local(q, SearchFilter.SONGS) } }.forEach { add(it.await()) }
        }
        if (matches().size < 2) {
            add(local(first, SearchFilter.TOP))
            if (explicit) {
                val q = queries.getOrNull(1) ?: first
                SongVersions.alternativeCatalogs(country).map { r -> async { r to foreign(q, r) } }.forEach {
                    val (r, songs) = it.await(); add(songs, r)
                }
            }
        }
        if (!answered) throw java.io.IOException("No YouTube catalog answered")
        matches().map { RecordingHit(it, RecordingSource.YOUTUBE, catalogs[it.id].orEmpty()) }
    }
}
