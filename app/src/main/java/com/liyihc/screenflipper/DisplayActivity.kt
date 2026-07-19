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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        renderFrame(AppState.rawFrame.value, AppState.flipMode.value)

        scope.launch {
            combine(AppState.rawFrame, AppState.flipMode) { frame, mode -> frame to mode }
                .onEach { (frame, mode) -> renderFrame(frame, mode) }
                .collect {}
        }

        view.setOnClickListener { finish() }
        view.isClickable = true
        view.isFocusable = true

        registerReceiver(dismissReceiver, IntentFilter(ACTION_CLOSE), Context.RECEIVER_EXPORTED)
    }

    private fun renderFrame(frame: Bitmap?, flipMode: Int) {
        val view = imageView ?: return
        if (frame == null) {
            if (currentBitmap == null) finish()
            return
        }
        val flipped = FlipUtils.applyFlip(frame, flipMode)
        currentBitmap?.recycle()
        currentBitmap = flipped
        view.setImageBitmap(flipped)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(dismissReceiver) } catch (_: Exception) {}
        AppState.setIsDisplayShowing(false)
        sendBroadcast(Intent(ACTION_DISMISSED))
        currentBitmap?.recycle()
        currentBitmap = null
        imageView?.setImageBitmap(null)
        imageView = null
    }

    companion object {
        const val ACTION_DISMISSED = "com.liyihc.screenflipper.ACTION_DISPLAY_DISMISSED"
        const val ACTION_CLOSE = "com.liyihc.screenflipper.ACTION_DISPLAY_CLOSE"
    }
}
