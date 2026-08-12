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
    // 用户点了「去开启」从引导对话框进入权限设置页；返回时 onResume 触发自动重试。
    private var guideReturnRetry = false

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
        android.util.Log.d("ScreenFlip", "MainActivity onCreate action=${intent?.action}")
        // 不加载任何布局：本 Activity 仅作中转，不展示主界面

        if (intent?.action == ACTION_SHOW_OVERLAY_GUIDE) {
            showOverlayGuide()
            return
        }

        if (intent?.action == ACTION_REQUEST_PROJECTION) {
            // 悬浮窗已在显示，直接请求投影授权，不做权限前置判断
            launching = true
            requestProjection()
            return
        }

        startOrRequestProjection()
    }

    // 后台启动被系统拦截时的引导对话框：说明原因并提供「去开启」按钮，
    // 打开 MIUI「后台弹出窗口」/应用权限设置页。返回时（onResume）自动重试（R-Q6）。
    private fun showOverlayGuide() {
        android.util.Log.d("ScreenFlip", "showing overlay permission guide dialog")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.overlay_guide_title)
            .setMessage(R.string.overlay_guide_message)
            .setPositiveButton(R.string.overlay_guide_open) { _, _ ->
                guideReturnRetry = true
                openOverlayPermissionSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!guideReturnRetry) finish() }
            .show()
    }

    // 从权限设置页返回：请求 MirrorService 用已捕获的 rawFrame 重放 DisplayActivity。
    // 仍被拦时探测回调会再弹引导（ADR 0003 决定 6）。
    private fun retryDisplayAfterGuide() {
        android.util.Log.d("ScreenFlip", "guide settings return: retrying display launch")
        sendBroadcast(Intent(MirrorService.ACTION_RETRY_DISPLAY))
        finish()
    }

    // 优先打开 MIUI「后台弹出窗口」权限编辑页；失败则退回应用权限详情页。
    private fun openOverlayPermissionSettings() {
        val pkg = packageName
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", pkg)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.permcenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", pkg)
            }
        )
        for (it in candidates) {
            if (it.resolveActivity(packageManager) != null) {
                try {
                    startActivity(it)
                    return
                } catch (e: Exception) {
                    android.util.Log.e("ScreenFlip", "open MIUI perm editor failed: ${e.message}")
                }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "open app details failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 引导流程：从权限设置页返回时自动重试 DisplayActivity 启动；否则不跑普通权限路径。
        if (intent?.action == ACTION_SHOW_OVERLAY_GUIDE) {
            if (guideReturnRetry) retryDisplayAfterGuide()
            return
        }
        // 从设置/权限页返回后，若权限现已齐全则继续
        if (intent?.action != ACTION_REQUEST_PROJECTION &&
            !launching && permissionsReady()
        ) {
            launching = true
            startOrRequestProjection()
        }
    }

    private fun startOrRequestProjection() {
        android.util.Log.d("ScreenFlip", "startOrRequestProjection launching=$launching")
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
        android.util.Log.d("ScreenFlip", "launching MirrorService ACTION_START")
        val startIntent = Intent(this, MirrorService::class.java).apply {
            action = MirrorService.ACTION_START
        }
        try {
            startForegroundService(startIntent)
            android.util.Log.d("ScreenFlip", "startForegroundService called ok")
        } catch (e: Exception) {
            android.util.Log.e("ScreenFlip", "startForegroundService failed: ${e.message}")
        }
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
        const val ACTION_SHOW_OVERLAY_GUIDE =
            "com.liyihc.screenflipper.ACTION_SHOW_OVERLAY_GUIDE"
    }
}
