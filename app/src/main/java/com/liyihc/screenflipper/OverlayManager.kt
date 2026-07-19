package com.liyihc.screenflipper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: ImageView? = null
    private var attached = false

    fun attach() {
        if (attached) return
        val view = ImageView(context).apply {
            background = ColorDrawable(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = android.graphics.PixelFormat.OPAQUE
            alpha = 1.0f
            flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(view, params)
            android.util.Log.d("ScreenFlip", "Overlay attached ok")
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "Overlay attach failed: ${e.message}")
        }
        overlayView = view
        attached = true
    }

    fun show(bitmap: Bitmap) {
        overlayView?.let { view ->
            view.setImageBitmap(bitmap)
            view.visibility = View.VISIBLE
        }
    }

    fun hide() {
        overlayView?.visibility = View.GONE
    }

    fun detach() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        attached = false
    }

    fun isAttached(): Boolean = attached
}
