package com.prism.music.connect.yandex

import org.junit.Assert.*
import org.junit.Test

class StationLinkHealthTest {
    @Test fun fastPollsDoNotDisconnectEarly() {
        val health = StationLinkHealth()
        for (t in 0L until 30_000L step 250L) assertFalse(health.failed(t, false))
        assertTrue(health.failed(30_000L, false))
    }

    @Test fun androidBlockedNetworkDoesNotSpendRecoveryBudget() {
        val health = StationLinkHealth()
        assertFalse(health.failed(0, false))
        assertFalse(health.failed(2000, true))
        assertFalse(health.failed(120000, true))
        assertFalse(health.failed(120001, false))
        assertFalse(health.failed(150000, false))
        assertTrue(health.failed(150001, false))
    }

    @Test fun successfulPollResetsTimer() {
        val health = StationLinkHealth()
        assertFalse(health.failed(0, false))
        assertFalse(health.failed(29000, false))
        health.recovered()
        assertFalse(health.failed(30000, false))
        assertTrue(health.failed(60000, false))
    }
}
