package com.liyihc.screenflipper

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt

// 悬浮工具栏管理器。
// 与旧实现（直接 new 一堆 View 塞进 WindowManager）不同，这里使用 Jetpack Compose
// 渲染界面：把一个 ComposeView 作为 WindowManager 的子 View 挂到屏幕上，
// Compose 的内容通过 collectAsStateWithLifecycle 直接订阅 AppState 的状态流。
// 拖动与长按手势保留在标题栏上（与原生实现一致）：
//  - 拖动超过 10px 视为移动，实时更新窗口坐标并落盘；
//  - 长按 400ms 切换 精简/完整 模式。
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

    // 手势状态（声明在类成员上，供 TitleBar 的 pointerInput 闭包读写）。
    private var longPressRunnable: Runnable? = null
    private var dragHandled = false
    private var longPressFired = false

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())

    // ComposeView 自身，以及它作为 WindowManager 子 View 的根（两者指向同一个对象）。
    private var composeView: ComposeView? = null
    private var rootView: View? = null
    private var attached = false

    // 当前窗口坐标（拖动时增量更新，松手时写回 config）。
    private var dragX = 0
    private var dragY = 0

    // 配色：主色用于按钮底色，半透明蓝用于整体背景。
    private val accent = Color(0xFF1565C0)
    private val surface = Color(0xEE1565C0)

    fun attach() {
        if (attached) return
        val compose = ComposeView(context)
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

        Box(
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .background(surface, RoundedCornerShape(8.dp))
                .padding(8.dp)
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

    // 标题栏：承载拖动与长按手势，并显示应用名。
    @Composable
    private fun TitleBar() {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            // 按下即启动长按计时，超过阈值视为切换 compact 模式。
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressFired = false
                            dragHandled = false
                            longPressRunnable = Runnable {
                                longPressFired = true
                                callback.onCompactToggled()
                            }
                            handler.postDelayed(longPressRunnable!!, 400)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // 超过阈值才判定为拖动，避免与长按冲突。
                            if (!dragHandled &&
                                (kotlin.math.abs(dragAmount.x) > 10 ||
                                    kotlin.math.abs(dragAmount.y) > 10)
                            ) {
                                dragHandled = true
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null
                            }
                            if (dragHandled) {
                                dragX += dragAmount.x.roundToInt()
                                dragY += dragAmount.y.roundToInt()
                                updateWindowPosition(dragX, dragY)
                            }
                        },
                        onDragEnd = {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            // 拖动结束才落盘坐标。
                            if (dragHandled) {
                                config.toolbarX = dragX
                                config.toolbarY = dragY
                            }
                            dragHandled = false
                            longPressFired = false
                        },
                        onDragCancel = {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            dragHandled = false
                            longPressFired = false
                        }
                    )
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
        val waitingOrOperating = state == AppState.State.WAITING ||
            state == AppState.State.OPERATING_AUTO ||
            state == AppState.State.OPERATING_MANUAL
        val showing = state == AppState.State.SHOWING

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TitleBar()

            if (showText.isNotBlank()) {
                Text(text = showText, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
            }

            if (idle) {
                Button(onClick = onStart) { Text("\u25B6 开始") }
            }

            if (waitingOrOperating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(checked = autoEnabled, onCheckedChange = onAuto)
                    Text("\u23F1 自动循环", color = Color.White, fontSize = 14.sp)
                    PauseInput()
                    Text("S", color = Color.White, fontSize = 14.sp)
                }
                Button(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("\uD83D\uDC46 手动") }
            }

            if (showing) {
                Button(onClick = onFlip) { Text(flipLabel(flipMode)) }
            }

            if (waitingOrOperating || showing) {
                Button(onClick = onExit) { Text("\u23F9 退出") }
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
        val waitingOrOperating = state == AppState.State.WAITING ||
            state == AppState.State.OPERATING_AUTO ||
            state == AppState.State.OPERATING_MANUAL
        val showing = state == AppState.State.SHOWING

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TitleBar()

            if (idle) {
                Text(
                    "\u25B6",
                    color = Color.White,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent)
                        .padding(12.dp)
                        .clickable(onClick = onStart)
                )
            }

            if (waitingOrOperating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            .padding(horizontal = 12.dp, vertical = 4.dp)
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
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable(onClick = onFlip)
                )
            }

            if (showText.isNotBlank()) {
                Text(text = showText, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
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
                    hint = "秒"
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
        MirrorConfig.FLIP_MIRROR -> "\uD83D\uDD01 翻转:左右镜像"
        MirrorConfig.FLIP_MIRROR_ROTATE_180 -> "\uD83D\uDD01 翻转:镜像+旋转180°"
        MirrorConfig.FLIP_NONE -> "\uD83D\uDD01 翻转:无翻转"
        else -> "\uD83D\uDD01 翻转:旋转180°"
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
