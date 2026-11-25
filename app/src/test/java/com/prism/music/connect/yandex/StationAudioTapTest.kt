package com.prism.music.connect.yandex

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class StationAudioTapTest {
    @Test fun tapReadsActualStereoPcmWithoutMutatingInput() {
        val tap = StationAudioTap
        try {
            tap.flush(44100, 2, C.ENCODING_PCM_16BIT)
            tap.start(true)
            val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            input.putShort(1200).putShort(-600).putShort(700).putShort(300).flip()
            tap.handleBuffer(input)
            assertEquals(0, input.position())
            assertArrayEquals(input.array(), tap.read(8))
            assertArrayEquals(ByteArray(8), tap.read(8))
        } finally { tap.stop() }
    }

    @Test fun pausedOrSeekClearsStalePcm() {
        val tap = StationAudioTap
        try {
            tap.flush(44100, 1, C.ENCODING_PCM_16BIT)
            tap.start(true)
            val input = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
            tap.handleBuffer(input)
            assertArrayEquals(byteArrayOf(1, 2, 1, 2), tap.read(4))
            tap.playing(false)
            assertArrayEquals(ByteArray(4), tap.read(4))
            tap.playing(true)
            tap.handleBuffer(input)
            tap.clear()
            assertArrayEquals(ByteArray(8), tap.read(8))
        } finally { tap.stop() }
    }

    @Test fun converts48khzTo441khz() {
        val tap = StationAudioTap
        try {
            tap.flush(48000, 2, C.ENCODING_PCM_16BIT)
            tap.start(true)
            tap.handleBuffer(ByteBuffer.allocate(480 * 4))
            assertEquals(441L * 4, tap.captured)
        } finally { tap.stop() }
    }
}
