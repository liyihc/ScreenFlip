package com.liyihc.screenflipper

import android.graphics.Bitmap

object FlipUtils {

    fun applyFlip(source: Bitmap, flipMode: Int): Bitmap {
        if (flipMode == MirrorConfig.FLIP_NONE) {
            // 返回拷贝而非原图：DisplayActivity 显示的 bitmap 必须由它自己持有，
            // 否则 AppState.setRawFrame 会 recycle 掉正在显示的 rawFrame → 崩溃。
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val width = source.width
        val height = source.height
        val src = IntArray(width * height)
        source.getPixels(src, 0, width, 0, 0, width, height)
        val dst = flipPixels(src, width, height, flipMode)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, width, 0, 0, width, height)
        return out
    }

    // 纯数组实现（不依赖 Android），可被 JVM 单元测试直接验证。
    // 各模式的快路径：180°=整数组反转、左右镜像=逐行反转、镜像+180°=反转行序。
    fun flipPixels(src: IntArray, width: Int, height: Int, flipMode: Int): IntArray {
        return when (flipMode) {
            MirrorConfig.FLIP_NONE -> src
            MirrorConfig.FLIP_MIRROR -> mirrorHorizontal(src, width, height)
            MirrorConfig.FLIP_MIRROR_ROTATE_180 -> flipVertical(src, width, height)
            else -> rotate180(src)
        }
    }

    // 左右镜像：反转每一行。
    private fun mirrorHorizontal(src: IntArray, width: Int, height: Int): IntArray {
        val dst = IntArray(src.size)
        var i = 0
        for (y in 0 until height) {
            var x = width - 1
            while (x >= 0) {
                dst[i++] = src[y * width + x]
                x--
            }
        }
        return dst
    }

    // 上下镜像（左右镜像+旋转180 的合成）：反转行的顺序。
    private fun flipVertical(src: IntArray, width: Int, height: Int): IntArray {
        val dst = IntArray(src.size)
        for (y in 0 until height) {
            System.arraycopy(src, y * width, dst, (height - 1 - y) * width, width)
        }
        return dst
    }

    // 旋转180：等价于反转整个数组（对行主序矩形像素网格成立）。
    private fun rotate180(src: IntArray): IntArray {
        val dst = IntArray(src.size)
        var i = 0
        var j = src.size - 1
        while (i < src.size) {
            dst[i++] = src[j--]
        }
        return dst
    }
}
