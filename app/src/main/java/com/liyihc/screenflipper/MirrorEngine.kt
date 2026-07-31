package com.liyihc.screenflipper

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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

    // 快照缓存（应用自有 ByteBuffer，不是 ImageReader 的缓冲）：
    // listener 只在"截图进行中"拷贝像素并 close Image——ImageReader 缓冲池保持流动，
    // 生产端（VirtualDisplay surface）才不会因缓冲被占满而卡死。
    private val imageLock = Any()
    private var cacheBuffer: ByteBuffer? = null
    @Volatile private var cacheArrivalMs = 0L
    @Volatile private var capturePending = false

    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MirrorCapture").apply { priority = Thread.MAX_PRIORITY }
    }
    private val captureBusy = AtomicBoolean(false)
    private var captureRequestMs = 0L
    private var stopped = true

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
        this.stopped = false

        imageReader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2
        )

        renderThread = HandlerThread("MirrorRender").also {
            it.start()
            renderHandler = Handler(it.looper)
        }

        // 必须先注册回调再创建 VirtualDisplay，否则某些 Android 版本抛
        // "Must register a callback before starting capture"
        projection.registerCallback(object : MediaProjection.Callback() {}, null)

        imageReader!!.setOnImageAvailableListener({ reader ->
            // 空闲时不 acquire：积压帧很快占满 ImageReader 缓冲，生产端（SurfaceFlinger）
            // 被阻塞停止产帧 —— 录屏镜像冻结，几乎零耗电。截图时先排空积压再强制重合成。
            if (!capturePending) return@setOnImageAvailableListener
            try {
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    synchronized(imageLock) {
                        if (capturePending) {
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
            imageReader!!.surface, null, null
        )
        // 注意：不要在这里 setSurface(null) —— 这台设备上 detach 后无法恢复产帧。
        // 空闲省电靠 listener 不 acquire 造成的生产端背压（自动冻结镜像）。
    }

    // 触发一次截图。调用方（MirrorService）在 hide 工具栏的同时调用本方法；
    // 引擎排空积压帧解除背压冻结，等 GONE 传播后 resize 强制重合成，取到
    // "工具栏已隐藏"的干净帧后回到空闲（listener 停止 acquire，镜像再次冻结）。
    fun captureFlipped() {
        android.util.Log.d("ScreenFlip", "captureFlipped called")
        if (stopped) return
        if (!captureBusy.compareAndSet(false, true)) {
            android.util.Log.d("ScreenFlip", "captureFlipped: already busy, ignore")
            return
        }
        captureRequestMs = SystemClock.uptimeMillis()
        captureExecutor.execute { doCapture() }
    }

    private fun doCapture() {
        val vd = virtualDisplay
        val reader = imageReader
        if (vd == null || reader == null) {
            android.util.Log.e("ScreenFlip", "captureFlipped: engine not ready")
            callback.onCaptureError()
            captureBusy.set(false)
            return
        }
        try {
            // 1) 排空积压帧，解除生产端阻塞（此刻 listener 仍处于 idle，不会并发 acquire）。
            //    空闲时镜像因背压冻结，积压帧就是"上次截图之后冻结的画面"。
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}

            // 2) 进入截图模式：listener 开始把新帧拷贝进快照缓存。
            synchronized(imageLock) {
                cacheArrivalMs = 0L
            }
            capturePending = true

            // 3) 等 GONE 传播：WindowManager 在下一个 vsync 的 relayout 遍历里才移除
            //    工具栏窗口层，隐藏生效前产出的帧仍含工具栏，一律跳过（resize 帧才取）。
            val settleDeadline = SystemClock.uptimeMillis() + HIDE_SETTLE_MS
            while (SystemClock.uptimeMillis() < settleDeadline) {
                if (!sleepQuietly(5)) break
            }

            // 4) resize 强制 SurfaceFlinger 重新合成：此刻 hide 工具栏已传播到合成器，
            //    resize 后产出的帧构造上必然不含工具栏。
            forceRecomposite(vd)
            val forceMs = SystemClock.uptimeMillis()

            val deadline = forceMs + RESIZE_FRAME_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (cacheArrivalMs > forceMs) break
                if (!sleepQuietly(10)) break
            }

            val snapshot: ByteBuffer
            synchronized(imageLock) {
                if (cacheArrivalMs == 0L) {
                    android.util.Log.e("ScreenFlip", "captureFlipped: no frame available")
                    callback.onCaptureError()
                    return
                }
                snapshot = cacheBuffer!!.duplicate()
                // 取完快照立即退出截图模式：decode 读的是 cacheBuffer 的共享 backing array，
                // 若 listener 继续 repack 会并发写同一块内存导致花屏（旧 decoding 标志同因）。
                capturePending = false
            }
            val bitmap = decode(snapshot)
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
            android.util.Log.e("ScreenFlip", "captureFlipped error: ${e.message}")
            callback.onCaptureError()
        } finally {
            // 回到空闲：listener 不再 acquire，镜像在下次截图前保持背压冻结。
            capturePending = false
            captureBusy.set(false)
        }
    }

    // 通过"先缩小一像素再复原"强制虚拟显示器重新合成，产出一帧全新画面。
    // 此时工具栏早已隐藏，该帧必然不含工具栏。
    private fun forceRecomposite(virtualDisplay: VirtualDisplay) {
        try {
            if (height > 1) virtualDisplay.resize(width, height - 1, dpi)
            virtualDisplay.resize(width, height, dpi)
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

    private fun sleepQuietly(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
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
        stopped = true
        captureExecutor.shutdownNow()
        synchronized(imageLock) {
            capturePending = false
            virtualDisplay?.setSurface(null)
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
        // resize 强制重合成后的取帧等待上限。
        private const val RESIZE_FRAME_WAIT_MS = 200L
    }
}
