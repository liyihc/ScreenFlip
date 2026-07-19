package com.liyihc.screenflipper

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppState {

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
}
