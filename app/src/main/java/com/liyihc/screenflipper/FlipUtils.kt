package com.liyihc.screenflipper

import android.graphics.Bitmap

object FlipUtils {

    fun applyFlip(source: Bitmap, flipMode: Int): Bitmap {
        val width = source.width
        val height = source.height
        val flipped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val src = IntArray(width * height)
        source.getPixels(src, 0, width, 0, 0, width, height)
        val dst = IntArray(width * height)
        for (sy in 0 until height) {
            for (sx in 0 until width) {
                val srcPixel = src[sy * width + sx]
                val (dx, dy) = when (flipMode) {
                    MirrorConfig.FLIP_MIRROR ->
                        (width - 1 - sx) to sy
                    MirrorConfig.FLIP_MIRROR_ROTATE_180 ->
                        sx to (height - 1 - sy)
                    else -> // FLIP_ROTATE_180
                        (width - 1 - sx) to (height - 1 - sy)
                }
                dst[dy * width + dx] = srcPixel
            }
        }
        flipped.setPixels(dst, 0, width, 0, 0, width, height)
        return flipped
    }
}
