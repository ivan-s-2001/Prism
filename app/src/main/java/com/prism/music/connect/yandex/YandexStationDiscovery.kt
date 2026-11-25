package com.prism.music.connect.yandex

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal data class YandexStation(
    val deviceId: String,
    val platform: String,
    val serviceName: String,
    val address: Inet4Address,
    val port: Int,
)

internal sealed interface StationDiscoveryEvent {
    data class Found(val station: YandexStation) : StationDiscoveryEvent
    data class Lost(val serviceName: String) : StationDiscoveryEvent
}

/** Discovery only; an advertised device is NOT proof of account ownership. */
@Suppress("DEPRECATION")
internal class YandexStationDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun discover(): Flow<StationDiscoveryEvent> = callbackFlow {
        val pending = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val multicast = wifi.createMulticastLock("PrismYandexDiscovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val worker = launch {
            for (candidate in pending) {
                // Legacy NSD supports only one outstanding resolve.
                val service = runCatching { resolve(candidate) }.getOrNull() ?: continue
                val address = service.host as? Inet4Address ?: continue
                if (!address.isSiteLocalAddress && !address.isLinkLocalAddress) continue
                val id = service.attributes["deviceId"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: continue
                val platform = service.attributes["platform"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: continue
                if (service.port !in 1..65535) continue
                trySend(StationDiscoveryEvent.Found(YandexStation(id, platform, service.serviceName, address, service.port)))
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("NSD start failed: $errorCode"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                pending.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                trySend(StationDiscoveryEvent.Lost(serviceInfo.serviceName))
            }
        }
        try {
            nsd.discoverServices(GlagolProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            awaitClose { }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            pending.close()
            worker.cancel()
            if (multicast.isHeld) multicast.release()
        }
    }

    private suspend fun resolve(service: NsdServiceInfo): NsdServiceInfo = suspendCoroutine { continuation ->
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    continuation.resumeWithException(IllegalStateException("NSD resolve failed: $errorCode"))
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    continuation.resume(serviceInfo)
                }
            })
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}
