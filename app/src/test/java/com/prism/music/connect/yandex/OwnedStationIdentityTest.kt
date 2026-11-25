package com.prism.music.connect.yandex

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedStationIdentityTest {
    private val station = YandexStation("owned-id", "yandexstation", "Station",
        InetAddress.getByName("192.168.1.42") as Inet4Address, 1961)

    private val response = """
        {"devices":[{"id":"owned-id","platform":"yandexstation","glagol":
            {"security":{"server_certificate":"test-pem","server_private_key":"ignored"}}}]}
    """.trimIndent()

    @Test fun matchesAccountDevice() {
        val result = OwnedStationIdentity.fromDeviceList(response, station)
        assertEquals("owned-id", result.deviceId)
        assertEquals("test-pem", result.certificatePem)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownDevice() {
        OwnedStationIdentity.fromDeviceList(response, station.copy(deviceId = "other"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsPlatformMismatch() {
        OwnedStationIdentity.fromDeviceList(response, station.copy(platform = "other"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingCertificate() {
        OwnedStationIdentity.fromDeviceList(response.replace("test-pem", ""), station)
    }
}
