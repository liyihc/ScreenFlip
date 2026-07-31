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
    private var currentBitmap: Bitmap? = null
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
            if (currentBitmap == null) {
                closeReason = "no_frame"
                android.util.Log.d("ScreenFlip", "DisplayActivity close by no frame")
                finish()
            }
            return
        }
        // onCreate 直接渲染一次 + combine 初始发射会再渲染一次，且 setRawFrame 可能在
        // 两次之间 recycle 掉帧源；用"同一帧源+同翻转模式"短路，避免 recycle 正在显示的
        // bitmap 后崩溃（Canvas: trying to use a recycled bitmap）。
        if (frame === lastRenderedFrame && flipMode == lastRenderedMode) return
        lastRenderedFrame = frame
        lastRenderedMode = flipMode
        val flipped = FlipUtils.applyFlip(frame, flipMode)
        val old = currentBitmap
        currentBitmap = flipped
        view.setImageBitmap(flipped)
        old?.recycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(dismissReceiver) } catch (_: Exception) {}
        android.util.Log.d("ScreenFlip", "DisplayActivity onDestroy reason=${closeReason ?: "unknown"}")
        AppState.setIsDisplayShowing(false)
        sendBroadcast(Intent(ACTION_DISMISSED).putExtra(EXTRA_DISPLAY_SEQ, displaySeq))
        currentBitmap?.recycle()
        currentBitmap = null
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
