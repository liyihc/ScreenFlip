package com.liyihc.screenflipper

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

class DisplayActivity : Activity() {

    private var imageView: ImageView? = null

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
    }

    override fun onDestroy() {
        super.onDestroy()
        imageView?.setImageBitmap(null)
        imageView = null
    }
}
