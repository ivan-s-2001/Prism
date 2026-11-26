package com.prism.music.data

import android.content.Context
import com.prism.music.data.innertube.InnerTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** An artist the listener starred. */
@Serializable
data class FavoriteArtist(val id: String, val name: String, val thumbnail: String? = null, val at: Long = 0, val channelId: String? = null)

/**
 * What the listener chose about artists, kept on this phone: the ones they starred, and the
 * signature style picked for an artist's name (instead of the one Prism would choose).
 */
internal object MusicPreferenceLock

class ArtistPrefs(context: Context, private val account: () -> String? = { null }, private val onFavorite: (FavoriteArtist) -> Unit = {}) {
    private val prefs = context.getSharedPreferences("artist_prefs", Context.MODE_PRIVATE)
    private val listSerializer = ListSerializer(FavoriteArtist.serializer())

    private var owner = account()
    private fun scopedKey() = "favorites_scope:" + (owner ?: "device")
    private fun decode(raw: String?) = raw?.let { runCatching { InnerTube.json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
    private fun loadScoped(): List<FavoriteArtist> {
        if (!prefs.getBoolean("favorites_scoped_v1", false)) {
            val legacy = prefs.getString("favorites", null) ?: "[]"
            check(prefs.edit().putString(scopedKey(), legacy).putBoolean("favorites_scoped_v1", true).commit())
        }
        return decode(prefs.getString(scopedKey(), null))
    }

    val favorites = MutableStateFlow(loadScoped())

    private fun saveFavorites(list: List<FavoriteArtist>) {
        val raw = InnerTube.json.encodeToString(listSerializer, list)
        check(prefs.edit().putString(scopedKey(), raw).putString("favorites", raw).commit())
        favorites.value = list
    }

    /** Keep the original key as a mirror for Bring your Prism and existing backup formats. */
    fun refreshAccount() = synchronized(MusicPreferenceLock) {
        val next = account()
        if (next != owner) {
            owner = next
            saveFavorites(loadScoped())
        }
    }

    /** Artist id -> signature style key. */
    val signatures = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(SIG) }.mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(SIG) to it } }.toMap()
    )

    /** Reads the saved choices again (after "Bring your Prism" merged a phone's into them, 1.8.0). */
    fun reload() = synchronized(MusicPreferenceLock) {
        val imported = decode(prefs.getString("favorites", null))
        refreshAccount()
        saveFavorites(imported)
        signatures.value = prefs.all.filterKeys { it.startsWith(SIG) }.mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(SIG) to it } }.toMap()
        colors.value = prefs.all.filterKeys { it.startsWith(COL) }.mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(COL) to it } }.toMap()
    }

    fun isFavorite(id: String) = favorites.value.any { it.id == id }

    fun setFavorite(artist: FavoriteArtist, on: Boolean) = synchronized(MusicPreferenceLock) {
        refreshAccount()
        if (on) onFavorite(artist)
        val rest = favorites.value.filterNot { it.id == artist.id }
        val next = if (on) listOf(artist.copy(at = System.currentTimeMillis(), channelId = artist.channelId ?: favorites.value.firstOrNull { it.id == artist.id }?.channelId)) + rest else rest
        saveFavorites(next)
    }

    /** Takes [ids] off the starred list in one go. */
    fun unstar(ids: Set<String>) = synchronized(MusicPreferenceLock) {
        refreshAccount()
        if (ids.isEmpty()) return@synchronized
        val next = favorites.value.filterNot { it.id in ids }
        saveFavorites(next)
    }

    /**
     * GitHub #182 (1.7.7.1): bringing the YouTube Music library over starred every artist of every library song (~1,000
     * for one listener), not only the ones the account follows. Once, the stars that came in such a batch are taken off
     * unless [followed] (the account's subscriptions; null when they can't be had, e.g. signed out) has them; stars
     * given by hand stay. False when it has to wait (the subscriptions couldn't be asked); true once done.
     */
    val starsRepaired: Boolean get() = prefs.getBoolean(REPAIRED, false)

    fun repairBroughtStars(followed: Set<String>?, signedIn: Boolean): Boolean {
        if (prefs.getBoolean(REPAIRED, false)) return true
        if (signedIn && followed == null) return false
        val batch = broughtInBatch(favorites.value)
        unstar(batch.map { it.id }.filterTo(HashSet()) { followed == null || it !in followed })
        prefs.edit().putBoolean(REPAIRED, true).apply()
        return true
    }

    /** Null means Prism picks (by genre and audience). */
    fun signature(id: String): String? = signatures.value[id]

    fun setSignature(id: String, key: String?) {
        signatures.value = if (key == null) signatures.value - id else signatures.value + (id to key)
        prefs.edit().apply { if (key == null) remove(SIG + id) else putString(SIG + id, key) }.apply()
    }

    /** Artist id -> their page's color (1.7.2), picked from the page's ⋯ menu; unset = the photo's. */
    val colors = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(COL) }.mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(COL) to it } }.toMap()
    )

    fun setColor(id: String, color: Int?) {
        colors.value = if (color == null) colors.value - id else colors.value + (id to color)
        prefs.edit().apply { if (color == null) remove(COL + id) else putInt(COL + id, color) }.apply()
    }

    companion object {
        private const val SIG = "sig:"
        private const val COL = "col:"
        private const val REPAIRED = "stars_repaired_182"

        /**
         * Stars that arrived as a batch: [MIN_BATCH] or more, each within [BATCH_GAP_MS] of the one before (bringing the
         * library over stars them milliseconds apart; a listener starring by hand never does ten in a row that fast).
         */
        internal fun broughtInBatch(list: List<FavoriteArtist>): List<FavoriteArtist> {
            val sorted = list.filter { it.at > 0 }.sortedBy { it.at }
            val out = ArrayList<FavoriteArtist>()
            var run = ArrayList<FavoriteArtist>()
            for (a in sorted) {
                if (run.isNotEmpty() && a.at - run.last().at > BATCH_GAP_MS) {
                    if (run.size >= MIN_BATCH) out += run
                    run = ArrayList()
                }
                run += a
            }
            if (run.size >= MIN_BATCH) out += run
            return out
        }

        private const val MIN_BATCH = 10
        private const val BATCH_GAP_MS = 5_000L
    }
}
