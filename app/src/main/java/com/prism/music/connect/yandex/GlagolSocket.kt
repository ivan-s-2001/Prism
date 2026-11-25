package com.prism.music.connect.yandex

import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
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
    private class Connection {
        val opened = CompletableDeferred<Unit>()
        val replies = ConcurrentHashMap<String, CompletableDeferred<String>>()
        lateinit var socket: WebSocket
        @Volatile var failed = false
    }
    private var connection: Connection? = null
    private var closed = false

    @Synchronized private fun getConnection(): Connection {
        check(!closed) { "Station connection closed" }
        connection?.takeUnless { it.failed }?.let { return it }
        connection?.socket?.cancel()
        require(station.address.isSiteLocalAddress || station.address.isLinkLocalAddress)
        val current = Connection()
        connection = current
        current.socket = client.newWebSocket(
            Request.Builder().url("wss://$host:${station.port}/").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    current.opened.complete(Unit)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(this@GlagolSocket) {
                        if (connection !== current || current.failed || closed) return
                        runCatching {
                            val message = Json.parseToJsonElement(text).jsonObject
                            val id = (message["requestId"] as? JsonPrimitive)?.contentOrNull
                            val status = (message["status"] as? JsonPrimitive)?.contentOrNull
                            if (id != null && status != null) current.replies[id]?.complete(status)
                        }
                        onState(text)
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail(current, t)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    fail(current, IOException("Station socket closing"))
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    fail(current, IOException("Station socket closed"))
                }
            },
        )
        return current
    }

    private fun fail(c: Connection, t: Throwable) {
        c.failed = true
        c.opened.completeExceptionally(t)
        c.replies.values.forEach { it.completeExceptionally(t) }
    }

    private suspend fun ready(): Connection {
        val c = getConnection()
        if (withTimeoutOrNull(8000) { c.opened.await(); true } != true) {
            fail(c, IOException("Station socket open timed out"))
            c.socket.cancel()
            throw IOException("Station socket open timed out")
        }
        if (c.failed) throw IOException("Station socket closed")
        return c
    }

    private fun enqueue(c: Connection, frame: String) {
        if (c.failed || !c.socket.send(frame)) {
            val error = IOException("Station socket closed")
            fail(c, error)
            throw error
        }
    }

    /** Reconnect on the next call, not by replaying an ambiguous command. */
    suspend fun send(conversationToken: String, payload: JsonObject) {
        val c = ready()
        enqueue(c, GlagolProtocol.envelope(conversationToken, payload))
    }

    suspend fun request(conversationToken: String, payload: JsonObject, timeoutMs: Long = 3000L, confirmed: (() -> Boolean)? = null): String? {
        val c = ready()
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<String>()
        c.replies[id] = reply
        try {
            enqueue(c, GlagolProtocol.envelope(conversationToken, payload, id))
            return withTimeoutOrNull(timeoutMs) {
                if (confirmed == null) reply.await()
                else {
                    while (!reply.isCompleted && !confirmed()) delay(25)
                    if (reply.isCompleted) reply.await() else null
                }
            }
        } finally { c.replies.remove(id) }
    }

    @Synchronized override fun close() {
        closed = true
        connection?.let {
            fail(it, IOException("Station connection closed"))
            it.socket.cancel()
        }
        connection = null
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
