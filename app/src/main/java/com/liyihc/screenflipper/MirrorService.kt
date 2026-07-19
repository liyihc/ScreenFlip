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

    enum class State { IDLE, WAITING, OPERATING_AUTO, OPERATING_MANUAL, SHOWING }

    private lateinit var mirrorEngine: MirrorEngine
    private lateinit var overlayManager: OverlayManager
    private lateinit var toolbarManager: ToolbarManager
    private lateinit var config: MirrorConfig

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restoreRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var state: State = State.IDLE
    private var uiReady = false

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("ScreenFlip", "DEBUG receiver got action=${intent?.action} cmd=${intent?.getStringExtra("cmd")}")
            when (intent?.action) {
                ACTION_DEBUG -> when (intent.getStringExtra("cmd")) {
                    "start" -> { android.util.Log.d("ScreenFlip", "DEBUG start"); onStartClicked() }
                    "auto" -> { android.util.Log.d("ScreenFlip", "DEBUG auto"); onAutoToggled(!AppState.autoEnabled.value) }
                    "manual" -> { android.util.Log.d("ScreenFlip", "DEBUG manual"); onManualClicked() }
                    "done" -> { android.util.Log.d("ScreenFlip", "DEBUG done"); if (state == State.OPERATING_MANUAL) captureNow() }
                    "reset" -> { android.util.Log.d("ScreenFlip", "DEBUG reset"); resetToWaiting() }
                    "flip" -> { android.util.Log.d("ScreenFlip", "DEBUG flip"); onFlipModeClicked() }
                    "stop" -> { android.util.Log.d("ScreenFlip", "DEBUG stop"); onExitClicked() }
                    "resume" -> { toolbarManager.attach(); overlayManager.attach(); toolbarManager.show() }
                }
            }
        }
    }

    private val displayDismissedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("ScreenFlip", "Display dismissed")
            onDisplayDismissed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = MirrorConfig(this)
        overlayManager = OverlayManager(this)
        toolbarManager = ToolbarManager(this, config, this)
        registerReceiver(debugReceiver, IntentFilter(ACTION_DEBUG), Context.RECEIVER_EXPORTED)
        registerReceiver(
            displayDismissedReceiver,
            IntentFilter(DisplayActivity.ACTION_DISMISSED),
            Context.RECEIVER_EXPORTED
        )
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
            ACTION_MANUAL_DONE -> {
                if (state == State.OPERATING_MANUAL) {
                    captureNow()
                }
            }
            ACTION_DISPLAY_DISMISSED -> {
                onDisplayDismissed()
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
                    "done" -> if (state == State.OPERATING_MANUAL) captureNow()
                    "reset" -> resetToWaiting()
                    "flip" -> onFlipModeClicked()
                    "resume" -> { toolbarManager.attach(); overlayManager.attach(); toolbarManager.show() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun prepareUi() {
        if (uiReady) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("点击悬浮窗开始"))
        overlayManager.attach()
        toolbarManager.attach()
        toolbarManager.setIdle()
        toolbarManager.setFlipModeLabel(config.flipMode)
        toolbarManager.setPauseSeconds(config.pauseDuration / 1000)
        uiReady = true
        android.util.Log.d("ScreenFlip", "UI prepared, idle state")
    }

    private fun beginCapture(data: Intent) {
        android.util.Log.d("ScreenFlip", "beginCapture: overlayAttached=${overlayManager.isAttached()} toolbarAttached=${toolbarManager.isAttached()}")
        overlayManager.attach()
        toolbarManager.attach()
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, data)

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
        mirrorEngine.setFlipMode(config.flipMode)
        mirrorEngine.start(
            mediaProjection!!,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi
        )

        state = State.WAITING
        toolbarManager.setWaiting()
        toolbarManager.show()
        overlayManager.hide()
        updateNotification("镜像工具待命")
        android.util.Log.d("ScreenFlip", "after beginCapture: toolbarVisible=${toolbarManager.isAttached()}")
    }

    private fun captureNow() {
        if (!::mirrorEngine.isInitialized) {
            android.util.Log.e("ScreenFlip", "captureNow: mirrorEngine not ready, ignore")
            return
        }
        removeRestoreRunnable()
        toolbarManager.hide()
        overlayManager.hide()
        handler.postDelayed({
            mirrorEngine.captureFlipped()
        }, 150)
    }

    private fun scheduleAutoCapture() {
        removeRestoreRunnable()
        if (!AppState.autoEnabled.value || state != State.WAITING) return
        state = State.OPERATING_AUTO
        toolbarManager.setOperating(true)
        startCountdown()
        updateNotification("操作中…${config.pauseDuration / 1000}秒后显示")
        restoreRunnable = Runnable {
            android.util.Log.d("ScreenFlip", "restoreRunnable firing captureFlipped")
            AppState.setShowText("")
            toolbarManager.hide()
            overlayManager.hide()
            mirrorEngine.captureFlipped()
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
                if (state != State.OPERATING_AUTO) return
                AppState.setShowText("${remaining}秒后截图")
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
        toolbarManager.setAutoEnabled(enabled)
        if (enabled) {
            if (state == State.WAITING) scheduleAutoCapture()
        } else {
            if (state == State.OPERATING_AUTO) {
                removeRestoreRunnable()
                removeCountdownRunnable()
                AppState.setShowText("")
                state = State.WAITING
                toolbarManager.setWaiting()
                toolbarManager.show()
                updateNotification("镜像工具待命")
            }
        }
    }

    override fun onManualClicked() {
        android.util.Log.d("ScreenFlip", "onManualClicked state=$state")
        if (state == State.OPERATING_AUTO || state == State.OPERATING_MANUAL) return
        removeRestoreRunnable()
        cancelAll()
        AppState.setIsDisplayShowing(false)
        sendBroadcast(Intent(DisplayActivity.ACTION_CLOSE))
        state = State.OPERATING_MANUAL
        toolbarManager.setOperating(false)
        toolbarManager.show()
        captureNow()
    }

    override fun onStartClicked() {
        android.util.Log.d("ScreenFlip", "onStartClicked state=$state")
        if (state != State.IDLE) return
        requestProjectionViaActivity()
    }

    private fun resetToWaiting() {
        state = State.WAITING
        overlayManager.hide()
        toolbarManager.setWaiting()
        toolbarManager.show()
        updateNotification("镜像工具待命")
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
    }

    override fun onFlipModeClicked() {
        val next = (config.flipMode + 1) % 3
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

    private fun onDisplayDismissed() {
        android.util.Log.d("ScreenFlip", "onDisplayDismissed state=$state")
        if (state != State.SHOWING) return
        state = State.WAITING
        toolbarManager.setWaiting()
        toolbarManager.show()
        updateNotification("镜像工具待命")
        if (AppState.autoEnabled.value) scheduleAutoCapture()
    }

    override fun onRawFrameReady(bitmap: Bitmap) {
        handler.post {
            AppState.setRawFrame(bitmap)
            overlayManager.hide()
            val intent = Intent(this, DisplayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                android.util.Log.d("ScreenFlip", "DisplayActivity launched")
            } catch (e: Exception) {
                android.util.Log.e("ScreenFlip", "launch DisplayActivity failed: ${e.message}")
            }
            toolbarManager.setShowing()
            toolbarManager.show()
            state = State.SHOWING
            android.util.Log.d("ScreenFlip", "onRawFrameReady: display shown")
            updateNotification("已显示翻转画面")
        }
    }

    override fun onCaptureError() {
        handler.post {
            toolbarManager.setWaiting()
            toolbarManager.show()
            state = State.WAITING
            updateNotification("捕获失败，请重试")
            if (AppState.autoEnabled.value) scheduleAutoCapture()
        }
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
        val doneIntent = Intent(this, MirrorService::class.java).apply {
            action = ACTION_MANUAL_DONE
        }
        val donePi = PendingIntent.getService(
            this, 1, doneIntent,
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
            .setContentTitle("Screen Flipper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .addAction(android.R.drawable.ic_menu_gallery, "开始", startPi)
        if (state != State.IDLE) {
            builder.addAction(android.R.drawable.ic_menu_send, "完成截图", donePi)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
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
            overlayManager.detach()
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
        serviceScope.cancel()
        stopMirroring()
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.liyihc.screenflipper.ACTION_START"
        const val ACTION_STOP = "com.liyihc.screenflipper.ACTION_STOP"
        const val ACTION_MANUAL_DONE = "com.liyihc.screenflipper.ACTION_MANUAL_DONE"
        const val ACTION_DISPLAY_DISMISSED = "com.liyihc.screenflipper.ACTION_DISPLAY_DISMISSED"
        const val ACTION_PROJECTION_GRANTED = "com.liyihc.screenflipper.ACTION_PROJECTION_GRANTED"
        const val ACTION_DEBUG = "com.liyihc.screenflipper.ACTION_DEBUG"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "screen_flip_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
