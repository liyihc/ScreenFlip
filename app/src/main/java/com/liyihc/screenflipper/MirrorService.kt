package com.liyihc.screenflipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
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

class MirrorService : Service(), MirrorEngine.Callback, ToolbarManager.ToolbarCallback {

    enum class State { IDLE, WAITING, OPERATING_AUTO, OPERATING_MANUAL, SHOWING }

    private lateinit var mirrorEngine: MirrorEngine
    private lateinit var overlayManager: OverlayManager
    private lateinit var toolbarManager: ToolbarManager
    private lateinit var config: MirrorConfig

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restoreRunnable: Runnable? = null
    private var state: State = State.IDLE
    private var uiReady = false

    override fun onCreate() {
        super.onCreate()
        config = MirrorConfig(this)
        overlayManager = OverlayManager(this)
        toolbarManager = ToolbarManager(this, config, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 仅准备 UI（悬浮窗），不立即请求截屏授权
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
            ACTION_STOP -> {
                stopMirroring()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun prepareUi() {
        if (uiReady) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("点击悬浮窗开始"))
        overlayManager.attach()
        toolbarManager.attach()
        toolbarManager.setIdle()
        uiReady = true
        android.util.Log.d("ScreenFlip", "UI prepared, idle state")
    }

    private fun beginCapture(data: Intent) {
        android.util.Log.d("ScreenFlip", "beginCapture")
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
        mirrorEngine.start(
            mediaProjection!!,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi
        )

        state = State.WAITING
        toolbarManager.setWaiting()
        updateNotification("镜像工具待命")
    }

    private fun captureNow() {
        removeRestoreRunnable()
        toolbarManager.hide()
        overlayManager.hide()
        handler.postDelayed({
            mirrorEngine.captureFlipped()
        }, 150)
    }

    override fun onAutoClicked() {
        if (state == State.SHOWING || state == State.OPERATING_AUTO || state == State.OPERATING_MANUAL) return
        state = State.OPERATING_AUTO
        toolbarManager.setOperating(true)
        toolbarManager.hide()
        overlayManager.hide()
        updateNotification("操作中…${(config.pauseDuration / 1000)}秒后显示")
        restoreRunnable = Runnable { mirrorEngine.captureFlipped() }
        handler.postDelayed(restoreRunnable!!, config.pauseDuration)
    }

    override fun onManualClicked() {
        if (state == State.SHOWING || state == State.OPERATING_AUTO || state == State.OPERATING_MANUAL) return
        state = State.OPERATING_MANUAL
        toolbarManager.setOperating(false)
        toolbarManager.hide()
        overlayManager.hide()
        updateNotification("操作后点通知完成")
    }

    override fun onStartClicked() {
        if (state != State.IDLE) return
        requestProjectionViaActivity()
    }

    override fun onResetClicked() {
        state = State.WAITING
        overlayManager.hide()
        toolbarManager.setWaiting()
        toolbarManager.show()
        updateNotification("镜像工具待命")
    }

    override fun onExitClicked() {
        stopMirroring()
        stopSelf()
    }

    override fun onSnapshotReady(bitmap: Bitmap) {
        overlayManager.show(bitmap)
        toolbarManager.setShowing()
        toolbarManager.show()
        state = State.SHOWING
        updateNotification("已显示翻转画面")
    }

    override fun onCaptureError() {
        toolbarManager.setWaiting()
        toolbarManager.show()
        state = State.WAITING
        updateNotification("捕获失败，请重试")
    }

    private fun requestProjectionViaActivity() {
        // MediaProjection 授权必须由 Activity 发起，这里拉起 MainActivity 做中转
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
        removeRestoreRunnable()
        mirrorEngine.stop()
        overlayManager.detach()
        toolbarManager.detach()
        mediaProjection?.stop()
        mediaProjection = null
        config.running = false
        stopForeground(true)
    }

    override fun onDestroy() {
        stopMirroring()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.liyihc.screenflipper.ACTION_START"
        const val ACTION_STOP = "com.liyihc.screenflipper.ACTION_STOP"
        const val ACTION_MANUAL_DONE = "com.liyihc.screenflipper.ACTION_MANUAL_DONE"
        const val ACTION_PROJECTION_GRANTED = "com.liyihc.screenflipper.ACTION_PROJECTION_GRANTED"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "screen_flip_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
