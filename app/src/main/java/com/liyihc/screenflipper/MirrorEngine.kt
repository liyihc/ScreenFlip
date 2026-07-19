package com.liyihc.screenflipper

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import java.nio.ByteBuffer

class MirrorEngine(
    private val callback: Callback
) {

    interface Callback {
        fun onSnapshotReady(bitmap: Bitmap)
        fun onCaptureError()
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var width = 0
    private var height = 0
    private var dpi = 0

    fun start(
        projection: MediaProjection,
        displayWidth: Int,
        displayHeight: Int,
        displayDpi: Int
    ) {
        this.mediaProjection = projection
        this.width = displayWidth
        this.height = displayHeight
        this.dpi = displayDpi

        imageReader = ImageReader.newInstance(
            width, height, android.graphics.PixelFormat.RGBA_8888, 2
        )
        val surface: Surface = imageReader!!.surface

        renderThread = HandlerThread("MirrorRender").also {
            it.start()
            renderHandler = Handler(it.looper)
        }

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenFlip",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface, null, null
        )
    }

    fun captureFlipped() {
        val reader = imageReader ?: run { callback.onCaptureError(); return }
        renderHandler?.post {
            try {
                val image = reader.acquireLatestImage() ?: run {
                    callback.onCaptureError()
                    return@post
                }
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val buffer: ByteBuffer = plane.buffer
                val src = ByteArray(buffer.remaining())
                buffer.get(src)
                val flipped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                flipped.copyPixelsFromBuffer(
                    ByteBuffer.wrap(flipHorizontally(src, width, height, pixelStride, rowStride))
                )
                image.close()
                callback.onSnapshotReady(flipped)
            } catch (e: Exception) {
                callback.onCaptureError()
            }
        }
    }

    private fun flipHorizontally(
        src: ByteArray,
        w: Int,
        h: Int,
        pixelStride: Int,
        rowStride: Int
    ): ByteArray {
        val dst = ByteArray(src.size)
        for (y in 0 until h) {
            val rowStart = y * rowStride
            for (x in 0 until w) {
                val srcOffset = rowStart + x * pixelStride
                val dstOffset = rowStart + (w - 1 - x) * pixelStride
                dst[dstOffset] = src[srcOffset]
                dst[dstOffset + 1] = src[srcOffset + 1]
                dst[dstOffset + 2] = src[srcOffset + 2]
                dst[dstOffset + 3] = src[srcOffset + 3]
            }
        }
        return dst
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        renderThread?.quitSafely()
        renderThread = null
        mediaProjection = null
    }
}
