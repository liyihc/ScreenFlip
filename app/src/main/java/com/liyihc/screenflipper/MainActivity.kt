package com.liyihc.screenflipper

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_START
                putExtra(MirrorService.EXTRA_PROJECTION_DATA, result.data)
            }
            startForegroundService(intent)
            statusText.text = "镜像运行中"
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } else {
            statusText.text = "录屏授权被拒绝"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestProjection() else statusText.text = "缺少通知权限"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        stopButton.isEnabled = false

        startButton.setOnClickListener { ensurePermissionsThenStart() }
        stopButton.setOnClickListener {
            val intent = Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_STOP
            }
            startService(intent)
            statusText.text = "已停止"
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun ensurePermissionsThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            statusText.text = "需要悬浮窗权限"
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }
}
