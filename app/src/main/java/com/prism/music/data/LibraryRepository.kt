package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.AppContainer
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine

enum class SyncState { IDLE, SYNCING, DONE, ERROR }

internal enum class SongReaction { NONE, LIKE, DISLIKE }

internal fun mergeProfileArtists(
    local: List<FavoriteArtist>,
    remote: List<BrowseItem>,
    blocked: List<com.prism.music.data.foryou.Excluded>,
): List<FavoriteArtist> =
    (local + remote.filterIsInstance<ArtistItem>().map { FavoriteArtist(it.id, it.title, it.thumbnail) })
        .distinctBy { it.id }
        .filterNot { favorite -> blocked.any { it.id == favorite.id || it.name.equals(favorite.name, true) } }

internal fun reactionAfterLike(current: SongReaction): SongReaction =
    if (current == SongReaction.LIKE) SongReaction.NONE else SongReaction.LIKE

internal fun reactionAfterDislike(current: SongReaction): SongReaction =
    if (current == SongReaction.DISLIKE) SongReaction.NONE else SongReaction.DISLIKE

/**
 * A list read from the database that can't take Prism down (#103): on a phone short of memory, reading ~1,250 liked
 * songs failed partway ("Couldn't read row 1248… from CursorWindow"), and the error closed the app. Now it's logged and
 * read again, a little later each time ([stepMs] more per try, up to ten steps); the list keeps what it had meanwhile.
 */
internal fun <T> kotlinx.coroutines.flow.Flow<T>.resilient(what: String, stepMs: Long = 1_000L): kotlinx.coroutines.flow.Flow<T> =
    retryWhen { cause, attempt ->
        if (cause is kotlinx.coroutines.CancellationException || attempt >= MAX_READ_TRIES) return@retryWhen false
        runCatching { android.util.Log.w("PrismLibrary", "Reading $what failed (try ${attempt + 1}), reading it again: $cause") }
        com.prism.music.playback.AudioDiagnostics.log("library", "reading $what failed, read again: ${cause.javaClass.simpleName}")
        kotlinx.coroutines.delay(stepMs * (attempt + 1).coerceAtMost(10))
        true
    }.catch { cause ->
        // Still failing after every try: the list keeps what it had (Prism keeps running) until it's next opened.
        if (cause is kotlinx.coroutines.CancellationException) throw cause
        runCatching { android.util.Log.e("PrismLibrary", "Gave up reading $what: $cause") }
        com.prism.music.playback.AudioDiagnostics.log("library", "gave up reading $what: ${cause.javaClass.simpleName}")
    }

/** How many times a failed library read is tried again ([resilient]) before giving up. */
private const val MAX_READ_TRIES = 20L

/** Local mirror of the signed-in user's YouTube Music library, with two-way like sync. */
class LibraryRepository(private val c: AppContainer) {
    private val songs = c.db.songs()

    private val dislikePrefs = c.app.getSharedPreferences("dislikes", android.content.Context.MODE_PRIVATE)
    /** Songs to keep out of autoplay, radio and smart downloads. */
    val dislikedIds = MutableStateFlow(dislikePrefs.getStringSet("ids", emptySet())!!.toSet())
    /** Keep the metadata for excluded tracks, including exclusions saved by older versions. */
    val dislikedSongs: StateFlow<List<Song>> = dislikedIds.map { ids ->
        val rows = ids.toList().chunked(400).flatMap { songs.getAll(it) }.associateBy { it.id }
        ids.map { id -> rows[id]?.toSong() ?: Song(id, id) }
            .sortedBy { it.title.lowercase() }
    }.resilient("disliked songs").flowOn(Dispatchers.IO).stateIn(c.scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val likedIds: StateFlow<Set<String>> = combine(songs.likedIdsFlow().resilient("liked ids"), dislikedIds) { ids, disliked ->
        ids.toSet() - disliked
    }.stateIn(c.scope, SharingStarted.Eagerly, emptySet())

    val liked = combine(songs.likedFlow().resilient("liked songs"), dislikedIds) { list, disliked ->
        list.filterNot { it.id in disliked }.map { it.toSong() }
    }.stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    /**
     * The liked songs as the lists show them (#111): a liked music video whose song is liked too shows once, as the song,
     * unless Settings → Library & Replay → "A song and its video as one" is off. [liked] stays everything that's liked
     * (sync, the Liked playlist mirror, downloads use it).
     */
    val likedShown: StateFlow<List<com.prism.music.data.model.Song>> =
        kotlinx.coroutines.flow.combine(liked, c.settings.flow.map { it.mergeLikedVideos }.distinctUntilChanged()) { list, merge ->
            if (merge) LikedMerge.merge(list, c.queue.counterparts) else list
        }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    val recent = songs.recentFlow(40).resilient("recent songs").map { list -> list.map { it.toSong() } }
        .stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    /** Ids of the phone's own files, most recently played first. */
    val recentLocalIds: StateFlow<List<String>> = songs.recentLocalIdsFlow().resilient("recent files")
        .stateIn(c.scope, SharingStarted.Lazily, emptyList())

    val playlists = MutableStateFlow<List<BrowseItem>>(emptyList())
    val albums = MutableStateFlow<List<BrowseItem>>(emptyList())
    val artists = MutableStateFlow<List<BrowseItem>>(emptyList())
    private val subscribedArtists = MutableStateFlow<List<BrowseItem>>(
        if (c.settings.current.isLoggedIn) cachedSubscriptions(remoteReactionKey() + ":artists").orEmpty() else emptyList()
    )
    val artistLibraryReady = MutableStateFlow(c.settings.current.isLoggedIn && cachedSubscriptions(remoteReactionKey() + ":artists") != null)
    /** One view of local stars and the account's subscriptions, shared by both profile editors and Wave. */
    val profileArtists: StateFlow<List<FavoriteArtist>> by lazy {
        combine(c.artistPrefs.favorites, subscribedArtists, c.exclusions.artists) { local, remote, blocked ->
            mergeProfileArtists(local, remote, blocked)
        }.stateIn(c.scope, SharingStarted.Eagerly, mergeProfileArtists(c.artistPrefs.favorites.value, subscribedArtists.value, c.exclusions.artists.value))
    }
    val syncState = MutableStateFlow(SyncState.IDLE)
    val lastError = MutableStateFlow<String?>(null)

    private val keptLoad: kotlinx.coroutines.Job

    /**
     * When each library album was first seen here (1.8.4, D56: Albums by "Recently added"). Kept in "library_order"
     * with the sorts; lazy, since the collector below can run before the rest of this class is set up.
     */
    val albumsSeen: AlbumFirstSeen by lazy { AlbumFirstSeen(c.app.getSharedPreferences("library_order", android.content.Context.MODE_PRIVATE)) }

    init {
        // Every list of library albums that lands (the kept copy, a sync, after a save) marks its new ones as seen now.
        c.scope.launch(Dispatchers.IO) { albums.collect { list -> if (list.isNotEmpty()) albumsSeen.note(list.map { it.id }) } }
        // The library as last synced, so it's all there offline (#55) and before the first sync of a launch finishes.
        // A restored copy signs out right after start ([forgetAccount] stops this, so the old account's lists don't
        // land after they were cleared).
        keptLoad = c.scope.launch(Dispatchers.IO) {
            if (!c.settings.current.isLoggedIn) return@launch
            val saved = c.offline.library() ?: return@launch
            synchronized(this@LibraryRepository) {
                if (!isActive) return@launch
                if (playlists.value.isEmpty()) playlists.value = saved.playlists
                if (albums.value.isEmpty()) albums.value = saved.albums
                if (artists.value.isEmpty()) artists.value = saved.artists
            }
        }
    }

    /** Signed out: the account's lists, and its playlists and albums kept for offline, are gone from the phone. */
    fun forgetAccount() {
        synchronized(this) { keptLoad.cancel() }
        synchronized(pending) { pending.clear(); followUps?.cancel() }
        playlists.value = emptyList(); albums.value = emptyList(); artists.value = emptyList(); subscribedArtists.value = emptyList(); artistLibraryReady.value = false
        c.scope.launch(Dispatchers.IO) { c.offline.clear(); c.homeCache.clear() }
    }

    /**
     * Switches Prism to another channel on the session: another Google account, or a brand account of the same one.
     * The last channel's likes (from YouTube; files' likes are Prism's own), library lists, offline copies and Liked
     * songs playlist go, and the new one's are synced. Replay and the phone's own history stay: they're this phone's.
     */
    suspend fun switchAccount(a: com.prism.music.data.model.YtAccount, cookie: String? = null) = withContext(Dispatchers.IO) {
        c.settings.saveIdentity(a.authUser, a.dataSyncId, cookie)
        c.settings.flow.first { it.authUser == a.authUser && it.dataSyncId == a.dataSyncId && (cookie == null || it.cookie == cookie) }
        c.settings.saveAccountInfo(a.name, listOfNotNull(a.byline, a.email).distinct().joinToString(" · "), a.avatar)
        // Before the likes go: the playlist mirror would take them out of the old channel's playlist.
        c.likedPlaylist.forget()
        val kept = c.imports.keptLikes() // imported likes kept in Prism are this phone's, not the channel's
        likedIds.value.filterNot { it.startsWith(Song.LOCAL_PREFIX) || it in kept }.forEach { songs.setLiked(it, false, 0) }
        forgetAccount()
        val artistKey = remoteReactionKey() + ":artists"
        cachedSubscriptions(artistKey)?.let { subscribedArtists.value = it; artistLibraryReady.value = true }
        syncInBackground()
    }

    /** Keeps the library's lists for offline, with their covers. */
    private fun persist() {
        if (playlists.value.isEmpty() && albums.value.isEmpty() && artists.value.isEmpty()) return
        c.offline.saveLibrary(playlists.value, albums.value, artists.value)
        c.scope.launch(Dispatchers.IO) {
            // List-sized (they're shown as tiles and rows), one at a time when the music isn't loading.
            (playlists.value + albums.value).forEach { c.artwork.save(c.covers.art(it), size = 544) }
        }
    }

    /**
     * On Wi-Fi, after a sync: the library's playlists and albums are saved with their songs (each once a day at most,
     * twelve per sync), so they open offline even if they haven't been opened lately.
     */
    private suspend fun keepForOffline() {
        if (!c.network.isOnline() || c.network.metered.value) return
        val day = 24 * 3_600_000L
        val now = System.currentTimeMillis()
        val todo = (playlists.value.filterIsInstance<com.prism.music.data.model.PlaylistItem>().filter { !it.isMix && it.id != "LM" } + albums.value)
            .filter { now - c.offline.savedAt(it.id) > day }
            .take(12)
        for (item in todo) {
            // Never while the music is loading: a whole playlist is many requests, and since 1.6.8 they ran alongside
            // the first songs of every launch (users heard buffering).
            c.quietNetwork()
            if (c.network.metered.value) break
            val page = runCatching {
                if (item is AlbumItem) c.ytm.album(item.id) else c.ytm.playlistAll(item.id, 1000)
            }.getOrNull() ?: continue
            c.offline.savePage(item.id, page)
            kotlinx.coroutines.delay(4_000)
        }
    }

    private data class ReactionRequest(val song: Song, val target: SongReaction, val remoteKey: String?)
    private val reactionLock = Mutex()
    private val remoteReactionLock = Mutex()
    private val remoteReactionWake = Channel<Unit>(Channel.CONFLATED)
    private val reactionRequests = Channel<ReactionRequest>(Channel.UNLIMITED)
    private val pendingReactions = mutableMapOf<String, ReactionRequest>()

    // One ordered local writer. Remote operations are serialized independently, never holding the local lock during I/O.
    private val reactionWriter by lazy {
        c.scope.launch(Dispatchers.IO) {
            for (request in reactionRequests) {
                try {
                    reactionLock.withLock { applyReaction(request.song, request.target, request.remoteKey) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError.value = e.friendlyMessage()
                } finally {
                    synchronized(pendingReactions) {
                        if (pendingReactions[request.song.id] === request) pendingReactions.remove(request.song.id)
                    }
                }
            }
        }
    }

    private fun storedReaction(id: String): SongReaction = when {
        id in dislikedIds.value -> SongReaction.DISLIKE
        id in likedIds.value -> SongReaction.LIKE
        else -> SongReaction.NONE
    }

    private fun desiredReaction(id: String): SongReaction = synchronized(pendingReactions) {
        pendingReactions[id]?.target ?: storedReaction(id)
    }

    fun isDisliked(id: String) = desiredReaction(id) == SongReaction.DISLIKE
    fun isLiked(id: String) = desiredReaction(id) == SongReaction.LIKE

    private fun requestReaction(song: Song, choose: (SongReaction) -> SongReaction): SongReaction =
        synchronized(pendingReactions) {
            val current = pendingReactions[song.id]?.target ?: storedReaction(song.id)
            if (song.isRss) return@synchronized current
            val target = choose(current)
            val request = ReactionRequest(song, target, remoteReactionKey().takeIf { c.settings.current.isLoggedIn })
            pendingReactions[song.id] = request
            reactionWriter
            reactionRequests.trySend(request)
            target
        }

    fun toggleDislike(song: Song): Boolean =
        requestReaction(song, ::reactionAfterDislike) == SongReaction.DISLIKE

    fun setDisliked(song: Song, disliked: Boolean) {
        requestReaction(song) { previous ->
            if (disliked) SongReaction.DISLIKE else if (previous == SongReaction.DISLIKE) SongReaction.NONE else previous
        }
    }

    fun toggleLike(song: Song) { requestReaction(song, ::reactionAfterLike) }

    fun setLiked(song: Song, liked: Boolean) {
        requestReaction(song) { previous ->
            if (liked) SongReaction.LIKE else if (previous == SongReaction.LIKE) SongReaction.NONE else previous
        }
    }

    /** Clear either reaction, including legacy IDs with no metadata. */
    internal fun clearReaction(song: Song) { requestReaction(song) { SongReaction.NONE } }

    /** Bulk selection uses the same writer, preserving order and mutual exclusion. */
    fun setLiked(many: List<Song>, liked: Boolean) {
        many.forEach { setLiked(it, liked) }
    }

    private fun remoteReactionKey(): String {
        val s = c.settings.current
        return "remote_reactions:" + (s.dataSyncId?.takeIf { it.isNotBlank() } ?: "${s.authUser}:${s.accountName}")
    }

    private fun pendingRemote(key: String): Map<String, SongReaction> =
        dislikePrefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { row ->
            val id = row.substringBeforeLast('\t', "")
            val reaction = SongReaction.entries.firstOrNull { it.name == row.substringAfterLast('\t') }
            if (id.isBlank() || reaction == null) null else id to reaction
        }.toMap()

    private fun savePendingRemote(key: String, pending: Map<String, SongReaction>) {
        dislikePrefs.edit().putStringSet(key, pending.map { (id, target) -> "$id\t${target.name}" }.toSet()).commit()
    }

    /** Local state wins until its latest explicit change is acknowledged by the same remote account. */
    private suspend fun sendPendingReaction(key: String, id: String, target: SongReaction): Boolean {
        if (!c.settings.current.isLoggedIn || remoteReactionKey() != key) return false
        return try {
            val sent = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                when (target) {
                    SongReaction.LIKE -> c.ytm.like(id, true)
                    SongReaction.DISLIKE -> c.ytm.dislike(id, true)
                    SongReaction.NONE -> c.ytm.like(id, false)
                }
                true
            } == true
            if (sent) reactionLock.withLock {
                val latest = pendingRemote(key)
                if (latest[id] == target) savePendingRemote(key, latest - id)
            }
            sent
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            lastError.value = tr(R.string.couldn_t_sync_like, e.friendlyMessage())
            false
        }
    }

    private suspend fun applyReaction(song: Song, target: SongReaction, remoteKey: String?) {
        val existing = songs.get(song.id)
        val liked = target == SongReaction.LIKE
        val disliked = target == SongReaction.DISLIKE
        val oldDisliked = song.id in dislikedIds.value
        val wasLiked = existing?.liked == true
        // Write metadata before publishing the exclusion so it can be edited after a restart.
        if (wasLiked != liked || (disliked && existing == null)) {
            songs.upsert(song.toEntity(existing).copy(liked = liked, likedAt = if (liked) System.currentTimeMillis() else 0))
            if (wasLiked != liked) c.lastFm.liked(song, liked)
        }
        if (disliked) {
            val next = dislikedIds.value + song.id
            dislikePrefs.edit().putStringSet("ids", next).commit()
            dislikedIds.value = next
        }
        if (!disliked && oldDisliked) {
            val next = dislikedIds.value - song.id
            dislikePrefs.edit().putStringSet("ids", next).commit()
            dislikedIds.value = next
        }
        if (disliked && c.downloads.downloads.value.containsKey(song.id) && existing?.smartDownload == true) {
            c.downloads.remove(song.id, byListener = false)
        }
        if (target != SongReaction.DISLIKE) c.recFeedback.forgiveTrack(song.id)
        if (song.isLocal) return
        if (liked && !wasLiked && c.settings.current.downloadLikes) c.downloads.download(song)
        if (remoteKey != null) {
            savePendingRemote(remoteKey, pendingRemote(remoteKey) + (song.id to target))
            remoteReactionWake.trySend(Unit)
        }
    }

    suspend fun recordPlayed(song: Song) {
        // The song list doesn't keep what makes one an episode: a feed's would come back as a song, with no way to play.
        if (song.isRss) return
        reactionLock.withLock {
            val existing = songs.get(song.id)
            songs.upsert(song.toEntity(existing).copy(lastPlayed = System.currentTimeMillis()))
        }
    }

    /** Pulls liked songs, playlists, albums and artists from the account. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        if (!c.settings.current.isLoggedIn || syncState.value == SyncState.SYNCING) return@withContext
        syncState.value = SyncState.SYNCING
        try {
            remoteReactionLock.withLock {
            val key = remoteReactionKey()
            val beforeFetch = reactionLock.withLock { pendingRemote(key) }
            for ((id, reaction) in beforeFetch) sendPendingReaction(key, id, reaction)
            val fetched = c.ytm.likedSongs(requireComplete = true)
            reactionLock.withLock {
            val localEdits = beforeFetch + pendingRemote(key)
            val remote = fetched.filterNot { it.id in dislikedIds.value || localEdits[it.id]?.let { reaction -> reaction != SongReaction.LIKE } == true }
            if (remoteReactionKey() != key || !c.settings.current.isLoggedIn) { syncState.value = SyncState.IDLE; return@withContext }
            val before = songs.liked().mapTo(hashSetOf()) { it.id } - dislikedIds.value
            val now = System.currentTimeMillis()
            // Chunked: SQLite caps bound parameters at 999 on older Android versions.
            val existing = remote.map { it.id }.chunked(500).flatMap { songs.getAll(it) }.associateBy { it.id }
            // YouTube's order, newest like first, every time (#99): songs Prism already had kept their first like time,
            // so one liked again (or re-liked in another app) stayed where it was instead of moving to the top.
            songs.upsertAll(remote.mapIndexed { i, s ->
                s.toEntity(existing[s.id]).copy(liked = true, likedAt = now - i)
            })
            if (remote.isNotEmpty()) {
                val remoteIds = remote.map { it.id }.toSet()
                // Local songs' likes are Prism's own, and so are imported likes kept off the account (1.8.1): YouTube
                // doesn't know them, so it can't take them away.
                likesToDrop(before, remoteIds, c.imports.keptLikes() + localEdits.filterValues { it == SongReaction.LIKE }.keys).forEach { songs.setLiked(it, false, 0) }
            }
            if (c.settings.current.downloadLikes && before.isNotEmpty()) {
                remote.filter { it.id !in before }.forEach { c.downloads.download(it) }
            }
            }
            }
            val artistKey = remoteReactionKey() + ":artists"
            val artistEdits = synchronized(artistEditLock) { pendingArtists(artistKey) }
            flushArtistEdits()
            reloadPlaylists()
            runCatching { albums.value = c.ytm.libraryAlbums() }
            runCatching {
                artistSyncLock.withLock {
                val remote = c.ytm.librarySubscriptions(requireComplete = true)
                synchronized(artistEditLock) {
                    if (c.settings.current.isLoggedIn && artistKey == remoteReactionKey() + ":artists") {
                        val edits = artistEdits + pendingArtists(artistKey)
                        val kept = subscribedArtists.value.filter { edits[it.id]?.on == true }
                        subscribedArtists.value = (remote.filter { edits[it.id]?.on != false } + kept).distinctBy { it.id }
                        cacheSubscriptions(artistKey)
                        artistLibraryReady.value = true
                    }
                }
                }
            }
            runCatching {
                val remote = c.ytm.libraryArtists()
                if (c.settings.current.isLoggedIn && artistKey == remoteReactionKey() + ":artists") artists.value = remote
            }
            runCatching {
                c.ytm.accountInfo()?.let { c.settings.saveAccountInfo(it.name, it.email, it.avatar) }
            }
            persist()
            c.scope.launch(Dispatchers.IO) { runCatching { keepForOffline() } }
            syncState.value = SyncState.DONE
            lastError.value = null
            // Likes from YouTube Music are in: bring the Liked songs playlist (if there is one) in line.
            c.likedPlaylist.syncInBackground(full = true)
            // New songs in playlists kept downloaded (#298).
            if (c.settings.current.behavior.keptPlaylists.isNotEmpty()) c.scope.launch(Dispatchers.IO) { runCatching { c.keptPlaylists.syncAll() } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            syncState.value = SyncState.IDLE
            throw e
        } catch (e: Exception) {
            lastError.value = e.friendlyMessage()
            syncState.value = SyncState.ERROR
        }
    }

    private val thumbPrefs = c.app.getSharedPreferences("artist_thumbs", android.content.Context.MODE_PRIVATE)
    /** Artist photos for artists that only appear as names on songs, fetched once and kept. */
    val artistThumbs = MutableStateFlow<Map<String, String>>(thumbPrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
    private val thumbsLoading = java.util.Collections.synchronizedSet(HashSet<String>())

    fun loadArtistThumbs(ids: List<String>) {
        // Each photo costs an artist page; data saver leaves them for Wi-Fi (#40).
        if (c.dataSaving()) return
        val todo = ids.filter { it !in artistThumbs.value && thumbsLoading.add(it) }
        if (todo.isEmpty()) return
        c.scope.launch(Dispatchers.IO) {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                todo.forEach { id ->
                    launch {
                        gate.acquire()
                        try {
                            val page = runCatching { c.ytm.artist(id) }.getOrNull()
                            if (page?.thumbnail != null) keepThumbs(pageNamed(id, page.name) + (id to page.thumbnail))
                        } finally { gate.release() }
                    }
                }
            }
        }
    }

    private fun nameKey(name: String) = ArtistPhotos.nameKey(name)

    /** Kept on the phone and shown at once. */
    @Synchronized private fun keepThumbs(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        thumbPrefs.edit().apply { entries.forEach { (k, v) -> putString(k, v) } }.apply()
        artistThumbs.value = artistThumbs.value + entries
    }

    /**
     * [id]'s page name, kept beside its photo (1.8.8); "" for a page without a title ("Artist"), so that page is known
     * too and not fetched again at every open ([ArtistPhotos.pageEntry]).
     */
    private fun pageNamed(id: String, name: String): Map<String, String> = mapOf(ArtistPhotos.pageEntry(id, name))

    /** An artist's photo, by id, or by name for songs saved without the artist's id or linked to someone else's ([ArtistPhotos]). */
    fun artistPhoto(thumbs: Map<String, String>, id: String?, name: String): String? = ArtistPhotos.photo(thumbs, id, name)

    /**
     * Fetches photos for artists from play history (Replay): by id when the song carried one (unless its page is someone
     * else's, 1.8.8), otherwise by searching for the name. Waits until they're in.
     */
    suspend fun fetchArtistPhotos(artists: List<Pair<String?, String>>) = withContext(Dispatchers.IO) {
        if (c.dataSaving()) return@withContext
        val todo = artists.distinct().filter { (id, name) ->
            val thumbs = artistThumbs.value
            ArtistPhotos.wanted(thumbs, id, name) && thumbsLoading.add(id ?: nameKey(name))
        }
        if (todo.isEmpty()) return@withContext
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            todo.forEach { (id, name) ->
                launch {
                    val key = id ?: nameKey(name)
                    gate.acquire()
                    try {
                        if (id == null) {
                            photoByName(name)?.let { keepThumbs(mapOf(key to it)) }
                        } else {
                            val page = runCatching { c.ytm.artist(id) }.getOrNull()
                            if (page != null) {
                                keepThumbs(pageNamed(id, page.name) + listOfNotNull(page.thumbnail?.let { id to it }))
                                // The song linked the name to another artist's page: the name's own photo, if there is one.
                                if (!ArtistPhotos.couldBe(page.name, name) && artistThumbs.value[nameKey(name)] == null) {
                                    photoByName(name)?.let { keepThumbs(mapOf(nameKey(name) to it)) }
                                }
                            }
                        }
                    } finally {
                        gate.release()
                        // A miss can be tried again next time.
                        thumbsLoading.remove(key)
                    }
                }
            }
        }
    }

    /** "Drake, 21 Savage" is looked up as written, then as its first artist; only an exact name match counts. */
    private suspend fun photoByName(name: String): String? {
        val first = name.split(", ", " & ").first().trim()
        for (n in listOf(name, first).distinct()) {
            val items = runCatching { c.ytm.search(n, com.prism.music.data.innertube.SearchFilter.ARTISTS).items }.getOrNull() ?: continue
            val want = com.prism.music.data.innertube.SearchRank.norm(n)
            items.filterIsInstance<ArtistItem>().firstOrNull { com.prism.music.data.innertube.SearchRank.norm(it.title) == want }
                ?.thumbnail?.let { return it }
        }
        return null
    }

    /**
     * Deletes one of your playlists from YouTube Music; one you saved from someone else is just
     * taken out of the library. Gone from the list straight away, put back if it fails.
     */
    suspend fun deletePlaylist(item: BrowseItem): Result<Unit> = withContext(Dispatchers.IO) {
        val before = playlists.value
        playlists.value = before.filterNot { it.id == item.id }
        // One just made here (#414) isn't put back by the next refresh.
        val wasPending = synchronized(pending) { pending.remove(item.id.removePrefix("VL")) }
        runCatching { c.ytm.deletePlaylist(item.id) }
            .recoverCatching { c.ytm.unsavePlaylist(item.id) }
            .onSuccess {
                // 1.8.8: its saved transitions go with it.
                runCatching { c.transitions.forgetPlaylist(item.id) }
                if (c.likedPlaylist.playlistId.value == item.id) c.likedPlaylist.forget()
                val pins = c.settings.current.home.playlists
                if (pins.any { it.id == item.id }) c.settings.setHomePlaylists(pins.filterNot { it.id == item.id })
            }
            .onFailure {
                playlists.value = before
                if (wasPending != null) synchronized(pending) { pending[item.id.removePrefix("VL")] = wasPending }
            }
    }

    private val orderPrefs = c.app.getSharedPreferences("library_order", android.content.Context.MODE_PRIVATE)

    /** The listener's own order for the Library's playlists (ids, top first); empty until they arrange them. */
    val playlistOrder = MutableStateFlow(orderPrefs.getString("playlists", "")!!.split("\n").filter { it.isNotBlank() })

    /** How the Library's playlists are sorted (a name the Library screen knows), or null for its default. */
    val playlistSort = MutableStateFlow(orderPrefs.getString("playlist_sort", null))

    fun setPlaylistOrder(ids: List<String>) {
        playlistOrder.value = ids
        orderPrefs.edit().putString("playlists", ids.joinToString("\n")).apply()
    }

    fun setPlaylistSort(name: String) {
        playlistSort.value = name
        orderPrefs.edit().putString("playlist_sort", name).apply()
    }

    /**
     * How the listener last sorted a list ("liked", "downloads", "playlist:<id>", "library:songs"…), as a name the
     * screen knows; null for its default. Kept so a sort isn't lost on leaving the page.
     */
    /** Playlists, albums and artists pinned to the top of their Library tab (1.7.6), by id, newest pin first. */
    val pinned = MutableStateFlow(orderPrefs.getString("pinned", "")!!.split("\n").filter { it.isNotBlank() })

    fun setPinned(id: String, on: Boolean) {
        val now = pinned.value.filterNot { it == id }.let { if (on) listOf(id) + it else it }
        pinned.value = now
        orderPrefs.edit().putString("pinned", now.joinToString("\n")).apply()
    }

    fun sortOf(list: String): String? = orderPrefs.getString("sort:$list", null)

    fun setSort(list: String, name: String?) {
        orderPrefs.edit().apply { if (name == null) remove("sort:$list") else putString("sort:$list", name) }.apply()
    }

    fun syncInBackground() {
        c.scope.launch { sync() }
    }

    /**
     * Stars an artist (or takes the star off). Kept on the phone either way; signed in, it also
     * subscribes to them on YouTube Music ([channelId]), which is what YouTube's own app does.
     */
    fun setFavoriteArtist(artist: FavoriteArtist, channelId: String?, on: Boolean) {
        c.artistPrefs.setFavorite(artist.copy(channelId = channelId ?: artist.channelId), on)
        if (on && artists.value.none { it.id == artist.id }) {
            artists.value = listOf(ArtistItem(artist.id, artist.name, "Artist", artist.thumbnail)) + artists.value
        }
        if (c.settings.current.isLoggedIn) {
            val key = remoteReactionKey() + ":artists"
            synchronized(artistEditLock) {
                val item = ArtistItem(artist.id, artist.name, "Artist", artist.thumbnail)
                subscribedArtists.value = (subscribedArtists.value.filterNot { it.id == artist.id } + listOfNotNull(item.takeIf { on }))
                val edits = pendingArtists(key).toMutableMap()
                edits[artist.id] = ArtistEdit(channelId ?: artist.channelId, on)
                saveArtistEdits(key, edits)
                cacheSubscriptions(key)
            }
            c.scope.launch(Dispatchers.IO) { flushArtistEdits() }
        }
    }

    private data class ArtistEdit(val channel: String?, val on: Boolean)
    private val artistEditLock = Any()
    private val artistSyncLock = Mutex()

    private suspend fun flushRemoteReactions() = remoteReactionLock.withLock {
        if (!c.settings.current.isLoggedIn || !c.network.isOnline()) return@withLock
        val key = remoteReactionKey()
        val snapshot = reactionLock.withLock { pendingRemote(key) }
        for ((id, reaction) in snapshot) {
            if (!c.settings.current.isLoggedIn || remoteReactionKey() != key) break
            val latest = reactionLock.withLock { pendingRemote(key)[id] }
            if (latest == reaction) sendPendingReaction(key, id, reaction)
        }
    }

    init {
        c.scope.launch(Dispatchers.IO) {
            for (wake in remoteReactionWake) flushRemoteReactions()
        }
        c.scope.launch(Dispatchers.IO) {
            c.network.online.collect { online ->
                if (online) {
                    flushRemoteReactions()
                    flushArtistEdits()
                }
            }
        }
        // Retry transient server errors even when the network never disconnects.
        c.scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(60_000L)
                flushRemoteReactions()
                flushArtistEdits()
            }
        }
    }

    private fun cachedSubscriptions(key: String): List<BrowseItem>? =
        dislikePrefs.getString("$key:snapshot", null)?.let { raw ->
            runCatching {
                com.prism.music.data.innertube.InnerTube.json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(FavoriteArtist.serializer()), raw
                ).map { ArtistItem(it.id, it.name, "Artist", it.thumbnail) }
            }.getOrNull()
        }

    private fun cacheSubscriptions(key: String) {
        val list = subscribedArtists.value.filterIsInstance<ArtistItem>().map { FavoriteArtist(it.id, it.title, it.thumbnail) }
        dislikePrefs.edit().putString("$key:snapshot", com.prism.music.data.innertube.InnerTube.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(FavoriteArtist.serializer()), list
        )).apply()
    }

    private fun pendingArtists(key: String): Map<String, ArtistEdit> =
        dislikePrefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { row ->
            val parts = row.split('\t')
            if (parts.size != 3 || parts[0].isBlank()) null
            else parts[0] to ArtistEdit(parts[1].ifBlank { null }, parts[2] == "1")
        }.toMap()

    private fun saveArtistEdits(key: String, edits: Map<String, ArtistEdit>) {
        dislikePrefs.edit().putStringSet(key, edits.map { (id, edit) ->
            "$id\t${edit.channel.orEmpty()}\t${if (edit.on) 1 else 0}"
        }.toSet()).commit()
    }

    private suspend fun flushArtistEdits() = artistSyncLock.withLock {
        if (!c.settings.current.isLoggedIn || !c.network.isOnline()) return@withLock
        val key = remoteReactionKey() + ":artists"
        val edits = synchronized(artistEditLock) { pendingArtists(key) }
        for ((id, edit) in edits) {
            if (!c.settings.current.isLoggedIn || key != remoteReactionKey() + ":artists") break
            try {
                val sent = kotlinx.coroutines.withTimeoutOrNull(8000L) {
                    val channel = edit.channel ?: c.ytm.artist(id).channelId ?: return@withTimeoutOrNull false
                    if (key != remoteReactionKey() + ":artists") return@withTimeoutOrNull false
                    c.ytm.subscribe(channel, edit.on)
                    true
                } == true
                if (sent) synchronized(artistEditLock) {
                    val latest = pendingArtists(key)
                    if (latest[id] == edit) saveArtistEdits(key, latest - id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { lastError.value = e.friendlyMessage() }
        }
    }

    fun setArtistExcluded(artist: com.prism.music.data.foryou.Excluded, excluded: Boolean, channelId: String? = null) {
        if (excluded) {
            val favorite = (c.artistPrefs.favorites.value + subscribedArtists.value.filterIsInstance<ArtistItem>().map { FavoriteArtist(it.id, it.title, it.thumbnail) }).firstOrNull { it.id == artist.id || it.name.equals(artist.name, true) }
            if (favorite != null || channelId != null) {
                setFavoriteArtist(favorite ?: FavoriteArtist(artist.id, artist.name, artist.thumbnail), channelId ?: favorite?.channelId, false)
            }
        }
        c.exclusions.setArtist(artist, excluded)
    }

    /**
     * Saves an album or playlist to the account's library, or takes it out. False if YouTube Music refused; an album's
     * other playlist id ([orElse]) is tried before giving up.
     */
    suspend fun setSaved(playlistId: String, saved: Boolean, orElse: String? = null): Boolean = withContext(Dispatchers.IO) {
        listOfNotNull(playlistId, orElse).distinct().any { id ->
            runCatching { c.ytm.setLibrarySaved(id, saved) }.onFailure { android.util.Log.w("PrismLibrary", "library ${if (saved) "save" else "removal"} of $id refused: $it") }.isSuccess
        }.also { ok -> if (ok) refreshCollections() }
    }

    /**
     * Takes an album out of the library. YouTube can open a library album as another edition of it (a remaster, a
     * deluxe), whose playlist id isn't the one the library holds, so un-saving the page left the library's copy there.
     * The library's own entry for [albumId] (with its own playlist id) goes too. False if it's still in the library.
     */
    suspend fun removeAlbum(albumId: String, pagePlaylistId: String?): Boolean = withContext(Dispatchers.IO) {
        val entry = albums.value.filterIsInstance<AlbumItem>().firstOrNull { it.id == albumId }
        val ids = listOfNotNull(pagePlaylistId, entry?.playlistId).distinct()
        if (ids.isEmpty()) return@withContext false
        val ok = ids.map { runCatching { c.ytm.setLibrarySaved(it, false) }.isSuccess }.any { it }
        if (!ok) return@withContext false
        val fresh = runCatching { c.ytm.libraryAlbums() }.getOrNull()
        if (fresh != null) { albums.value = fresh; persist() }
        if (fresh?.none { it.id == albumId } != false) albumsSeen.forget(albumId) // saved again later, it's "recently added" again
        fresh?.none { it.id == albumId } ?: true
    }

    /**
     * When each YouTube playlist was last changed from Prism (songs added), by its id without "VL". An open playlist
     * page, or one kept in a tab's back stack, loads again when it's behind (Reddit, 1.7.9: a song added from the queue
     * "wasn't there", because the playlist page still showed what it had loaded before).
     */
    val playlistEdits = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Songs went into [playlistId]: its page and the Library's playlists (count, cover) are brought up to date. */
    fun playlistChanged(playlistId: String) {
        val id = playlistId.removePrefix("VL")
        playlistEdits.value = playlistEdits.value + (id to System.currentTimeMillis())
        refreshCollections()
    }

    /** Fetches the library's playlists and albums again (after one is saved, created or changed). */
    fun refreshCollections() {
        if (!c.settings.current.isLoggedIn) return
        c.scope.launch(Dispatchers.IO) {
            reloadPlaylists()
            runCatching { albums.value = c.ytm.libraryAlbums() }
            persist()
        }
    }

    /** Where the library's playlists are read from (a test hands in its own). */
    internal var fetchPlaylists: suspend () -> List<BrowseItem> = { c.ytm.libraryPlaylists() }

    /** The library's playlists from YouTube, with the ones just made here that it doesn't list yet ([created]). */
    internal suspend fun reloadPlaylists() {
        runCatching { playlists.value = withPending(fetchPlaylists()) }
    }

    /**
     * Playlists made from Prism that YouTube's library list may not show yet (#414), by id without "VL". YouTube took
     * about two minutes to list a new one, and every refresh until then put back a list without it.
     */
    private val pending = HashMap<String, PendingPlaylist>()
    private var followUps: kotlinx.coroutines.Job? = null

    private fun withPending(fetched: List<BrowseItem>): List<BrowseItem> = synchronized(pending) {
        val merged = mergePending(fetched, pending, System.currentTimeMillis())
        pending.keys.retainAll(merged.still.keys)
        merged.list
    }

    /**
     * A playlist was just made on YouTube Music ([id], named [name], with [songs] songs): it's in Library at once, kept
     * there through refreshes until YouTube lists it (or [PENDING_MS] pass), and the list is asked for again a few times
     * in the next minute so YouTube's own entry (cover, count) takes over as soon as it's there.
     */
    fun created(id: String, name: String, songs: Int = 0) {
        val key = id.removePrefix("VL")
        val item = PlaylistItem(key, name, com.prism.music.data.model.songCount(songs), null)
        synchronized(pending) { pending[key] = PendingPlaylist(item, System.currentTimeMillis()) }
        playlists.value = listOf(item) + playlists.value.filterNot { it.id.removePrefix("VL") == key }
        refreshCollections()
        synchronized(pending) {
            followUps?.cancel()
            followUps = c.scope.launch(Dispatchers.IO) {
                var waited = 0L
                for (at in FOLLOW_UPS_MS) {
                    kotlinx.coroutines.delay(at - waited)
                    waited = at
                    if (synchronized(pending) { pending.isEmpty() } || !c.settings.current.isLoggedIn) break
                    reloadPlaylists()
                    persist()
                }
            }
        }
    }

    companion object {
        /** How long a playlist made here is kept in Library without YouTube listing it. */
        internal const val PENDING_MS = 10 * 60_000L

        /** When Library's playlists are asked for again after one is made (YouTube is slow to list it). */
        private val FOLLOW_UPS_MS = longArrayOf(5_000, 20_000, 60_000)

        internal data class PendingPlaylist(val item: PlaylistItem, val at: Long)
        internal data class PendingMerge(val list: List<BrowseItem>, val still: Map<String, PendingPlaylist>)

        /**
         * [fetched] (the library's playlists as YouTube lists them) with the [pending] ones it doesn't list yet on top,
         * newest first; [PendingMerge.still] is what stays pending: not listed, and made less than [PENDING_MS] ago.
         */
        internal fun mergePending(fetched: List<BrowseItem>, pending: Map<String, PendingPlaylist>, now: Long): PendingMerge {
            val listed = fetched.mapTo(HashSet()) { it.id.removePrefix("VL") }
            val still = pending.filter { (id, p) -> id !in listed && now - p.at <= PENDING_MS }
            val missing = still.values.sortedByDescending { it.at }.map { it.item }
            return PendingMerge(missing + fetched, still)
        }

        /**
         * The likes a sync takes away: liked here before, no longer in the account's liked list, and not Prism's own
         * (local files' likes, and imported likes kept off the account: [kept]).
         */
        internal fun likesToDrop(before: Set<String>, remote: Set<String>, kept: Set<String>): List<String> =
            (before - remote).filterNot { it.startsWith(Song.LOCAL_PREFIX) || it in kept }

        /**
         * [items] in the listener's [order]; ones not placed yet (new playlists) lead, in the
         * order YouTube Music lists them.
         */
        fun <T : BrowseItem> inOrder(items: List<T>, order: List<String>): List<T> {
            if (order.isEmpty()) return items
            val at = order.withIndex().associate { it.value to it.index }
            val (placed, fresh) = items.partition { it.id in at }
            return fresh + placed.sortedBy { at.getValue(it.id) }
        }

        /** Albums your songs come from, most songs first. Fills the Albums tab beyond the ones saved on YouTube Music. */
        fun albumsFrom(songs: List<Song>): List<AlbumItem> = songs
            .filter { it.album?.id != null && !it.isVideo }
            .groupBy { it.album!!.id!! }
            .map { (id, list) ->
                val first = list.first()
                AlbumItem(id, first.album!!.title, first.primaryArtist + " • " + (com.prism.music.data.model.songCount(list.size)), first.thumbnail) to list.size
            }
            .sortedByDescending { it.second }
            .map { it.first }

        /** Artists of your songs, most songs first. */
        fun artistsFrom(songs: List<Song>): List<ArtistItem> = songs
            .flatMap { s -> s.artists.filter { it.id != null }.map { it to s } }
            .groupBy { it.first.id!! }
            .map { (id, pairs) ->
                ArtistItem(id, pairs.first().first.name, com.prism.music.data.model.songCount(pairs.size), null) to pairs.size
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
