package com.liyihc.screenflipper

import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class ToolbarManager(
    private val context: Context,
    private val config: MirrorConfig,
    private val callback: ToolbarCallback
) {

    interface ToolbarCallback {
        fun onStartClicked()
        fun onAutoToggled(enabled: Boolean)
        fun onManualClicked()
        fun onResetClicked()
        fun onExitClicked()
        fun onFlipModeClicked()
        fun onPauseSecondsChanged(seconds: Long)
        fun onCompactToggled()
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var toolbarView: LinearLayout? = null
    private var titleText: TextView? = null
    private var startButton: Button? = null
    private var autoCheckbox: CheckBox? = null
    private var manualButton: Button? = null
    private var pauseInput: EditText? = null
    private var resetButton: Button? = null
    private var exitButton: Button? = null
    private var flipButton: Button? = null
    private var statusText: TextView? = null
    private var attached = false

    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var touchDownTime = 0L
    private var dragHandled = false
    private var compact = false
    private var autoEnabled = false

    fun attach() {
        if (attached) return
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xEE1565C0.toInt())
        }

        val status = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            text = "镜像工具"
            gravity = Gravity.CENTER
        }

        val title = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            text = "ScreenFlip"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        val start = Button(context).apply {
            text = "▶ 开始"
            setOnClickListener { callback.onStartClicked() }
        }
        val auto = CheckBox(context).apply {
            text = "⏱ 自动循环"
            setTextColor(0xFFFFFFFF.toInt())
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != autoEnabled) callback.onAutoToggled(isChecked)
            }
        }
        val manual = Button(context).apply {
            text = "👆 手动"
            visibility = View.GONE
            setOnClickListener { callback.onManualClicked() }
        }
        val pause = EditText(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            hint = "秒"
            setHintTextColor(0xBBFFFFFF.toInt())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            setSingleLine()
            gravity = Gravity.CENTER
            visibility = View.GONE
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = text.toString().toLongOrNull()
                    if (v != null && v > 0) callback.onPauseSecondsChanged(v)
                }
            }
        }
        val reset = Button(context).apply {
            text = "🔄 重新操作"
            visibility = View.GONE
            setOnClickListener { callback.onResetClicked() }
        }
        val flip = Button(context).apply {
            text = "🔁 翻转:旋转180°"
            setOnClickListener { callback.onFlipModeClicked() }
        }
        val exit = Button(context).apply {
            text = "⏹ 退出"
            setOnClickListener { callback.onExitClicked() }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(start)
        layout.addView(auto)
        layout.addView(pause)
        layout.addView(manual)
        layout.addView(reset)
        layout.addView(flip)
        layout.addView(exit)

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.TOP or Gravity.START
            x = config.toolbarX
            y = config.toolbarY
        }

        title.setOnTouchListener { _, event -> onTouch(event, params, layout) }

        try {
            windowManager.addView(layout, params)
            android.util.Log.d("ScreenFlip", "Toolbar attached ok")
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "Toolbar attach failed: ${e.message}")
        }
        toolbarView = layout
        titleText = title
        statusText = status
        startButton = start
        autoCheckbox = auto
        manualButton = manual
        pauseInput = pause
        resetButton = reset
        exitButton = exit
        flipButton = flip
        attached = true
    }

    private fun onTouch(event: MotionEvent, params: WindowManager.LayoutParams, view: View): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchX = event.rawX
                touchY = event.rawY
                touchDownTime = System.currentTimeMillis()
                dragHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchX).toInt()
                val dy = (event.rawY - touchY).toInt()
                if (!dragHandled && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                    dragHandled = true
                }
                if (dragHandled) {
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(view, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dt = System.currentTimeMillis() - touchDownTime
                val dx = (event.rawX - touchX).toInt()
                val dy = (event.rawY - touchY).toInt()
                if (!dragHandled && dt > 500 && kotlin.math.abs(dx) < 10 && kotlin.math.abs(dy) < 10) {
                    callback.onCompactToggled()
                } else if (dragHandled) {
                    config.toolbarX = params.x
                    config.toolbarY = params.y
                }
            }
        }
        return true
    }

    fun setOperating(countdown: Boolean) {
        startButton?.visibility = View.GONE
        autoCheckbox?.visibility = View.GONE
        manualButton?.visibility = View.GONE
        pauseInput?.visibility = View.GONE
        resetButton?.visibility = View.GONE
        exitButton?.visibility = View.VISIBLE
        statusText?.text = if (countdown) "操作中…(自动)" else "操作中…(手动点通知完成)"
    }

    fun setShowing() {
        startButton?.visibility = View.GONE
        autoCheckbox?.visibility = View.GONE
        manualButton?.visibility = View.GONE
        pauseInput?.visibility = View.GONE
        resetButton?.visibility = View.VISIBLE
        exitButton?.visibility = View.VISIBLE
        statusText?.text = "已显示翻转画面"
    }

    fun setWaiting() {
        startButton?.visibility = View.GONE
        autoCheckbox?.visibility = if (compact) View.GONE else View.VISIBLE
        manualButton?.visibility = if (compact) View.VISIBLE else View.VISIBLE
        pauseInput?.visibility = if (compact) View.GONE else View.VISIBLE
        resetButton?.visibility = View.GONE
        exitButton?.visibility = if (compact) View.GONE else View.VISIBLE
        statusText?.text = "镜像工具"
    }

    fun setIdle() {
        startButton?.visibility = View.VISIBLE
        autoCheckbox?.visibility = View.GONE
        manualButton?.visibility = View.GONE
        pauseInput?.visibility = View.GONE
        resetButton?.visibility = View.GONE
        exitButton?.visibility = View.VISIBLE
        statusText?.text = "点击开始"
    }

    fun setAutoEnabled(enabled: Boolean) {
        autoEnabled = enabled
        autoCheckbox?.isChecked = enabled
        if (compact) applyCompactAutoVisual()
    }

    fun setPauseSeconds(seconds: Long) {
        val cur = pauseInput?.text.toString().toLongOrNull()
        if (cur != seconds) pauseInput?.setText(seconds.toString())
    }

    fun setCompact(isCompact: Boolean) {
        compact = isCompact
        rebuildCompactVisibility()
    }

    private fun rebuildCompactVisibility() {
        val fullButtons = listOf(flipButton)
        fullButtons.forEach { it?.visibility = if (compact) View.GONE else View.VISIBLE }
        exitButton?.visibility = if (compact) View.GONE else View.VISIBLE
        pauseInput?.visibility = if (compact) View.GONE else View.VISIBLE
        autoCheckbox?.visibility = if (compact) View.GONE else View.VISIBLE
        manualButton?.visibility = View.VISIBLE
        manualButton?.text = if (compact) "👆" else "👆 手动"
        autoCheckbox?.text = if (compact) "⏱" else "⏱ 自动循环"
        if (compact) applyCompactAutoVisual() else autoCheckbox?.setBackgroundColor(0)
    }

    private fun applyCompactAutoVisual() {
        if (!compact) return
        autoCheckbox?.setBackgroundColor(
            if (autoEnabled) 0x331B66FF.toInt() else 0
        )
    }

    fun setFlipModeLabel(mode: Int) {
        val label = when (mode) {
            MirrorConfig.FLIP_MIRROR -> "🔁 翻转:左右镜像"
            MirrorConfig.FLIP_MIRROR_ROTATE_180 -> "🔁 翻转:镜像+旋转180°"
            else -> "🔁 翻转:旋转180°"
        }
        flipButton?.text = label
    }

    fun hide() {
        toolbarView?.visibility = View.GONE
    }

    fun show() {
        toolbarView?.visibility = View.VISIBLE
    }

    fun detach() {
        toolbarView?.let { windowManager.removeView(it) }
        toolbarView = null
        attached = false
    }

    fun isAttached(): Boolean = attached

    fun isCompact(): Boolean = compact
}
