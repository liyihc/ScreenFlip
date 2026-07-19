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
import java.util.concurrent.atomic.AtomicBoolean

class MirrorEngine(
    private val callback: Callback
) {

    interface Callback {
        fun onRawFrameReady(bitmap: Bitmap)
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
    private var flipMode = MirrorConfig.FLIP_ROTATE_180
    private val capturePending = AtomicBoolean(false)

    fun setFlipMode(mode: Int) {
        flipMode = mode
    }

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

        // 必须先注册回调再创建 VirtualDisplay，否则某些 Android 版本抛
        // "Must register a callback before starting capture"
        projection.registerCallback(object : MediaProjection.Callback() {}, null)

        imageReader!!.setOnImageAvailableListener({ reader ->
            if (!capturePending.compareAndSet(true, false)) return@setOnImageAvailableListener
            try {
                val image = reader.acquireLatestImage() ?: run {
                    android.util.Log.e("ScreenFlip", "captureFlipped: acquireLatestImage null")
                    renderHandler?.post { callback.onCaptureError() }
                    return@setOnImageAvailableListener
                }
                processImage(image)
            } catch (e: Exception) {
                android.util.Log.e("ScreenFlip", "captureFlipped listener error: ${e.message}")
                renderHandler?.post { callback.onCaptureError() }
            }
        }, renderHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenFlip",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }

    fun captureFlipped() {
        android.util.Log.d("ScreenFlip", "captureFlipped called")
        val reader = imageReader
        if (reader == null) {
            android.util.Log.e("ScreenFlip", "captureFlipped: reader null")
            callback.onCaptureError()
            return
        }
        capturePending.set(true)
        android.util.Log.d("ScreenFlip", "captureFlipped: waiting for next frame")
        // 兜底：若 1.2s 内没有新帧回调，直接尝试取一帧（避免静态画面不产帧）
        renderHandler?.postDelayed({
            if (!capturePending.compareAndSet(true, false)) return@postDelayed
            try {
                val img = reader.acquireLatestImage()
                if (img != null) {
                    android.util.Log.d("ScreenFlip", "captureFlipped: fallback acquired image")
                    processImage(img)
                } else {
                    android.util.Log.e("ScreenFlip", "captureFlipped: fallback null image")
                    callback.onCaptureError()
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenFlip", "captureFlipped fallback error: ${e.message}")
                callback.onCaptureError()
            }
        }, 1200)
    }

    private fun processImage(image: android.media.Image) {
        try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer: ByteBuffer = plane.buffer

            // 每行在源 buffer 中占 rowStride 字节，但 Bitmap 需要紧密打包 (w*4)
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val dst = ByteBuffer.allocateDirect(width * height * 4)
            val rowBytes = ByteArray(rowStride)
            for (sy in 0 until height) {
                buffer.position(sy * rowStride)
                buffer.get(rowBytes)
                for (sx in 0 until width) {
                    val srcOffset = sx * pixelStride
                    val dstOffset = (sy * width + sx) * 4
                    dst.put(dstOffset, rowBytes[srcOffset])
                    dst.put(dstOffset + 1, rowBytes[srcOffset + 1])
                    dst.put(dstOffset + 2, rowBytes[srcOffset + 2])
                    dst.put(dstOffset + 3, rowBytes[srcOffset + 3])
                }
            }
            dst.rewind()
            raw.copyPixelsFromBuffer(dst)
            image.close()
            android.util.Log.d("ScreenFlip", "captureFlipped: raw bitmap built, calling onRawFrameReady")
            callback.onRawFrameReady(raw)
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "captureFlipped process error: ${e.message}")
            try { image.close() } catch (_: Exception) {}
            callback.onCaptureError()
        }
    }

    fun stop() {
        capturePending.set(false)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        renderThread?.quitSafely()
        renderThread = null
        mediaProjection = null
    }
}
