package com.liyihc.screenflipper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class MirrorConfig(context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = PREFS_NAME)
    private val dataStore = context.applicationContext.dataStore

    val pauseDurationFlow: Flow<Long> = dataStore.data.map { it[KEY_PAUSE_DURATION] ?: 5000L }
    val toolbarXFlow: Flow<Int> = dataStore.data.map { it[KEY_TOOLBAR_X] ?: 100 }
    val toolbarYFlow: Flow<Int> = dataStore.data.map { it[KEY_TOOLBAR_Y] ?: 300 }
    val renderFpsCapFlow: Flow<Int> = dataStore.data.map { it[KEY_RENDER_FPS_CAP] ?: 0 }
    val flipModeFlow: Flow<Int> = dataStore.data.map { it[KEY_FLIP_MODE] ?: 0 }
    val runningFlow: Flow<Boolean> = dataStore.data.map { it[KEY_RUNNING] ?: false }

    var pauseDuration: Long
        get() = runBlockingGet(KEY_PAUSE_DURATION, 5000L)
        set(value) = runBlockingEdit { it[KEY_PAUSE_DURATION] = value }

    var toolbarX: Int
        get() = runBlockingGet(KEY_TOOLBAR_X, 100)
        set(value) = runBlockingEdit { it[KEY_TOOLBAR_X] = value }

    var toolbarY: Int
        get() = runBlockingGet(KEY_TOOLBAR_Y, 300)
        set(value) = runBlockingEdit { it[KEY_TOOLBAR_Y] = value }

    var renderFpsCap: Int
        get() = runBlockingGet(KEY_RENDER_FPS_CAP, 0)
        set(value) = runBlockingEdit { it[KEY_RENDER_FPS_CAP] = value }

    var running: Boolean
        get() = runBlockingGet(KEY_RUNNING, false)
        set(value) = runBlockingEdit { it[KEY_RUNNING] = value }

    // 翻转模式：0=顺时针旋转180度, 1=左右镜像, 2=左右镜像+顺时针旋转180度, 3=无翻转(原图)
    var flipMode: Int
        get() = runBlockingGet(KEY_FLIP_MODE, 0)
        set(value) = runBlockingEdit { it[KEY_FLIP_MODE] = value }

    private fun <T> runBlockingGet(key: Preferences.Key<T>, default: T): T {
        return kotlinx.coroutines.runBlocking { dataStore.data.map { it[key] ?: default }.first() }
    }

    private fun runBlockingEdit(block: suspend (MutablePreferences) -> Unit) {
        kotlinx.coroutines.runBlocking { dataStore.edit(block) }
    }

    companion object {
        const val FLIP_ROTATE_180 = 0
        const val FLIP_MIRROR = 1
        const val FLIP_MIRROR_ROTATE_180 = 2
        const val FLIP_NONE = 3

        private const val PREFS_NAME = "screen_flip_prefs"
        private val KEY_PAUSE_DURATION = longPreferencesKey("pause_duration")
        private val KEY_TOOLBAR_X = intPreferencesKey("toolbar_x")
        private val KEY_TOOLBAR_Y = intPreferencesKey("toolbar_y")
        private val KEY_RENDER_FPS_CAP = intPreferencesKey("render_fps_cap")
        private val KEY_RUNNING = booleanPreferencesKey("running")
        private val KEY_FLIP_MODE = intPreferencesKey("flip_mode")
    }
}
