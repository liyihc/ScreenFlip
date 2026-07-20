package com.liyihc.screenflipper

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class ToolbarManager(
    private val context: Context,
    private val config: MirrorConfig,
    private val callback: ToolbarCallback
) {

    interface ToolbarCallback {
        fun onStartClicked()
        fun onAutoToggled(enabled: Boolean)
        fun onManualClicked()
        fun onExitClicked()
        fun onFlipModeClicked()
        fun onPauseSecondsChanged(seconds: Long)
        fun onCompactToggled()
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var toolbarView: LinearLayout? = null
    private var titleText: TextView? = null
    private var startButton: Button? = null
    private var fullAutoRow: LinearLayout? = null
    private var autoCheckbox: CheckBox? = null
    private var autoLabelView: TextView? = null
    private var pauseInput: EditText? = null
    private var autoUnit: TextView? = null
    private var manualButton: Button? = null
    private var fullManualRow: LinearLayout? = null
    private var exitButton: Button? = null
    private var flipButton: Button? = null
    private var statusText: TextView? = null
    private var compactRow: LinearLayout? = null
    private var compactAuto: CheckBox? = null
    private var compactManual: Button? = null
    private var attached = false

    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var touchDownTime = 0L
    private var dragHandled = false
    private var longPressFired = false
    private var compact = false
    private var autoEnabled = false
    private var longPressRunnable: Runnable? = null

    fun attach() {
        if (attached) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xEE1565C0.toInt())
        }

        val title = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            text = "ScreenFlip"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        val status = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        val start = Button(context).apply {
            text = "▶ 开始"
            setOnClickListener { callback.onStartClicked() }
        }

        val auto = CheckBox(context).apply {
            text = ""
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != autoEnabled) callback.onAutoToggled(isChecked)
            }
        }
        val autoLabel = TextView(context).apply {
            text = "⏱ 自动循环"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(8, 0, 0, 0)
        }
        val pause = EditText(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            hint = "秒"
            setHintTextColor(0xBBFFFFFF.toInt())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            setSingleLine()
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = text.toString().toLongOrNull()
                    if (v != null && v > 0) callback.onPauseSecondsChanged(v)
                }
            }
        }
        val unit = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            text = "S"
            textSize = 14f
        }
        val fullAuto = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(auto)
            addView(autoLabel)
            addView(pause)
            addView(unit)
        }

        val manual = Button(context).apply {
            text = "👆 手动"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { callback.onManualClicked() }
        }
        val fullManual = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(manual)
        }

        val flip = Button(context).apply {
            text = "🔁 翻转:旋转180°"
            setOnClickListener { callback.onFlipModeClicked() }
        }
        val exit = Button(context).apply {
            text = "⏹ 退出"
            setOnClickListener { callback.onExitClicked() }
        }

        val cAuto = CheckBox(context).apply {
            text = ""
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != autoEnabled) callback.onAutoToggled(isChecked)
            }
        }
        val cAutoLabel = TextView(context).apply {
            text = "⏱"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(8, 0, 0, 0)
        }
        val cManual = Button(context).apply {
            text = "👆"
            setOnClickListener { callback.onManualClicked() }
        }
        val cRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cAuto)
            addView(cAutoLabel)
            addView(cManual)
            visibility = View.GONE
        }

        root.addView(title)
        root.addView(status)
        root.addView(start)
        root.addView(fullAuto)
        root.addView(fullManual)
        root.addView(cRow)
        root.addView(flip)
        root.addView(exit)

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

        title.setOnTouchListener { _, event -> onTouch(event, params, root) }

        try {
            windowManager.addView(root, params)
            android.util.Log.d("ScreenFlip", "Toolbar attached ok")
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "Toolbar attach failed: ${e.message}")
        }
        toolbarView = root
        titleText = title
        statusText = status
        startButton = start
        fullAutoRow = fullAuto
        autoCheckbox = auto
        autoLabelView = autoLabel
        pauseInput = pause
        autoUnit = unit
        manualButton = manual
        fullManualRow = fullManual
        exitButton = exit
        flipButton = flip
        compactRow = cRow
        compactAuto = cAuto
        compactManual = cManual
        attached = true

        scope.launch {
            combine(
                AppState.state,
                AppState.compactMode,
                AppState.autoEnabled,
                AppState.showText,
                AppState.flipMode
            ) { state, compact, autoEnabled, showText, flipMode ->
                UiModel(state, compact, autoEnabled, showText, flipMode)
            }
                .onEach { updateUi(it) }
                .collect {}
        }
    }

    private data class UiModel(
        val state: AppState.State,
        val compact: Boolean,
        val autoEnabled: Boolean,
        val showText: String,
        val flipMode: Int
    )

    private fun updateUi(m: UiModel) {
        compact = m.compact
        autoEnabled = m.autoEnabled

        val s = m.state
        val idle = s == AppState.State.IDLE
        val waiting = s == AppState.State.WAITING
        val operating = s == AppState.State.OPERATING_AUTO ||
            s == AppState.State.OPERATING_MANUAL
        val full = !m.compact

        startButton?.visibility = if (idle) View.VISIBLE else View.GONE

        val rowsVisible = full && (waiting || operating)
        fullAutoRow?.visibility = if (rowsVisible) View.VISIBLE else View.GONE
        fullManualRow?.visibility = if (rowsVisible) View.VISIBLE else View.GONE

        compactRow?.visibility = if (!full && (waiting || operating)) View.VISIBLE else View.GONE

        val showing = s == AppState.State.SHOWING
        flipButton?.visibility = if (full && showing) View.VISIBLE else View.GONE

        exitButton?.visibility = if (full && (waiting || showing)) View.VISIBLE else View.GONE

        manualButton?.text = if (m.compact) "👆" else "👆 手动"

        val highlight = if (m.autoEnabled) 0x331B66FF.toInt() else 0
        autoCheckbox?.setBackgroundColor(highlight)
        compactAuto?.setBackgroundColor(highlight)
        if (autoCheckbox?.isChecked != m.autoEnabled) autoCheckbox?.isChecked = m.autoEnabled
        if (compactAuto?.isChecked != m.autoEnabled) compactAuto?.isChecked = m.autoEnabled

        setFlipModeLabel(m.flipMode)

        if (m.showText.isBlank()) {
            statusText?.visibility = View.GONE
        } else {
            statusText?.text = m.showText
            statusText?.visibility = View.VISIBLE
        }
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
                longPressFired = false
                longPressRunnable = Runnable {
                    longPressFired = true
                    callback.onCompactToggled()
                }
                handler.postDelayed(longPressRunnable!!, 400)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchX).toInt()
                val dy = (event.rawY - touchY).toInt()
                if (!dragHandled && !longPressFired && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                    dragHandled = true
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                }
                if (dragHandled) {
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(view, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                longPressRunnable = null
                if (dragHandled) {
                    config.toolbarX = params.x
                    config.toolbarY = params.y
                }
                dragHandled = false
                longPressFired = false
            }
        }
        return true
    }

    fun setPauseSeconds(seconds: Long) {
        val cur = pauseInput?.text.toString().toLongOrNull()
        if (cur != seconds) pauseInput?.setText(seconds.toString())
    }

    fun setCompact(isCompact: Boolean) {
        AppState.setCompactMode(isCompact)
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
        toolbarView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        toolbarView = null
        attached = false
    }

    fun isAttached(): Boolean = attached

    fun isCompact(): Boolean = AppState.compactMode.value
}
