package com.liyihc.screenflipper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class FrameRepackerTest {

    @Test
    fun repack_withRowPadding_matchesTightPixels() {
        val width = 10
        val height = 4
        val rowStride = width * 4 + 8 // 模拟行尾 padding
        val buffer = ByteBuffer.allocateDirect(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = y * rowStride + x * 4
                val i = y * width + x
                buffer.put(base, (i and 0xFF).toByte())
                buffer.put(base + 1, 0x11.toByte())
                buffer.put(base + 2, 0x22.toByte())
                buffer.put(base + 3, 0xFF.toByte())
            }
        }
        val packed = FrameRepacker.repack(width, height, rowStride, 4, buffer)
        assertEquals(width * height * 4, packed.remaining())
        val expected = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val o = i * 4
                expected[o] = (i and 0xFF).toByte()
                expected[o + 1] = 0x11
                expected[o + 2] = 0x22
                expected[o + 3] = 0xFF.toByte()
            }
        }
        val actual = ByteArray(width * height * 4)
        packed.get(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun repack_tightRows_identical() {
        val width = 8
        val height = 3
        val rowStride = width * 4
        val buffer = ByteBuffer.allocateDirect(rowStride * height)
        for (i in 0 until width * height * 4) buffer.put(i, (i and 0xFF).toByte())
        val packed = FrameRepacker.repack(width, height, rowStride, 4, buffer)
        val actual = ByteArray(width * height * 4)
        packed.get(actual)
        for (i in actual.indices) {
            assertEquals((i and 0xFF).toByte(), actual[i])
        }
    }

    @Test
    fun perf_1080x2400_withPadding() {
        val width = 1080
        val height = 2400
        val rowStride = 4328 // 每行 4320 字节有效像素 + 8 字节 padding
        val buffer = ByteBuffer.allocateDirect(rowStride * height)
        for (i in 0 until width * 4) buffer.put(i, (i and 0xFF).toByte())
        val t0 = System.nanoTime()
        val packed = FrameRepacker.repack(width, height, rowStride, 4, buffer)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("FrameRepacker perf ${width}x$height rowStride=$rowStride: ${ms}ms")
        assertEquals(width * height * 4, packed.remaining())
        assert(ms < 1500)
    }
}
