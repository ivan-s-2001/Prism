package com.prism.music.data.foryou

import android.content.Context
import com.prism.music.data.MusicPreferenceLock
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Something left out: an artist (by id where known, and name), a playlist or a song, with what to call it. */
@Serializable
data class Excluded(val id: String, val name: String, val thumbnail: String? = null)

/**
 * What the listener has left out (Settings → Library & Replay → Exclusions):
 * - **Artists not to play**: never in a radio, autoplay, Smart Shuffle, a mix or Smart Downloads (asked for by name,
 *   they still play).
 * - **Playlists and songs left out of your taste**: they play as usual, but their plays don't shape recommendations
 *   (a sleep or kids' playlist, a song played for someone else). Replay still counts them.
 * Kept on the phone (`exclusions` prefs).
 */
class Exclusions(context: Context, private val account: () -> String? = { null }, private val onArtistExcluded: (Excluded) -> Unit = {}) {
    private val prefs = context.getSharedPreferences("exclusions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private var owner = account()
    private fun scopedKey(key: String) = "$key:scope:" + (owner ?: "device")
    private fun decode(raw: String?): List<Excluded> =
        raw?.let { runCatching { json.decodeFromString<List<Excluded>>(it) }.getOrNull() } ?: emptyList()

    init {
        if (!prefs.getBoolean("exclusions_scoped_v1", false)) {
            val edit = prefs.edit()
            for (key in listOf(ARTISTS, PLAYLISTS, SONGS)) edit.putString(scopedKey(key), prefs.getString(key, null) ?: "[]")
            check(edit.putBoolean("exclusions_scoped_v1", true).commit())
        }
    }

    private fun load(key: String) = decode(prefs.getString(scopedKey(key), null))

    private val _artists = MutableStateFlow(load(ARTISTS))
    val artists: StateFlow<List<Excluded>> = _artists
    private val _playlists = MutableStateFlow(load(PLAYLISTS))
    val playlists: StateFlow<List<Excluded>> = _playlists
    private val _songs = MutableStateFlow(load(SONGS))
    val songs: StateFlow<List<Excluded>> = _songs

    /** Reads them again (after "Bring your Prism" merged a phone's in, 1.8.0). */
    fun reload() = synchronized(MusicPreferenceLock) {
        val imported = listOf(ARTISTS, PLAYLISTS, SONGS).associateWith { decode(prefs.getString(it, null)) }
        refreshAccount()
        save(ARTISTS, _artists, imported.getValue(ARTISTS))
        save(PLAYLISTS, _playlists, imported.getValue(PLAYLISTS))
        save(SONGS, _songs, imported.getValue(SONGS))
    }

    fun refreshAccount() = synchronized(MusicPreferenceLock) {
        val next = account()
        if (next != owner) {
            owner = next
            save(ARTISTS, _artists, load(ARTISTS))
            save(PLAYLISTS, _playlists, load(PLAYLISTS))
            save(SONGS, _songs, load(SONGS))
        }
    }

    private fun save(key: String, flow: MutableStateFlow<List<Excluded>>, list: List<Excluded>) {
        val raw = json.encodeToString(list)
        check(prefs.edit().putString(scopedKey(key), raw).putString(key, raw).commit())
        flow.value = list
    }

    fun setArtist(e: Excluded, excluded: Boolean) = synchronized(MusicPreferenceLock) {
        refreshAccount()
        if (excluded) onArtistExcluded(e)
        save(ARTISTS, _artists, _artists.value.filter { !same(it, e) } + listOfNotNull(e.takeIf { excluded }))
    }
    fun setPlaylist(e: Excluded, excluded: Boolean) = synchronized(MusicPreferenceLock) {
        refreshAccount()
        save(PLAYLISTS, _playlists, _playlists.value.filter { it.id != e.id } + listOfNotNull(e.takeIf { excluded }))
    }
    fun setSong(e: Excluded, excluded: Boolean) = synchronized(MusicPreferenceLock) {
        refreshAccount()
        save(SONGS, _songs, _songs.value.filter { it.id != e.id } + listOfNotNull(e.takeIf { excluded }))
    }

    fun isArtistExcluded(id: String?, name: String?): Boolean = _artists.value.any { a ->
        (id != null && a.id == id) || (name != null && a.name.equals(name.trim(), ignoreCase = true))
    }
    fun isPlaylistExcluded(id: String?) = id != null && _playlists.value.any { it.id == id }
    fun isSongExcluded(id: String) = _songs.value.any { it.id == id }

    /** Not to be recommended or played by Prism's own picks: any of its artists is one the listener doesn't want. */
    fun blocks(song: Song): Boolean = song.artists.any { isArtistExcluded(it.id, it.name) }

    /** A play of [song] from the queue started from [originId] that shouldn't count towards taste. */
    fun outOfTaste(song: Song, originId: String?): Boolean = isSongExcluded(song.id) || isPlaylistExcluded(originId) || blocks(song)

    private fun same(a: Excluded, b: Excluded) = a.id == b.id || a.name.equals(b.name, ignoreCase = true)

    private companion object {
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val SONGS = "songs"
    }
}
