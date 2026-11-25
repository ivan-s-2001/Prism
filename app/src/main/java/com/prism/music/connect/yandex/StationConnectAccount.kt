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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Experimental Station credentials; never exported, backed up or logged. */
internal object StationConnectAccount {
    private const val ALIAS = "prism.station.account.v1"
    @Volatile private var token: String? = null
    private var storage: AtomicFile? = null
    private val _signedIn = MutableStateFlow(false)
    val signedIn = _signedIn.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (storage != null) return
        val file = AtomicFile(File(context.noBackupFilesDir, "station-account.v1"))
        storage = file
        if (token != null) return
        token = runCatching {
            if (!file.baseFile.exists()) return@runCatching null
            val bytes = file.readFully()
            require(bytes.size in 29..65536)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrElse { file.delete(); null }
        _signedIn.value = token != null
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
        token = accessToken
        _signedIn.value = true
    }

    @Synchronized fun authorizePersistently(context: Context, accessToken: String) {
        require(accessToken.isNotBlank() && accessToken.length <= 16000)
        initialize(context)
        val file = requireNotNull(storage)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        require(cipher.iv.size == 12)
        val bytes = cipher.iv + cipher.doFinal(accessToken.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        authorize(accessToken)
    }

    @Synchronized fun clear() {
        token = null
        storage?.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        _signedIn.value = false
    }

    fun api(): QuasarStationApi? = token?.let { QuasarStationApi(it) }
}
