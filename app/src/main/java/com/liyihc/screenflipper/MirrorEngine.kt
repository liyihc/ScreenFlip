package com.liyihc.screenflipper

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    // 最新帧缓存（应用自有 ByteBuffer，不是 ImageReader 的缓冲）：
    // 监听器每来一帧就把像素拷进来并 close Image——ImageReader 缓冲池保持流动，
    // 生产端（VirtualDisplay surface）才不会因缓冲被占满而卡死（否则缓存永远冻结）。
    // 截图时等一帧"新鲜"画面到达/超时后，直接解码缓存。
    private val imageLock = Any()
    private var cacheBuffer: ByteBuffer? = null
    @Volatile private var cacheArrivalMs = 0L
    @Volatile private var decoding = false

    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MirrorCapture").apply { priority = Thread.MAX_PRIORITY }
    }
    private val captureBusy = AtomicBoolean(false)
    private var captureRequestMs = 0L

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
            try {
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    // 解码期间跳过拷贝（避免覆盖正在解码的快照），但无论如何都 close，
                    // 让缓冲池保持流动，生产端才不会卡死。
                    if (!decoding) synchronized(imageLock) {
                        if (!decoding) {
                            copyToCache(img)
                            cacheArrivalMs = SystemClock.uptimeMillis()
                        }
                    }
                } finally {
                    img.close()
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenFlip", "captureFlipped listener error: ${e.message}")
            }
        }, renderHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenFlip",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }

    // 触发一次截图。
    // minArrivalTimeMs 是工具栏隐藏时刻。取帧分三步：先等 GONE 真正传播到合成器
    // （HIDE_SETTLE_MS，这之前产出的帧仍可能含工具栏），再 resize 强制 SurfaceFlinger
    // 重新合成，最后取"resize 之后"产出的那一帧——它构造上必然不含工具栏。
    fun captureFlipped(minArrivalTimeMs: Long = 0L) {
        android.util.Log.d("ScreenFlip", "captureFlipped called")
        if (!captureBusy.compareAndSet(false, true)) {
            android.util.Log.d("ScreenFlip", "captureFlipped: already busy, ignore")
            return
        }
        captureRequestMs = SystemClock.uptimeMillis()
        captureExecutor.execute { doCapture(minArrivalTimeMs) }
    }

    private fun doCapture(minArrivalTimeMs: Long) {
        try {
            // 1) 等 GONE 传播：WindowManager 在下一个 vsync 的 relayout 遍历里才移除窗口层，
            //    隐藏生效前产出的帧仍含工具栏，一律忽略。
            val settleDeadline =
                maxOf(minArrivalTimeMs, SystemClock.uptimeMillis()) + HIDE_SETTLE_MS
            while (SystemClock.uptimeMillis() < settleDeadline) {
                Thread.sleep(5)
            }

            // 2) 强制重合成：resize 迫使 SurfaceFlinger 重新合成，产出一帧必然不含工具栏的画面。
            forceRecomposite()
            val forceMs = SystemClock.uptimeMillis()

            // 3) 取"resize 之后"产出的那一帧（静默期/瞬态尺寸帧已被 copyToCache 过滤）。
            val waitMs = if (cacheArrivalMs == 0L) FIRST_FRAME_WAIT_MS else RESIZE_FRAME_WAIT_MS
            val deadline = forceMs + waitMs
            while (SystemClock.uptimeMillis() < deadline) {
                if (cacheArrivalMs > forceMs) break
                Thread.sleep(10)
            }

            // 4) 解码交付；无帧可解码时报错（由服务端走失效重授权流程）。
            val bitmap = snapshotAndDecode()
            if (bitmap != null) {
                android.util.Log.d(
                    "ScreenFlip",
                    "LATENCY capture decode ${SystemClock.uptimeMillis() - captureRequestMs}ms"
                )
                callback.onRawFrameReady(bitmap)
            } else {
                android.util.Log.e("ScreenFlip", "captureFlipped: no frame available")
                callback.onCaptureError()
            }
        } catch (e: Exception) {
            decoding = false
            android.util.Log.e("ScreenFlip", "captureFlipped error: ${e.message}")
            callback.onCaptureError()
        } finally {
            captureBusy.set(false)
        }
    }

    private fun snapshotAndDecode(): Bitmap? {
        val snapshot: ByteBuffer
        synchronized(imageLock) {
            if (cacheBuffer == null) return null
            decoding = true
            snapshot = cacheBuffer!!.duplicate()
        }
        val bitmap = decode(snapshot)
        decoding = false
        return bitmap
    }

    // 通过"先缩小一像素再复原"强制虚拟显示器重新合成，产出一帧全新画面。
    // 此时工具栏早已隐藏（HIDE_SETTLE_MS 已过去），该帧必然不含工具栏。
    private fun forceRecomposite() {
        try {
            val vd = virtualDisplay ?: return
            if (height > 1) vd.resize(width, height - 1, dpi)
            vd.resize(width, height, dpi)
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "forceRecomposite error: ${e.message}")
        }
    }

    private fun copyToCache(image: Image) {
        // 跳过虚拟显示器 resize 时产出的瞬态尺寸帧（其宽高与目标不同，拷贝会越界）。
        if (image.width != width || image.height != height) return
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val src = plane.buffer
        val buf = cacheBuffer
        if (buf == null || buf.capacity() != width * height * 4) {
            cacheBuffer = ByteBuffer.allocateDirect(width * height * 4)
        }
        FrameRepacker.repackInto(cacheBuffer!!, width, height, rowStride, pixelStride, src)
    }

    private fun decode(buffer: ByteBuffer): Bitmap? {
        return try {
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            raw.copyPixelsFromBuffer(buffer)
            android.util.Log.d("ScreenFlip", "captureFlipped: raw bitmap built, calling onRawFrameReady")
            raw
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "captureFlipped process error: ${e.message}")
            null
        }
    }

    fun stop() {
        decoding = true
        captureExecutor.shutdownNow()
        synchronized(imageLock) {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
        }
        renderThread?.quitSafely()
        renderThread = null
        mediaProjection = null
    }

    companion object {
        // 隐藏工具栏后等待 GONE 传播到 SurfaceFlinger 的时间（≈2 帧 @60Hz）。
        private const val HIDE_SETTLE_MS = 33L
        // 首帧等待上限（刚启动、缓存里还没有任何帧时）。
        private const val FIRST_FRAME_WAIT_MS = 1500L
        // resize 强制重合成后的取帧等待上限。
        private const val RESIZE_FRAME_WAIT_MS = 200L
    }
}
