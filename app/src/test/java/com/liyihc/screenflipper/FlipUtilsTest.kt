package com.liyihc.screenflipper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FlipUtilsTest {

    // 与旧版 FlipUtils.applyFlip 相同的坐标映射，作为正确性参考。
    private fun referenceFlip(src: IntArray, width: Int, height: Int, mode: Int): IntArray {
        val dst = IntArray(width * height)
        for (sy in 0 until height) {
            for (sx in 0 until width) {
                val pixel = src[sy * width + sx]
                val (dx, dy) = when (mode) {
                    1 -> (width - 1 - sx) to sy
                    2 -> sx to (height - 1 - sy)
                    3 -> sx to sy
                    else -> (width - 1 - sx) to (height - 1 - sy)
                }
                dst[dy * width + dx] = pixel
            }
        }
        return dst
    }

    @Test
    fun allModes_matchReferenceOnSmallGrid() {
        val width = 5
        val height = 3
        // 每个像素取自己的索引，便于验证坐标映射
        val src = IntArray(width * height) { it }
        for (mode in 0..3) {
            assertArrayEquals(
                "mode=$mode",
                referenceFlip(src, width, height, mode),
                FlipUtils.flipPixels(src, width, height, mode)
            )
        }
    }

    @Test
    fun nonSquare_grid_matchesReference() {
        val width = 2
        val height = 7
        val src = IntArray(width * height) { it * 3 + 1 }
        for (mode in 0..3) {
            assertArrayEquals(
                "mode=$mode",
                referenceFlip(src, width, height, mode),
                FlipUtils.flipPixels(src, width, height, mode)
            )
        }
    }

    @Test
    fun noneMode_returnsSameArray() {
        val src = IntArray(6) { it * 7 }
        assertSame(src, FlipUtils.flipPixels(src, 3, 2, 3))
    }

    @Test
    fun rotate180_reversesRowMajorOrder() {
        val src = intArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(
            intArrayOf(6, 5, 4, 3, 2, 1),
            FlipUtils.flipPixels(src, 3, 2, 0)
        )
    }

    @Test
    fun perf_1080x2400() {
        val width = 1080
        val height = 2400
        val src = IntArray(width * height) { it xor 0x12345678 }
        for (mode in 0..3) {
            val t0 = System.nanoTime()
            val out = FlipUtils.flipPixels(src, width, height, mode)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("FlipUtils perf mode=$mode ${width}x$height: ${ms}ms")
            assertEquals(src.size, out.size)
            // 宽松阈值：只用于捕获病态 O(n^2) 实现，不做精确基准
            assert(ms < 1500)
        }
    }
}
