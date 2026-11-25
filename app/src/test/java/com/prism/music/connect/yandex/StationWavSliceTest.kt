package com.prism.music.connect.yandex

import com.prism.music.connect.PcmBody
import com.prism.music.connect.PcmFrames
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class StationWavSliceTest {
    private fun pcm(onOpen: (Long) -> Unit = {}) = PcmBody(1000, 10000, true) { start ->
        onOpen(start)
        object : PcmFrames {
            var at = start.toInt()
            override fun read(): ShortArray? {
                if (at >= 10000) return null
                val count = minOf(16, 10000 - at)
                return ShortArray(count * 2) { (at + it / 2).toShort() }.also { at += count }
            }
            override fun close() {}
        }
    }

    @Test fun segmentStartsAtCorrectSampleAndAdvertisesRemainingBytes() {
        var opened = -1L
        val slice = pcm { opened = it }.sliceFrom(4000)
        assertEquals(4000L, slice.offsetMs)
        assertEquals(10000L, slice.durationMs)
        assertEquals(44 + 6000 * 4L, slice.body.length)
        val out = ByteArrayOutputStream()
        slice.body.write(0, 47, out)
        val bytes = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24000, bytes.getInt(40))
        assertEquals(4000, bytes.getShort(44).toInt())
        assertEquals(4000L, opened)
        val ranged = ByteArrayOutputStream()
        slice.body.write(44 + 100 * 4L, 44 + 100 * 4L + 3, ranged)
        assertEquals(4100L, opened)
        assertEquals(4100, ByteBuffer.wrap(ranged.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt())
    }

    @Test fun outOfRangeTargetsAreClamped() {
        assertEquals(0L, pcm().sliceFrom(-1).offsetMs)
        assertEquals(9999L, pcm().sliceFrom(Long.MAX_VALUE).offsetMs)
        assertEquals(48L, pcm().sliceFrom(Long.MAX_VALUE).body.length)
    }
}
