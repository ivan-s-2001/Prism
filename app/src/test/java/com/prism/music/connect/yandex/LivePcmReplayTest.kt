package com.prism.music.connect.yandex

import org.junit.Assert.*
import org.junit.Test

class LivePcmReplayTest {
    @Test fun retryDoesNotConsumeOrChangeBytes() {
        val replay = LivePcmReplay(32)
        replay.append(ByteArray(16) { it.toByte() })
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), replay.read(3, 5))
        assertArrayEquals(replay.read(3, 5), replay.read(3, 5))
        assertEquals(16L, replay.end)
        replay.append(ByteArray(16) { (it + 16).toByte() })
        assertArrayEquals(byteArrayOf(14, 15, 16, 17, 18), replay.read(14, 5))
    }

    @Test fun wrapAndEvictionAreExact() {
        val replay = LivePcmReplay(8)
        replay.append(ByteArray(6) { it.toByte() })
        replay.append(ByteArray(6) { (it + 6).toByte() })
        assertEquals(4L, replay.start)
        assertFalse(replay.contains(3))
        assertArrayEquals(ByteArray(8) { (it + 4).toByte() }, replay.read(4, 8))
        assertTrue(replay.contains(12))
    }

    @Test fun repeatedRangeBoundariesDoNotDrift() {
        val replay = LivePcmReplay(64)
        repeat(1000) { i ->
            val offset = replay.end
            val bytes = ByteArray(20) { (i + it).toByte() }
            replay.append(bytes)
            assertArrayEquals(bytes, replay.read(offset, 20))
            assertArrayEquals(bytes.copyOfRange(1, 19), replay.read(offset + 1, 18))
        }
        assertEquals(20000L, replay.end)
    }
}
