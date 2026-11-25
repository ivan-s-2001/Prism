package com.prism.music.connect.yandex

import java.io.Closeable
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Device-specific TLS transport. The certificate MUST come from authenticated
 * Yandex account metadata matched against the discovered deviceId and platform.
 * Never trust a certificate obtained only from unauthenticated LAN discovery.
 */
internal class GlagolSocket(
    private val station: YandexStation,
    certificatePem: String,
    private val onState: (String) -> Unit = {},
) : Closeable {
    private val expected = CertificateFactory.getInstance("X.509")
        .generateCertificate(certificatePem.byteInputStream()) as X509Certificate
    private val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(expected)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Unexpected client certificate")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val actual = chain?.firstOrNull() ?: throw CertificateException("No peer certificate")
            actual.checkValidity()
            if (!MessageDigest.isEqual(expected.encoded, actual.encoded)) {
                throw CertificateException("Station certificate mismatch")
            }
        }
    }
    private val tls = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trust), SecureRandom())
    }
    private val host = requireNotNull(station.address.hostAddress)
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .sslSocketFactory(tls.socketFactory, trust)
        // The exact account-provided leaf certificate supplies the device identity.
        // A certificate for another station cannot pass the dedicated trust manager.
        .hostnameVerifier { hostname, _ -> hostname == host }
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val opened = CompletableDeferred<Unit>()
    private val socket: WebSocket

    init {
        require(station.address.isSiteLocalAddress || station.address.isLinkLocalAddress)
        socket = client.newWebSocket(
            Request.Builder().url("wss://$host:${station.port}/").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    onState(text)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.completeExceptionally(t)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    opened.completeExceptionally(IllegalStateException("Station disconnected"))
                }
            },
        )
    }

    /** Only queues a frame. A successful return does not prove audible playback. */
    suspend fun send(conversationToken: String, payload: JsonObject) {
        withTimeout(10_000) { opened.await() }
        check(socket.send(GlagolProtocol.envelope(conversationToken, payload))) {
            "Station connection closed"
        }
    }

    override fun close() {
        socket.cancel()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
