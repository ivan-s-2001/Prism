package com.prism.music.connect.yandex

/** Debug rollout only: credentials never persisted, exported or logged. */
internal object StationConnectAccount {
    @Volatile private var token: String? = null
    fun authorize(accessToken: String) { require(accessToken.isNotBlank()); token = accessToken }
    fun clear() { token = null }
    fun api(): QuasarStationApi? = token?.let { QuasarStationApi(it) }
}
