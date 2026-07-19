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

                // 每行在源 buffer 中占 rowStride 字节，但 Bitmap 需要紧密打包 (w*4)
                val flipped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val dst = ByteBuffer.allocateDirect(width * height * 4)
                val rowBytes = ByteArray(rowStride)
                for (y in 0 until height) {
                    buffer.position(y * rowStride)
                    buffer.get(rowBytes)
                    for (x in 0 until width) {
                        val srcOffset = x * pixelStride
                        val dstOffset = ((height - 1 - y) * width + (width - 1 - x)) * 4
                        dst.put(dstOffset, rowBytes[srcOffset])
                        dst.put(dstOffset + 1, rowBytes[srcOffset + 1])
                        dst.put(dstOffset + 2, rowBytes[srcOffset + 2])
                        dst.put(dstOffset + 3, rowBytes[srcOffset + 3])
                    }
                }
                dst.rewind()
                flipped.copyPixelsFromBuffer(dst)
                image.close()
                callback.onSnapshotReady(flipped)
            } catch (e: Exception) {
                callback.onCaptureError()
            }
        }
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
