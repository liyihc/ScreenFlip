package com.liyihc.screenflipper

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var launching = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            // 把授权结果回传给 Service，由它真正开始截屏
            val intent = Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_PROJECTION_GRANTED
                putExtra(MirrorService.EXTRA_PROJECTION_DATA, result.data)
            }
            startService(intent)
        }
        finish()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launching = true
            startOrRequestProjection()
        } else {
            launching = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不加载任何布局：本 Activity 仅作中转，不展示主界面

        if (intent?.action == ACTION_REQUEST_PROJECTION) {
            // 悬浮窗已在显示，直接请求投影授权，不做权限前置判断
            launching = true
            requestProjection()
            return
        }

        startOrRequestProjection()
    }

    override fun onResume() {
        super.onResume()
        // 从设置/权限页返回后，若权限现已齐全则继续
        if (intent?.action != ACTION_REQUEST_PROJECTION &&
            !launching && permissionsReady()
        ) {
            launching = true
            startOrRequestProjection()
        }
    }

    private fun startOrRequestProjection() {
        if (launching) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            launching = true
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
        // 权限齐全：启动悬浮窗服务（仅准备 UI）并退出
        launching = true
        val startIntent = Intent(this, MirrorService::class.java).apply {
            action = MirrorService.ACTION_START
        }
        startForegroundService(startIntent)
        finish()
    }

    private fun permissionsReady(): Boolean {
        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return overlayOk && notifOk
    }

    private fun requestProjection() {
        android.util.Log.d("ScreenFlip", "MainActivity requesting projection")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        const val ACTION_REQUEST_PROJECTION =
            "com.liyihc.screenflipper.ACTION_REQUEST_PROJECTION"
    }
}
