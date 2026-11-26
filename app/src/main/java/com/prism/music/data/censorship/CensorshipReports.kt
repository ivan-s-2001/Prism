package com.prism.music.data.censorship

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Reads only public report indexes. No account data, audio URLs or song queries are sent upstream. */
internal class CensorshipReports(private val dir: File, http: OkHttpClient) {
    data class Report(val fck: Boolean, val liberty: Boolean)
    data class Audit(val reports: Map<String, Report>, val linked: Int, val total: Int, val feedsAvailable: Int)
    private data class Feed(val name: String, val url: String, val parse: (String) -> Set<String>)
    private val feeds = listOf(
        Feed("fck", "https://raw.githubusercontent.com/Hazzz895/FckCensorData/main/list_v2.json", CensorshipIndex::fckIds),
        Feed("liberty", "https://raw.githubusercontent.com/gagayhhad-tech/ym-liberty-db/main/list.json", CensorshipIndex::libertyIds),
    )
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private val memory = mutableMapOf<String, Set<String>>()
    private val attempted = mutableMapOf<String, Long>()
    private val ttl = TimeUnit.HOURS.toMillis(24)
    private val retry = TimeUnit.MINUTES.toMillis(15)

    suspend fun reports(sourceIds: Collection<String>): Report? = withContext(Dispatchers.IO) {
        val ids = sourceIds.mapNotNull(CensorshipIndex::yandexId).toSet()
        if (ids.isEmpty()) return@withContext null
        lock.withLock {
            val fck = read(feeds[0]).any { it in ids }
            val liberty = read(feeds[1]).any { it in ids }
            Report(fck, liberty).takeIf { fck || liberty }
        }
    }

    /** One bounded refresh per feed, not one HTTP request per song. */
    suspend fun audit(sourceIds: Map<String, Collection<String>>): Audit = withContext(Dispatchers.IO) {
        val linked = sourceIds.mapValues { (_, ids) -> ids.mapNotNull(CensorshipIndex::yandexId).toSet() }
            .filterValues { it.isNotEmpty() }
        if (linked.isEmpty()) return@withContext Audit(emptyMap(), 0, sourceIds.size, 0)
        lock.withLock {
            val fck = read(feeds[0])
            val liberty = read(feeds[1])
            val hits = linked.mapNotNull { (songId, ids) ->
                val report = Report(ids.any { it in fck }, ids.any { it in liberty })
                if (report.fck || report.liberty) songId to report else null
            }.toMap()
            Audit(hits, linked.size, sourceIds.size, listOf(fck, liberty).count { it.isNotEmpty() })
        }
    }

    private fun read(feed: Feed): Set<String> {
        val now = System.currentTimeMillis()
        val file = File(dir, feed.name + ".ids")
        val atomic = AtomicFile(file)
        val previous = memory[feed.name] ?: runCatching {
            if (file.length() > 1_000_000L) emptySet()
            else atomic.readFully().decodeToString().lineSequence().filter { it.matches(Regex("[0-9]{1,24}")) }.toSet()
        }.getOrDefault(emptySet()).also { memory[feed.name] = it }
        val age = now - file.lastModified()
        if (previous.isNotEmpty() && age in 0 until ttl) return previous
        val lastAttempt = attempted[feed.name]
        if (lastAttempt != null && now - lastAttempt in 0 until retry) return previous
        attempted[feed.name] = now
        return try {
            val next = client.newCall(Request.Builder().url(feed.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Index HTTP " + response.code)
                val source = response.body.source()
                if (source.request(2_000_001L)) throw IOException("Index too large")
                feed.parse(source.readUtf8()).also { require(it.isNotEmpty() && it.size <= 30_000) }
            }
            dir.mkdirs()
            val output = atomic.startWrite()
            try {
                output.write(next.sorted().joinToString("\n").toByteArray())
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
            memory[feed.name] = next
            next
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            previous
        }
    }
}
