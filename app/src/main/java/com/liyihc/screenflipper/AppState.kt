package com.liyihc.screenflipper

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppState {

    enum class State { IDLE, WAITING, CAPTURING, SHOWING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _autoEnabled = MutableStateFlow(false)
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    private val _rawFrame = MutableStateFlow<Bitmap?>(null)
    val rawFrame: StateFlow<Bitmap?> = _rawFrame.asStateFlow()

    private val _isDisplayShowing = MutableStateFlow(false)
    val isDisplayShowing: StateFlow<Boolean> = _isDisplayShowing.asStateFlow()

    private val _flipMode = MutableStateFlow(MirrorConfig.FLIP_ROTATE_180)
    val flipMode: StateFlow<Int> = _flipMode.asStateFlow()

    private val _compactMode = MutableStateFlow(false)
    val compactMode: StateFlow<Boolean> = _compactMode.asStateFlow()

    private val _showText = MutableStateFlow("")
    val showText: StateFlow<String> = _showText.asStateFlow()

    private val _countdownSeconds = MutableStateFlow<Long>(-1)
    val countdownSeconds: StateFlow<Long> = _countdownSeconds.asStateFlow()

    private var displaySeq = 0L

    // per-launch 探测标记：每次启动 DisplayActivity 前复位，onCreate 里置位。
    // 超时回调据此判定本次后台启动是否真的「出现」（ADR 0003）。与 isDisplayShowing
    // 不同，它是按启动实例的一次性标志，不会被旧实例/旧状态混淆。
    private var displayAppeared = false

    // 每次 DisplayActivity 启动递增；旧实例延迟销毁时广播的 DISMISSED 携带旧序号，
    // MirrorService 据此忽略过期关闭事件。
    @Synchronized
    fun nextDisplaySeq(): Long {
        displaySeq++
        return displaySeq
    }

    @Synchronized
    fun currentDisplaySeq(): Long = displaySeq

    @Synchronized
    fun resetDisplayAppeared() {
        displayAppeared = false
    }

    @Synchronized
    fun markDisplayAppeared() {
        displayAppeared = true
    }

    @Synchronized
    fun hasDisplayAppeared(): Boolean = displayAppeared

    fun setState(value: State) {
        _state.value = value
    }

    fun setAutoEnabled(value: Boolean) {
        _autoEnabled.value = value
    }

    fun setRawFrame(bitmap: Bitmap?) {
        val old = _rawFrame.value
        _rawFrame.value = bitmap
        try { old?.recycle() } catch (_: Exception) {}
    }

    fun setIsDisplayShowing(value: Boolean) {
        _isDisplayShowing.value = value
    }

    fun setFlipMode(mode: Int) {
        _flipMode.value = mode
    }

    fun setCompactMode(value: Boolean) {
        _compactMode.value = value
    }

    fun setShowText(text: String) {
        _showText.value = text
    }

    fun setCountdownSeconds(seconds: Long) {
        _countdownSeconds.value = seconds
    }
}
