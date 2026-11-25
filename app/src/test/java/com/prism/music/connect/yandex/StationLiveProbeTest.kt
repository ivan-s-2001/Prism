package com.prism.music.connect.yandex

import com.prism.music.connect.Wav
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class StationLiveProbeTest {
    @Test fun headerAdvertisesTenMinutesOfStereoPcm() {
        val header = Wav.header(StationLiveProbe.RATE, StationLiveProbe.CHANNELS, StationLiveProbe.DATA_BYTES)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, header.size)
        assertEquals(StationLiveProbe.TOTAL_BYTES - 8, b.getInt(4).toLong())
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(44_100, b.getInt(24))
        assertEquals(176_400, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(105_840_000, b.getInt(40))
    }

    @Test fun sameResourceCanProduceDifferentSignalsAndSilence() {
        val a = StationLiveProbe.pcm(1000, 4096, StationLiveProbe.Signal.A)
        val b = StationLiveProbe.pcm(1000, 4096, StationLiveProbe.Signal.B)
        val silent = StationLiveProbe.pcm(1000, 4096, StationLiveProbe.Signal.SILENCE)
        assertFalse(a.contentEquals(b))
        assertTrue(a.any { it != 0.toByte() })
        assertTrue(b.any { it != 0.toByte() })
        assertTrue(silent.all { it == 0.toByte() })
        val samples = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        while (samples.remaining() >= 4) {
            val left = samples.short.toInt()
            val right = samples.short.toInt()
            assertEquals(left, right)
            assertTrue(kotlin.math.abs(left) <= 900)
        }
    }

    @Test fun splitRangesMaintainCorrectSampleBytes() {
        val signal = StationLiveProbe.Signal.A
        val all = StationLiveProbe.pcm(0, 4096, signal)
        val split = StationLiveProbe.pcm(0, 1533, signal) + StationLiveProbe.pcm(1533, 2563, signal)
        assertArrayEquals(all, split)
    }
}
