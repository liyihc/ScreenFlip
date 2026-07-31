package com.liyihc.screenflipper

import java.nio.ByteBuffer

// 把 ImageReader 的 RGBA_8888 帧按"行紧密打包"重排为 width*height*4 的连续缓冲
// （源行可能带 padding，Bitmap 需要紧密打包）。
// 独立成纯函数，便于在 JVM 单元测试中验证 padding 处理与性能。
object FrameRepacker {

    fun repack(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        buffer: ByteBuffer
    ): ByteBuffer {
        val dst = ByteBuffer.allocateDirect(width * height * 4)
        repackInto(dst, width, height, rowStride, pixelStride, buffer)
        return dst
    }

    fun repackInto(
        dst: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        buffer: ByteBuffer
    ) {
        val tightBytes = width * 4
        dst.rewind()
        if (pixelStride == 4 && rowStride == tightBytes && buffer.limit() >= tightBytes * height) {
            // 无行 padding：一次批量拷贝
            buffer.position(0)
            val slice = buffer.duplicate()
            slice.limit(tightBytes * height)
            dst.put(slice)
        } else if (pixelStride == 4) {
            // 常见情况：每行带 padding，逐行批量拷贝有效像素
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row)
                dst.put(row, 0, tightBytes)
            }
        } else {
            // 通用回退：逐像素拷贝
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row)
                for (x in 0 until width) {
                    val src = x * pixelStride
                    val dstOff = y * tightBytes + x * 4
                    dst.put(dstOff, row[src])
                    dst.put(dstOff + 1, row[src + 1])
                    dst.put(dstOff + 2, row[src + 2])
                    dst.put(dstOff + 3, row[src + 3])
                }
            }
        }
        dst.rewind()
    }
}
