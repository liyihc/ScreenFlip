package com.liyihc.screenflipper

import android.content.Context
import android.content.SharedPreferences

class MirrorConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var pauseDuration: Long
        get() = prefs.getLong(KEY_PAUSE_DURATION, 5000L)
        set(value) = prefs.edit().putLong(KEY_PAUSE_DURATION, value).apply()

    var toolbarX: Int
        get() = prefs.getInt(KEY_TOOLBAR_X, 100)
        set(value) = prefs.edit().putInt(KEY_TOOLBAR_X, value).apply()

    var toolbarY: Int
        get() = prefs.getInt(KEY_TOOLBAR_Y, 300)
        set(value) = prefs.edit().putInt(KEY_TOOLBAR_Y, value).apply()

    var renderFpsCap: Int
        get() = prefs.getInt(KEY_RENDER_FPS_CAP, 0)
        set(value) = prefs.edit().putInt(KEY_RENDER_FPS_CAP, value).apply()

    var running: Boolean
        get() = prefs.getBoolean(KEY_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_RUNNING, value).apply()

    // 翻转模式：0=顺时针旋转180度, 1=左右镜像, 2=左右镜像+顺时针旋转180度
    var flipMode: Int
        get() = prefs.getInt(KEY_FLIP_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_FLIP_MODE, value).apply()

    companion object {
        const val FLIP_ROTATE_180 = 0
        const val FLIP_MIRROR = 1
        const val FLIP_MIRROR_ROTATE_180 = 2

        private const val PREFS_NAME = "screen_flip_prefs"
        private const val KEY_PAUSE_DURATION = "pause_duration"
        private const val KEY_TOOLBAR_X = "toolbar_x"
        private const val KEY_TOOLBAR_Y = "toolbar_y"
        private const val KEY_RENDER_FPS_CAP = "render_fps_cap"
        private const val KEY_RUNNING = "running"
        private const val KEY_FLIP_MODE = "flip_mode"
    }
}
