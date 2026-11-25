package com.prism.music.connect.yandex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Experimental Station credentials; never exported, backed up or logged. */
internal object StationConnectAccount {
    private const val ALIAS = "prism.station.account.v1"
    @Volatile private var credentials: YandexCredentials? = null
    @Volatile private var generation = 0L
    private val refreshLock = Mutex()
    private var storage: AtomicFile? = null
    private val _signedIn = MutableStateFlow(false)
    val signedIn = _signedIn.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (storage != null) return
        val file = AtomicFile(File(context.noBackupFilesDir, "station-account.v1"))
        storage = file
        if (credentials != null) return
        credentials = runCatching {
            if (!file.baseFile.exists()) return@runCatching null
            val bytes = file.readFully()
            require(bytes.size in 29..65536)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val decoded = cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            if (decoded.startsWith("{")) {
                val json = Json.parseToJsonElement(decoded).jsonObject
                val access = json["access"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("Invalid account")
                YandexCredentials(access, json["refresh"]?.jsonPrimitive?.contentOrNull, json["expires"]?.jsonPrimitive?.longOrNull)
            } else decoded.takeIf { it.isNotBlank() }?.let { YandexCredentials(it, null, null) }
        }.getOrElse { file.delete(); null }
        _signedIn.value = credentials != null
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    /** Legacy debug entry point, memory only. */
    @Synchronized fun authorize(accessToken: String) {
        require(accessToken.isNotBlank())
        generation++
        credentials = YandexCredentials(accessToken, null, null)
        _signedIn.value = true
    }

    @Synchronized fun authorizePersistently(context: Context, value: YandexCredentials) {
        initialize(context)
        persist(value)
        generation++
        credentials = value
        _signedIn.value = true
    }

    private fun persist(value: YandexCredentials) {
        require(value.access.isNotBlank() && value.access.length <= 16000)
        require(value.refresh == null || value.refresh.length <= 16000)
        val file = requireNotNull(storage)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        require(cipher.iv.size == 12)
        val encoded = buildJsonObject {
            put("access", value.access)
            value.refresh?.let { put("refresh", it) }
            value.expiresAt?.let { put("expires", it) }
        }.toString()
        val bytes = cipher.iv + cipher.doFinal(encoded.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun clear() {
        generation++
        credentials = null
        storage?.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        _signedIn.value = false
    }

    fun api(): QuasarStationApi? {
        val session = generation
        if (credentials == null) return null
        return QuasarStationApi { rejected -> accessToken(session, rejected) }
    }

    private suspend fun accessToken(session: Long, rejected: String?): String = refreshLock.withLock {
        val current = credentials?.takeIf { generation == session } ?: throw YandexAuthException("Sign in again", true)
        val due = current.expiresAt?.let { it - System.currentTimeMillis() < 120000 } == true
        if (!due && (rejected == null || rejected != current.access)) return@withLock current.access
        try {
            val renewed = YandexDeviceAuth().refresh(current)
            withContext(Dispatchers.IO) {
                synchronized(this@StationConnectAccount) {
                    if (generation != session || credentials !== current) throw YandexAuthException("Account changed", true)
                    persist(renewed)
                    credentials = renewed
                }
            }
            renewed.access
        } catch (e: YandexAuthException) {
            if (e.reauthorizationRequired) synchronized(this@StationConnectAccount) {
                if (generation == session && credentials === current) clear()
            }
            throw e
        }
    }
}
