package com.prism.music.connect.yandex

import org.junit.Assert.*
import org.junit.Test

class LivePacerTest {
    @Test fun pcmPeriodDoesNotAccumulateRoundingError() {
        val p = LivePacer(44100, 0L)
        repeat(10000) { n ->
            assertEquals(20_000_000L, p.advance(882, n * 20_000_000L))
        }
        assertEquals(0L, p.late)
    }

    @Test fun longStallNeverCreatesCatchUpBurst() {
        val p = LivePacer(44100, 0L)
        assertEquals(0L, p.advance(882, 2_000_000_000L))
        assertEquals(1L, p.late)
        assertEquals(20_000_000L, p.advance(882, 2_000_000_000L))
    }

    @Test fun aacFractionalPeriodsStaySampleAccurate() {
        val p = LivePacer(44100, 0L)
        var now = 0L
        repeat(10000) { now += p.advance(1024, now) }
        assertEquals(10000L * 1024L * 1_000_000_000L / 44100, now)
    }
}
