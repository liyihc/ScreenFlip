package com.liyihc.screenflipper

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.cancel

// 悬浮工具栏管理器。
// 与旧实现（直接 new 一堆 View 塞进 WindowManager）不同，这里使用 Jetpack Compose
// 渲染界面：把一个 ComposeView 作为 WindowManager 的子 View 挂到屏幕上，
// Compose 的内容通过 collectAsStateWithLifecycle 直接订阅 AppState 的状态流。
// 拖动与长按手势保留在标题栏上（与原生实现一致）：
//  - 拖动超过 10px 视为移动，实时更新窗口坐标并落盘；
//  - 长按 400ms 切换 精简/完整 模式（拖动中不触发长按）。
class ToolbarManager(
    private val context: Context,
    private val config: MirrorConfig,
    private val callback: ToolbarCallback
) {

    // 工具栏对外回调，由 MirrorService 实现并驱动状态机。
    interface ToolbarCallback {
        fun onStartClicked()
        fun onAutoToggled(enabled: Boolean)
        fun onManualClicked()
        fun onExitClicked()
        fun onFlipModeClicked()
        fun onPauseSecondsChanged(seconds: Long)
        fun onCompactToggled()
    }

    // 拖动状态（声明在类成员上，供 TitleBar 的 pointerInput 闭包读写）。
    private var dragX = 0
    private var dragY = 0

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // ComposeView 自身，以及它作为 WindowManager 子 View 的根（两者指向同一个对象）。
    private var composeView: ComposeView? = null
    private var rootView: View? = null
    private var attached = false

    // 配色
    private val accent = Color(0xFF1565C0)
    private val surface = Color(0xEE1565C0)

    // 间距常量（所有页面统一使用）
    private val T_PAD = 4.dp            // 工具栏内边距
    private val T_GAP = 1.dp            // 元素纵向间距
    private val T_ROW_GAP = 2.dp        // 完整模式行内横向间距
    private val C_ROW_GAP = 4.dp        // 精简模式行内横向间距
    private val C_PAD_H = 8.dp          // 精简模式按钮横向内边距
    private val C_PAD_V = 2.dp          // 精简模式按钮纵向内边距

    fun attach() {
        if (attached) return
        val compose = ComposeView(context)
        compose.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // ComposeView 挂在 WindowManager 上，没有宿主 LifecycleOwner，
        // 必须手动注入一个，否则 onAttachedToWindow 时会抛 ViewTreeLifecycleOwner not found。
        val owner = ToolbarLifecycleOwner()
        compose.setViewTreeLifecycleOwner(owner)
        compose.setViewTreeSavedStateRegistryOwner(owner)
        owner.performResume()
        compose.setContent {
            MaterialTheme {
                ToolbarRoot()
            }
        }

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.TOP or Gravity.START
            x = config.toolbarX
            y = config.toolbarY
        }

        dragX = config.toolbarX
        dragY = config.toolbarY

        try {
            windowManager.addView(compose, params)
            android.util.Log.d("ScreenFlip", "Toolbar attached ok")
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "Toolbar attach failed: ${e.message}")
        }
        composeView = compose
        rootView = compose
        attached = true
    }

    // 工具栏根：根据 compact 状态在完整布局与精简布局之间切换。
    @Composable
    private fun ToolbarRoot() {
        val state by AppState.state.collectAsStateWithLifecycle()
        val compact by AppState.compactMode.collectAsStateWithLifecycle()
        val autoEnabled by AppState.autoEnabled.collectAsStateWithLifecycle()
        val showText by AppState.showText.collectAsStateWithLifecycle()
        val flipMode by AppState.flipMode.collectAsStateWithLifecycle()
        val countdown by AppState.countdownSeconds.collectAsStateWithLifecycle()

        // 任意订阅的状态变化导致重组时打点。
        LaunchedEffect(state, compact, autoEnabled, showText, flipMode, countdown) {
            android.util.Log.d(
                "ScreenFlip",
                "Toolbar recompose: state=$state compact=$compact auto=$autoEnabled showText='$showText' flipMode=$flipMode countdown=$countdown"
            )
        }

        Box(
            modifier = Modifier
                .wrapContentWidth()
                .background(surface, RoundedCornerShape(8.dp))
                .padding(T_PAD)
        ) {
            if (compact) {
                CompactLayout(
                    state = state,
                    autoEnabled = autoEnabled,
                    showText = showText,
                    flipMode = flipMode,
                    countdown = countdown,
                    onStart = callback::onStartClicked,
                    onAuto = callback::onAutoToggled,
                    onManual = callback::onManualClicked,
                    onFlip = callback::onFlipModeClicked
                )
            } else {
                FullLayout(
                    state = state,
                    autoEnabled = autoEnabled,
                    showText = showText,
                    flipMode = flipMode,
                    onStart = callback::onStartClicked,
                    onAuto = callback::onAutoToggled,
                    onManual = callback::onManualClicked,
                    onExit = callback::onExitClicked,
                    onFlip = callback::onFlipModeClicked
                )
            }
        }
    }

    // 拖动辅助（pointerInteropFilter 回调内使用）。
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWinX = 0
    private var dragStartWinY = 0
    private var dragHandled = false
    private var longPressFired = false
    private var longPressRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    // 标题栏：承载拖动与长按手势，并显示应用名。
    // 使用 pointerInteropFilter 直接处理 Android 原生触摸事件：
    //  - 拖动超过 10px 即移动窗口，同时取消长按定时器；
    //  - 按住不动 400ms 触发长按，切换精简/完整模式。
    @Composable
    private fun TitleBar() {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInteropFilter { event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            dragStartRawX = event.rawX
                            dragStartRawY = event.rawY
                            dragStartWinX = dragX
                            dragStartWinY = dragY
                            dragHandled = false
                            longPressFired = false
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            val r = Runnable {
                                longPressFired = true
                                callback.onCompactToggled()
                            }
                            longPressRunnable = r
                            handler.postDelayed(r, 400L)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - dragStartRawX).toInt()
                            val dy = (event.rawY - dragStartRawY).toInt()
                            if (!dragHandled && !longPressFired && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                                dragHandled = true
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null
                            }
                            if (dragHandled) {
                                dragX = dragStartWinX + dx
                                dragY = dragStartWinY + dy
                                updateWindowPosition(dragX, dragY)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            if (dragHandled) {
                                config.toolbarX = dragX
                                config.toolbarY = dragY
                            }
                            dragHandled = false
                            longPressFired = false
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            dragX = config.toolbarX
                            dragY = config.toolbarY
                            dragHandled = false
                            longPressFired = false
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Text(
                text = "ScreenFlip",
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }

    // 完整模式布局：开始/自动循环/手动/翻转/退出 全部展开。
    @Composable
    private fun FullLayout(
        state: AppState.State,
        autoEnabled: Boolean,
        showText: String,
        flipMode: Int,
        onStart: () -> Unit,
        onAuto: (Boolean) -> Unit,
        onManual: () -> Unit,
        onExit: () -> Unit,
        onFlip: () -> Unit
    ) {
        val idle = state == AppState.State.IDLE
        val waiting = state == AppState.State.WAITING
        val showing = state == AppState.State.SHOWING

        Column(
            modifier = Modifier.width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(T_GAP)
        ) {
            TitleBar()

            if (showText.isNotBlank()) {
                Text(text = showText, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, softWrap = false)
            }

            if (idle) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.wrapContentWidth()
                ) { Text(context.getString(R.string.start_button), maxLines = 1) }
            }

            if (waiting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(T_ROW_GAP)
                ) {
                    Checkbox(checked = autoEnabled, onCheckedChange = onAuto)
                    Text(context.getString(R.string.auto_label), color = Color.White, fontSize = 14.sp, softWrap = false)
                    PauseInput()
                    Text(context.getString(R.string.pause_unit), color = Color.White, fontSize = 14.sp)
                }
                Button(
                    onClick = onManual,
                    modifier = Modifier.wrapContentWidth()
                ) { Text(context.getString(R.string.manual_button), maxLines = 1) }
            }

            if (showing) {
                Button(
                    onClick = onFlip,
                    modifier = Modifier.wrapContentWidth()
                ) { Text(flipLabel(flipMode), maxLines = 1) }
            }

            if (true) {
                Button(
                    onClick = onExit,
                    modifier = Modifier.wrapContentWidth()
                ) { Text(context.getString(R.string.exit_button), maxLines = 1) }
            }
        }
    }

    // 精简模式布局：仅保留图标与复选框，节省屏幕空间。
    @Composable
    private fun CompactLayout(
        state: AppState.State,
        autoEnabled: Boolean,
        showText: String,
        flipMode: Int,
        countdown: Long,
        onStart: () -> Unit,
        onAuto: (Boolean) -> Unit,
        onManual: () -> Unit,
        onFlip: () -> Unit
    ) {
        val idle = state == AppState.State.IDLE
        val waiting = state == AppState.State.WAITING
        val showing = state == AppState.State.SHOWING

        Column(
            modifier = Modifier.width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(T_GAP)
        ) {
            TitleBar()

            if (showText.isNotBlank()) {
                Text(text = showText, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, softWrap = false)
            }

            if (idle) {
                Text(
                    "\u25B6",
                    color = Color.White,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent)
                        .padding(C_PAD_H)
                        .clickable(onClick = onStart)
                )
            }

            if (waiting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(C_ROW_GAP)
                ) {
                    // 自动开启且处于倒计时时，复选框旁显示剩余秒数。
                    val autoText = if (autoEnabled && countdown >= 0) "${countdown}\u23F1" else "\u23F1"
                    Checkbox(checked = autoEnabled, onCheckedChange = onAuto)
                    Text(
                        autoText,
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier.clickable(onClick = { onAuto(!autoEnabled) })
                    )
                    Text(
                        "\uD83D\uDC46",
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(accent)
                            .padding(horizontal = C_PAD_H, vertical = C_PAD_V)
                            .clickable(onClick = onManual)
                    )
                }
            }

            if (showing) {
                Text(
                    "\uD83D\uDD01",
                    color = Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent)
                        .padding(horizontal = C_PAD_H, vertical = C_PAD_V)
                        .clickable(onClick = onFlip)
                )
            }
        }
    }

    // 暂停时长输入框：用 AndroidView 承载原生 EditText（需要软键盘输入与焦点）。
    @Composable
    private fun PauseInput() {
        var text by remember { mutableStateOf((config.pauseDuration / 1000).toString()) }
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    setTextColor(Color.White.toArgb())
                    setHintTextColor(Color(0xBBFFFFFF).toArgb())
                    hint = context.getString(R.string.pause_unit)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    textSize = 14f
                    setSingleLine()
                    gravity = Gravity.CENTER
                    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) {
                            val v = text.toString().toLongOrNull()
                            if (v != null && v > 0) callback.onPauseSecondsChanged(v)
                        }
                    }
                }
            },
            update = { view ->
                val desired = (config.pauseDuration / 1000).toString()
                if (view.text.toString() != desired) view.setText(desired)
            }
        )
    }

    // 实时更新悬浮窗在屏幕上的坐标。
    private fun updateWindowPosition(x: Int, y: Int) {
        val view = rootView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = x
        params.y = y
        // 必须显式通知 WindowManager，仅修改 LayoutParams 对象不会生效。
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    fun setPauseSeconds(seconds: Long) {
        // PauseInput 直接从 config 读取，无需手动同步。
    }

    fun setCompact(isCompact: Boolean) {
        AppState.setCompactMode(isCompact)
    }

    fun setFlipModeLabel(mode: Int) {
        AppState.setFlipMode(mode)
    }

    fun hide() {
        rootView?.visibility = View.GONE
    }

    fun show() {
        rootView?.visibility = View.VISIBLE
    }

    fun detach() {
        composeView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        composeView?.disposeComposition()
        composeView = null
        rootView = null
        attached = false
    }

    fun isAttached(): Boolean = attached

    fun isCompact(): Boolean = AppState.compactMode.value

    // 根据 flipMode 返回翻转按钮的中文标签。
    private fun flipLabel(mode: Int): String = when (mode) {
        MirrorConfig.FLIP_MIRROR -> context.getString(R.string.flip_mirror)
        MirrorConfig.FLIP_MIRROR_ROTATE_180 -> context.getString(R.string.flip_mirror_rotate)
        MirrorConfig.FLIP_NONE -> context.getString(R.string.flip_none)
        else -> context.getString(R.string.flip_rotate)
    }
}

// 极简 LifecycleOwner + SavedStateRegistryOwner 实现。
// ComposeView 通过 WindowManager 直接挂到屏幕，没有 Activity/Fragment 作为宿主，
// 需要自行提供一个 LifecycleOwner 供 Compose 的重组作用域与 SavedState 使用。
// 工具栏常驻，生命周期固定为 RESUMED 即可。
private class ToolbarLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun performResume() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
}
