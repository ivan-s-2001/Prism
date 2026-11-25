package com.prism.music.connect.yandex

import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class YandexAuthException(message: String) : Exception(message)

internal class YandexDeviceGrant(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val interval: Int,
    val deadline: Long,
)

internal class YandexDeviceAuth {
    // Publicly documented interoperability credentials used by the open-source
    // MarshalX/yandex-music-api device auth and AlexxIT/YandexStation.
    // Not personal account secrets. Experimental; may be revoked by Yandex.
    private val clientId = "23cabbbdc6cd418abb4b39c32c41195d"
    private val clientSecret = "53bc75238f0c4d08a118e51fe9203300"
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun requestCode(): YandexDeviceGrant {
        val result = post("device/code", mapOf(
            "client_id" to clientId,
            "device_id" to UUID.randomUUID().toString().replace("-", "").take(10),
            "device_name" to "Prism Station Lab",
        ))
        val deviceCode = result.string("device_code") ?: throw YandexAuthException("Device code request rejected")
        val userCode = result.string("user_code") ?: throw YandexAuthException("No user code returned")
        val url = (result.string("verification_url") ?: result.string("verification_uri") ?: "https://ya.ru/device").toHttpUrl()
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            !(url.host == "ya.ru" || url.host == "yandex.ru" || url.host.endsWith(".yandex.ru"))) {
            throw YandexAuthException("Unexpected verification URL")
        }
        val expires = result["expires_in"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 3600) ?: 300
        val interval = result["interval"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 60) ?: 5
        return YandexDeviceGrant(deviceCode, userCode, url.toString(), interval,
            SystemClock.elapsedRealtime() + expires * 1000L)
    }

    suspend fun awaitToken(grant: YandexDeviceGrant): String {
        var interval = grant.interval.toLong()
        while (SystemClock.elapsedRealtime() < grant.deadline) {
            delay(interval * 1000L)
            if (SystemClock.elapsedRealtime() >= grant.deadline) break
            val result = post("token", mapOf(
                "grant_type" to "device_code",
                "code" to grant.deviceCode,
                "client_id" to clientId,
                "client_secret" to clientSecret,
            ))
            result.string("access_token")?.let { if (it.isNotBlank()) return it }
            when (result.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> throw YandexAuthException("Authorization denied")
                "expired_token" -> throw YandexAuthException("Device code expired")
                "invalid_client" -> throw YandexAuthException("Yandex rejected OAuth client")
                else -> throw YandexAuthException("OAuth token request rejected")
            }
        }
        throw YandexAuthException("Device code expired")
    }

    private suspend fun post(path: String, fields: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        require(path == "device/code" || path == "token")
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url("https://oauth.yandex.ru/$path").post(body).build()
        http.newCall(request).execute().use { response ->
            val source = response.body?.source() ?: throw YandexAuthException("Empty OAuth response")
            if (source.request(65537L)) throw YandexAuthException("OAuth response too large")
            val parsed = try { Json.parseToJsonElement(source.readUtf8()).jsonObject }
                catch (_: Exception) { throw YandexAuthException("Invalid OAuth response (HTTP ${response.code})") }
            parsed
        }
    }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
}
