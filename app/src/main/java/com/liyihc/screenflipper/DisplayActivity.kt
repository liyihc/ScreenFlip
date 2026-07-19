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

class DisplayActivity : Activity() {

    private var imageView: ImageView? = null

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

        val bmp = FlipBitmapHolder.bitmap
        if (bmp != null) {
            view.setImageBitmap(bmp)
        } else {
            finish()
        }

        view.setOnClickListener { finish() }
        view.isClickable = true
        view.isFocusable = true

        registerReceiver(dismissReceiver, IntentFilter(ACTION_CLOSE), Context.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(dismissReceiver) } catch (_: Exception) {}
        sendBroadcast(Intent(ACTION_DISMISSED))
        imageView?.setImageBitmap(null)
        imageView = null
    }

    companion object {
        const val ACTION_DISMISSED = "com.liyihc.screenflipper.ACTION_DISPLAY_DISMISSED"
        const val ACTION_CLOSE = "com.liyihc.screenflipper.ACTION_DISPLAY_CLOSE"
    }
}
