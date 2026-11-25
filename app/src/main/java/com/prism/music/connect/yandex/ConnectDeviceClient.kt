package com.prism.music.connect.yandex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Per-installation credential approved by the private Alice skill owner.
 * This is deliberately independent of the local Quasar OAuth credential.
 */
internal object ConnectDeviceClient {
    private const val BASE = "https://glagolskill.duckdns.org"
    private const val ALIAS = "prism.connect.device.v2"
    private val http = OkHttpClient.Builder().followRedirects(false)
        .followSslRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private var file: AtomicFile? = null
    @Volatile private var secret: String? = null
    @Volatile private var paired = false

    class ApiError(val status: Int, val reason: String) : IOException("Connect HTTP $status: $reason")
    class Lease internal constructor(val generation: String, internal val credential: String)

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    @Synchronized private fun initialize(context: Context) {
        if (file != null) return
        val f = AtomicFile(File(context.noBackupFilesDir, "connect-device.v2"))
        file = f
        secret = runCatching {
            if (!f.baseFile.exists()) return@runCatching null
            val b = f.readFully()
            require(b.size == 92)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            cipher.doFinal(b.copyOfRange(12, b.size)).toString(Charsets.UTF_8)
                .takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        }.getOrElse { f.delete(); null }
    }

    @Synchronized private fun createSecret(context: Context): String {
        initialize(context)
        secret?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val value = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        require(cipher.iv.size == 12)
        val f = requireNotNull(file)
        val stream = f.startWrite()
        try {
            stream.write(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            f.finishWrite(stream)
        } catch (e: Exception) { f.failWrite(stream); throw e }
        secret = value
        return value
    }

    private suspend fun post(path: String, credential: String, body: JsonObject = buildJsonObject {}): JsonObject =
        withContext(Dispatchers.IO) {
            require(path in setOf("pair", "status", "claim", "pull", "command", "release", "revoke"))
            val request = Request.Builder().url("$BASE/v2/$path")
                .header("Authorization", "Bearer $credential")
                .post(body.toString().toRequestBody(jsonType)).build()
            http.newCall(request).execute().use { response ->
                val source = response.body?.source() ?: throw IOException("No Connect response")
                if (source.request(65537L)) throw IOException("Connect response too large")
                val result = Json.parseToJsonElement(source.readUtf8()) as? JsonObject
                    ?: throw IOException("Invalid Connect response")
                if (!response.isSuccessful) throw ApiError(response.code,
                    (result["error"] as? JsonPrimitive)?.contentOrNull ?: "unknown")
                result
            }
        }

    suspend fun beginPair(context: Context): String {
        val credential = withContext(Dispatchers.IO) { createSecret(context.applicationContext) }
        val result = post("pair", credential, buildJsonObject {
            put("name", android.os.Build.MODEL.take(60).ifBlank { "Android" })
        })
        return requireNotNull((result["code"] as? JsonPrimitive)?.contentOrNull)
            .also { require(it.matches(Regex("[0-9]{8}"))) }
    }

    suspend fun status(context: Context): JsonObject? {
        withContext(Dispatchers.IO) { initialize(context.applicationContext) }
        val credential = secret ?: return null
        return try {
            post("status", credential).also { if (secret == credential) paired = true }
        } catch (e: ApiError) {
            if (e.status == 401) {
                if (secret == credential) paired = false
                null
            } else throw e
        }
    }

    /** A paired device must claim before it can stream; a never-paired local device keeps legacy mode. */
    suspend fun claim(context: Context): Lease? {
        withContext(Dispatchers.IO) { initialize(context.applicationContext) }
        val credential = secret ?: return null
        // A pending pairing credential is not a host. Never silently bypass a failed paired claim.
        if (status(context) == null) throw IOException("Complete Connect pairing first")
        val result = post("claim", credential)
        return Lease(requireNotNull((result["generation"] as? JsonPrimitive)?.contentOrNull), credential)
    }

    suspend fun pull(lease: Lease, state: JsonObject): JsonObject? =
        post("pull", lease.credential, buildJsonObject {
            put("generation", lease.generation)
            put("state", state)
        })["command"] as? JsonObject

    suspend fun release(lease: Lease) {
        post("release", lease.credential, buildJsonObject {
            put("generation", lease.generation)
            put("stopped", true)
        })
    }

    suspend fun command(generation: String, command: JsonObject) {
        val credential = secret ?: throw IOException("Device not paired")
        post("command", credential, buildJsonObject {
            put("generation", generation)
            put("id", java.util.UUID.randomUUID().toString())
            put("command", command)
        })
    }

    suspend fun revoke(context: Context) {
        withContext(Dispatchers.IO) { initialize(context.applicationContext) }
        val credential = secret
        if (credential != null) {
            try { post("revoke", credential) }
            catch (e: ApiError) { if (e.status != 401) throw e }
        }
        withContext(Dispatchers.IO) {
            synchronized(this@ConnectDeviceClient) {
                secret = null
                paired = false
                file?.delete()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
            }
        }
    }

    fun isPaired(): Boolean = paired
}
