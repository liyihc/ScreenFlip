package com.liyihc.screenflipper

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class DisplayActivity : Activity() {

    private var imageView: ImageView? = null
    private var lastRenderedFrame: Bitmap? = null
    private var lastRenderedMode = -1
    private var closeReason: String? = null
    private var displaySeq = -1L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            closeReason = "action_close"
            android.util.Log.d("ScreenFlip", "DisplayActivity close by ACTION_CLOSE broadcast")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("ScreenFlip", "DisplayActivity onCreate")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        val view = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
        }
        imageView = view
        setContentView(view)

        AppState.setIsDisplayShowing(true)
        displaySeq = AppState.currentDisplaySeq()
        // 后台启动探测标记（ADR 0003）：onCreate 真正执行即证明本次 startActivity 未被拦截
        AppState.markDisplayAppeared()

        renderFrame(AppState.rawFrame.value, AppState.flipMode.value)

        scope.launch {
            combine(AppState.rawFrame, AppState.flipMode) { frame, mode -> frame to mode }
                .onEach { (frame, mode) -> renderFrame(frame, mode) }
                .collect {}
        }

        view.setOnClickListener {
            closeReason = "tap"
            android.util.Log.d("ScreenFlip", "DisplayActivity close by user tap")
            sendBroadcast(Intent(ACTION_DISMISSING).putExtra(EXTRA_DISPLAY_SEQ, displaySeq))
            finish()
        }
        view.isClickable = true
        view.isFocusable = true

        registerReceiver(dismissReceiver, IntentFilter(ACTION_CLOSE), Context.RECEIVER_EXPORTED)
    }

    private fun renderFrame(frame: Bitmap?, flipMode: Int) {
        val view = imageView ?: return
        if (frame == null) {
            if (lastRenderedFrame == null) {
                closeReason = "no_frame"
                android.util.Log.d("ScreenFlip", "DisplayActivity close by no frame")
                finish()
            }
            return
        }
        // onCreate 直接渲染一次 + combine 初始发射会再渲染一次；用"同一帧源+同翻转模式"
        // 短路，避免重复设置。bitmap 所有权归 AppState（setRawFrame 负责 recycle），
        // 这里只显示，不做像素操作。
        if (frame === lastRenderedFrame && flipMode == lastRenderedMode) return
        lastRenderedFrame = frame
        lastRenderedMode = flipMode
        view.setImageBitmap(frame)
        applyFlipTransform(view, flipMode)
    }

    // 翻转不做像素拷贝，交给 GPU 硬件加速渲染在合成时完成：
    // 180°=rotation(180)、左右镜像=scaleX(-1)、镜像+180°=上下翻转=scaleY(-1)、无=恒等。
    private fun applyFlipTransform(view: ImageView, flipMode: Int) {
        view.rotation = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        when (flipMode) {
            MirrorConfig.FLIP_ROTATE_180 -> view.rotation = 180f
            MirrorConfig.FLIP_MIRROR -> view.scaleX = -1f
            MirrorConfig.FLIP_MIRROR_ROTATE_180 -> view.scaleY = -1f
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(dismissReceiver) } catch (_: Exception) {}
        android.util.Log.d("ScreenFlip", "DisplayActivity onDestroy reason=${closeReason ?: "unknown"}")
        AppState.setIsDisplayShowing(false)
        sendBroadcast(Intent(ACTION_DISMISSED).putExtra(EXTRA_DISPLAY_SEQ, displaySeq))
        imageView?.setImageBitmap(null)
        imageView = null
    }

    companion object {
        const val ACTION_DISMISSED = "com.liyihc.screenflipper.ACTION_DISPLAY_DISMISSED"
        const val ACTION_DISMISSING = "com.liyihc.screenflipper.ACTION_DISPLAY_DISMISSING"
        const val ACTION_CLOSE = "com.liyihc.screenflipper.ACTION_DISPLAY_CLOSE"
        const val EXTRA_DISPLAY_SEQ = "display_seq"
    }
}
