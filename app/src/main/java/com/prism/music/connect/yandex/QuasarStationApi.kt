package com.prism.music.connect.yandex

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Quasar requests using an existing authenticated Yandex session.
 * The future account-login layer supplies a domain-scoped cookie jar.
 * No login, cookie extraction, persistence or credential logging is performed here.
 */
internal class QuasarStationApi(cookieJar: CookieJar) : StationTokenSource {
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun verify(station: YandexStation): OwnedStationIdentity {
        val body = get("https://quasar.iot.yandex.ru/glagol/device_list".toHttpUrl())
        return OwnedStationIdentity.fromDeviceList(body, station)
    }

    override suspend fun tokenFor(station: OwnedStationIdentity): String {
        val url = "https://quasar.yandex.ru/glagol/token".toHttpUrl().newBuilder()
            .addQueryParameter("device_id", station.deviceId)
            .addQueryParameter("platform", station.platform)
            .build()
        val result = Json.parseToJsonElement(get(url)).jsonObject
        val token = result["token"]?.jsonPrimitive?.contentOrNull
        if (result["status"]?.jsonPrimitive?.contentOrNull != "ok" || token.isNullOrBlank()) {
            throw IOException("Yandex rejected the device token request")
        }
        return token
    }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Quasar HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty Quasar response")
            val source = body.source()
            if (source.request(2_097_153L)) throw IOException("Quasar response exceeds limit")
            source.readUtf8()
        }
    }
}
