package com.liyihc.screenflipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MirrorService : Service(), MirrorEngine.Callback, ToolbarManager.ToolbarCallback {

    private lateinit var mirrorEngine: MirrorEngine
    private lateinit var toolbarManager: ToolbarManager
    private lateinit var config: MirrorConfig

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restoreRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var state: AppState.State
        get() = AppState.state.value
        set(value) { AppState.setState(value) }
    private var uiReady = false
    private var projectionInvalidated = false
    private var lastCaptureStartMs = 0L

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("ScreenFlip", "DEBUG receiver got action=${intent?.action} cmd=${intent?.getStringExtra("cmd")}")
            when (intent?.action) {
                ACTION_DEBUG -> when (intent.getStringExtra("cmd")) {
                    "start" -> { android.util.Log.d("ScreenFlip", "DEBUG start"); onStartClicked() }
                    "auto" -> { android.util.Log.d("ScreenFlip", "DEBUG auto"); onAutoToggled(!AppState.autoEnabled.value) }
                    "manual" -> { android.util.Log.d("ScreenFlip", "DEBUG manual"); onManualClicked() }
                    "reset" -> { android.util.Log.d("ScreenFlip", "DEBUG reset"); resetToWaiting() }
                    "flip" -> { android.util.Log.d("ScreenFlip", "DEBUG flip"); onFlipModeClicked() }
                    "stop" -> { android.util.Log.d("ScreenFlip", "DEBUG stop"); onExitClicked() }
                    "resume" -> { toolbarManager.attach(); toolbarManager.show() }
                }
            }
        }
    }

    private val displayDismissedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("ScreenFlip", "Display dismissed")
            onDisplayDismissed(intent?.getLongExtra(DisplayActivity.EXTRA_DISPLAY_SEQ, -1L) ?: -1L)
        }
    }

    private val displayDismissingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("ScreenFlip", "Display dismissing (tap): to WAITING now")
            onDisplayDismissed(intent?.getLongExtra(DisplayActivity.EXTRA_DISPLAY_SEQ, -1L) ?: -1L)
        }
    }

    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (state != AppState.State.IDLE && mediaProjection != null) {
                android.util.Log.d("ScreenFlip", "SCREEN_OFF: marking projection invalidated")
                projectionInvalidated = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = MirrorConfig(this)
        toolbarManager = ToolbarManager(this, config, this)
        registerReceiver(debugReceiver, IntentFilter(ACTION_DEBUG), Context.RECEIVER_EXPORTED)
        registerReceiver(
            displayDismissedReceiver,
            IntentFilter(DisplayActivity.ACTION_DISMISSED),
            Context.RECEIVER_EXPORTED
        )
        registerReceiver(
            displayDismissingReceiver,
            IntentFilter(DisplayActivity.ACTION_DISMISSING),
            Context.RECEIVER_EXPORTED
        )
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        collectConfig()
    }

    private fun collectConfig() {
        config.pauseDurationFlow
            .onEach { toolbarManager.setPauseSeconds(it / 1000) }
            .launchIn(serviceScope)
        config.flipModeFlow
            .onEach {
                AppState.setFlipMode(it)
                toolbarManager.setFlipModeLabel(it)
            }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                prepareUi()
            }
            ACTION_PROJECTION_GRANTED -> {
                val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                if (data != null) {
                    beginCapture(data)
                }
            }
            ACTION_DISPLAY_DISMISSED -> {
                onDisplayDismissed(intent?.getLongExtra(DisplayActivity.EXTRA_DISPLAY_SEQ, -1L) ?: -1L)
            }
            ACTION_STOP -> {
                sendBroadcast(Intent(DisplayActivity.ACTION_CLOSE))
                stopMirroring()
                stopSelf()
            }
            ACTION_DEBUG -> {
                when (intent.getStringExtra("cmd")) {
                    "start" -> onStartClicked()
                    "auto" -> onAutoToggled(!AppState.autoEnabled.value)
                    "manual" -> onManualClicked()
                    "reset" -> resetToWaiting()
                    "flip" -> onFlipModeClicked()
                    "resume" -> { toolbarManager.attach(); toolbarManager.show() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun prepareUi() {
        if (uiReady) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_tap_to_start)))
        toolbarManager.attach()
        state = AppState.State.IDLE
        AppState.setShowText("")
        toolbarManager.setFlipModeLabel(config.flipMode)
        toolbarManager.setPauseSeconds(config.pauseDuration / 1000)
        uiReady = true
        android.util.Log.d("ScreenFlip", "UI prepared, idle state")
    }

    private fun beginCapture(data: Intent) {
        android.util.Log.d("ScreenFlip", "beginCapture: toolbarAttached=${toolbarManager.isAttached()}")
        toolbarManager.attach()
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, data)
        projectionInvalidated = false

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
        }

        mirrorEngine = MirrorEngine(this)
        mirrorEngine.start(
            mediaProjection!!,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi
        )

        state = AppState.State.WAITING
        AppState.setShowText("")
        toolbarManager.show()
        updateNotification(getString(R.string.notif_standby))
        android.util.Log.d("ScreenFlip", "after beginCapture: toolbarVisible=${toolbarManager.isAttached()}")
    }

    private fun captureNow() {
        if (!::mirrorEngine.isInitialized) {
            android.util.Log.e("ScreenFlip", "captureNow: mirrorEngine not ready, ignore")
            return
        }
        state = AppState.State.CAPTURING
        removeRestoreRunnable()
        lastCaptureStartMs = SystemClock.uptimeMillis()
        // 隐藏工具栏的同时触发引擎截图：引擎排空积压帧解除背压冻结、等 GONE 传播后
        // resize 强制重合成，取一帧构造上不含工具栏的画面。
        toolbarManager.hide()
        mirrorEngine.captureFlipped()
    }

    private fun scheduleAutoCapture() {
        removeRestoreRunnable()
        if (projectionInvalidated) { invalidateProjectionToIdle(); return }
        if (!AppState.autoEnabled.value || state != AppState.State.WAITING) return
        startCountdown()
        updateNotification(getString(R.string.notif_operating, config.pauseDuration / 1000))
        restoreRunnable = Runnable {
            android.util.Log.d("ScreenFlip", "restoreRunnable firing captureFlipped")
            AppState.setShowText("")
            captureNow()
        }
        handler.postDelayed(restoreRunnable!!, config.pauseDuration)
        android.util.Log.d("ScreenFlip", "auto scheduled capture in ${config.pauseDuration}ms")
    }

    private fun startCountdown() {
        removeCountdownRunnable()
        val totalSec = config.pauseDuration / 1000
        var remaining = totalSec
        countdownRunnable = object : Runnable {
            override fun run() {
                if (state != AppState.State.WAITING) return
                AppState.setShowText(getString(R.string.countdown_screenshot, remaining))
                AppState.setCountdownSeconds(remaining)
                remaining--
                if (remaining >= 0) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(countdownRunnable!!)
    }

    override fun onAutoToggled(enabled: Boolean) {
        android.util.Log.d("ScreenFlip", "onAutoToggled enabled=$enabled state=$state")
        AppState.setAutoEnabled(enabled)
        if (enabled) {
            if (state == AppState.State.WAITING) scheduleAutoCapture()
        } else {
            if (restoreRunnable != null) {
                removeRestoreRunnable()
                removeCountdownRunnable()
                AppState.setShowText("")
                AppState.setCountdownSeconds(-1)
                updateNotification(getString(R.string.notif_standby))
            }
        }
    }

    override fun onManualClicked() {
        android.util.Log.d("ScreenFlip", "onManualClicked state=$state")
        if (projectionInvalidated) { invalidateProjectionToIdle(); return }
        if (state == AppState.State.CAPTURING) return
        removeRestoreRunnable()
        cancelAll()
        AppState.setIsDisplayShowing(false)
        if (state == AppState.State.SHOWING) {
            // 显示画面还开着：只关闭它，不自动截图。等 DISMISSED 把状态带回 WAITING 后
            // 再点一次手动才会截，避免把全屏显示画面本身截进截图（回显）。
            sendBroadcast(Intent(DisplayActivity.ACTION_CLOSE))
            return
        }
        sendBroadcast(Intent(DisplayActivity.ACTION_CLOSE))
        toolbarManager.show()
        captureNow()
    }

    override fun onStartClicked() {
        android.util.Log.d("ScreenFlip", "onStartClicked state=$state")
        if (state != AppState.State.IDLE) return
        requestProjectionViaActivity()
    }

    private fun resetToWaiting() {
        state = AppState.State.WAITING
        AppState.setShowText("")
        toolbarManager.show()
        updateNotification(getString(R.string.notif_standby))
        if (AppState.autoEnabled.value) scheduleAutoCapture()
    }

    override fun onExitClicked() {
        AppState.setIsDisplayShowing(false)
        sendBroadcast(Intent(DisplayActivity.ACTION_CLOSE))
        stopMirroring()
        stopSelf()
    }

    private fun cancelAll() {
        removeRestoreRunnable()
        removeCountdownRunnable()
        AppState.setShowText("")
        AppState.setCountdownSeconds(-1)
    }

    override fun onFlipModeClicked() {
        val next = (config.flipMode + 1) % 4
        config.flipMode = next
        AppState.setFlipMode(next)
        toolbarManager.setFlipModeLabel(next)
        android.util.Log.d("ScreenFlip", "flip mode changed to $next")
    }

    override fun onPauseSecondsChanged(seconds: Long) {
        config.pauseDuration = seconds * 1000L
        android.util.Log.d("ScreenFlip", "pause duration set to ${seconds}s")
    }

    override fun onCompactToggled() {
        toolbarManager.setCompact(!isCompact())
    }

    private fun isCompact(): Boolean {
        return toolbarManager.isCompact()
    }

    private fun onDisplayDismissed(seq: Long) {
        val current = AppState.currentDisplaySeq()
        if (seq != -1L && seq != current) {
            // 快速连拍时旧预览的销毁会被延迟到新预览显示之后：上一个 onDestroy 广播的
            // DISMISSED 携带旧序号，这种过期事件不能把状态拉出 SHOWING。
            android.util.Log.d("ScreenFlip", "onDisplayDismissed ignored: stale seq=$seq current=$current")
            return
        }
        android.util.Log.d("ScreenFlip", "onDisplayDismissed state=$state")
        if (state != AppState.State.SHOWING) return
        state = AppState.State.WAITING
        AppState.setShowText("")
        toolbarManager.show()
        updateNotification(getString(R.string.notif_standby))
        if (AppState.autoEnabled.value) scheduleAutoCapture()
    }

    override fun onRawFrameReady(bitmap: Bitmap) {
        handler.post {
            AppState.setRawFrame(bitmap)
            val intent = Intent(this, DisplayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                AppState.nextDisplaySeq()
                android.util.Log.d(
                    "ScreenFlip",
                    "LATENCY capture->display ${SystemClock.uptimeMillis() - lastCaptureStartMs}ms"
                )
                android.util.Log.d("ScreenFlip", "DisplayActivity launched")
            } catch (e: Exception) {
                android.util.Log.e("ScreenFlip", "launch DisplayActivity failed: ${e.message}")
            }
            state = AppState.State.SHOWING
            toolbarManager.show()
            AppState.setShowText(getString(R.string.notif_shown))
            android.util.Log.d("ScreenFlip", "onRawFrameReady: display shown")
            updateNotification(getString(R.string.notif_shown))
        }
    }

    override fun onCaptureError() {
        handler.post { invalidateProjectionToIdle() }
    }

    private fun invalidateProjectionToIdle() {
        android.util.Log.d("ScreenFlip", "invalidateProjectionToIdle: back to idle for re-auth")
        cancelAll()
        projectionInvalidated = false
        AppState.setAutoEnabled(false)
        if (::mirrorEngine.isInitialized) {
            try { mirrorEngine.stop() } catch (_: Exception) {}
        }
        mediaProjection?.stop()
        mediaProjection = null
        state = AppState.State.IDLE
        toolbarManager.show()
        updateNotification(getString(R.string.notif_projection_invalid))
    }

    private fun requestProjectionViaActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_PROJECTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "start activity failed: ${e.message}")
        }
    }

    private fun removeRestoreRunnable() {
        restoreRunnable?.let { handler.removeCallbacks(it) }
        restoreRunnable = null
    }

    private fun removeCountdownRunnable() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Flip",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val startIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_PROJECTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val startPi = PendingIntent.getActivity(
            this, 0, startIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val stopIntent = Intent(this, MirrorService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 2, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .addAction(android.R.drawable.ic_menu_gallery, getString(R.string.notif_action_start), startPi)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopPi)
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopMirroring() {
        try {
            cancelAll()
            AppState.setAutoEnabled(false)
            AppState.setRawFrame(null)
            AppState.setIsDisplayShowing(false)
            mirrorEngine.stop()
            toolbarManager.detach()
            mediaProjection?.stop()
            mediaProjection = null
            config.running = false
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "stopMirroring error: ${e.message}")
        }
        stopForeground(true)
    }

    override fun onDestroy() {
        try { unregisterReceiver(debugReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(displayDismissedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(displayDismissingReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        serviceScope.cancel()
        stopMirroring()
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.liyihc.screenflipper.ACTION_START"
        const val ACTION_STOP = "com.liyihc.screenflipper.ACTION_STOP"
        const val ACTION_DISPLAY_DISMISSED = "com.liyihc.screenflipper.ACTION_DISPLAY_DISMISSED"
        const val ACTION_PROJECTION_GRANTED = "com.liyihc.screenflipper.ACTION_PROJECTION_GRANTED"
        const val ACTION_DEBUG = "com.liyihc.screenflipper.ACTION_DEBUG"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "screen_flip_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
