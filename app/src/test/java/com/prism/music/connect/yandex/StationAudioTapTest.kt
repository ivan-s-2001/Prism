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

    @Test fun independentRoomsReceiveSameSamples() {
        val tap = StationAudioTap
        tap.flush(44100, 2, C.ENCODING_PCM_16BIT)
        tap.start(true)
        val first = tap.openReader()
        val second = tap.openReader()
        try {
            val input = ByteBuffer.allocate(44100 * 4 / 2).order(ByteOrder.LITTLE_ENDIAN)
            while (input.hasRemaining()) input.putShort(1200).putShort(-600)
            input.flip()
            tap.handleBuffer(input)
            repeat(4) {
                val a = first.read(3528)
                val b = second.read(3528)
                assertArrayEquals(a, b)
                assertTrue(a.any { it != 0.toByte() })
            }
            first.close()
            assertTrue(second.read(3528).any { it != 0.toByte() })
        } finally {
            first.close()
            second.close()
            tap.stop()
        }
    }

    @Test fun stalledRoomResumesNearLiveEdge() {
        val tap = StationAudioTap
        tap.flush(44100, 2, C.ENCODING_PCM_16BIT)
        tap.start(true)
        val slow = tap.openReader()
        try {
            val input = ByteBuffer.allocate(44100 * 4 * 3).order(ByteOrder.LITTLE_ENDIAN)
            while (input.hasRemaining()) input.putShort(1200).putShort(-600)
            input.flip()
            tap.handleBuffer(input)
            assertTrue(slow.read(3528).any { it != 0.toByte() })
            assertTrue(slow.skippedBytes >= 44100L * 4 * 2)
        } finally {
            slow.close()
            tap.stop()
        }
    }

    @Test fun shortUnderrunKeepsMusicAndAvoidsRebuffer() {
        val tap = StationAudioTap
        tap.flush(44100, 2, C.ENCODING_PCM_16BIT)
        tap.start(true)
        val reader = tap.openReader()
        try {
            val frames = 44100 * 350 / 1000
            val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            while (input.hasRemaining()) input.putShort(1200).putShort(-600)
            input.flip()
            tap.handleBuffer(input)
            val remainingFrames = 882 - 44
            reader.read((frames - remainingFrames) * 4)
            assertFalse(reader.recovering)
            val output = reader.read(3528)
            assertEquals(3528, output.size)
            val samples = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
            while (samples.hasRemaining()) {
                assertEquals(1200, samples.short.toInt())
                assertEquals(-600, samples.short.toInt())
            }
            assertFalse(reader.recovering)
            assertEquals(0L, reader.recoveries)
            assertEquals(176L, reader.underrunBytes)
            assertEquals(-44L, reader.adjustedFrames)
        } finally {
            reader.close()
            tap.stop()
        }
    }

    @Test fun longerUnderrunStillRebuffers() {
        val tap = StationAudioTap
        tap.flush(44100, 2, C.ENCODING_PCM_16BIT)
        tap.start(true)
        val reader = tap.openReader()
        try {
            val frames = 44100 * 350 / 1000
            tap.handleBuffer(ByteBuffer.allocate(frames * 4))
            reader.read((frames - 441) * 4)
            assertFalse(reader.recovering)
            assertEquals(3528, reader.read(3528).size)
            assertTrue(reader.recovering)
            assertEquals(1L, reader.recoveries)
        } finally {
            reader.close()
            tap.stop()
        }
    }
}
