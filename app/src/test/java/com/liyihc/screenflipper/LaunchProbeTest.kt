package com.liyihc.screenflipper

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchProbeTest {

    // ADR 0003: 后台 startActivity 被拦时 DisplayActivity.onCreate 不执行。
    // 探测结果 = 是否在超时窗口内「出现」+ 出现后是否仍显示中。

    @Test
    fun appeared_and_still_showing_is_APPEARED() {
        assertEquals(
            LaunchProbe.Outcome.APPEARED,
            LaunchProbe.outcome(appeared = true, isDisplayShowing = true)
        )
    }

    @Test
    fun appeared_but_dismissed_during_probe_window_is_APPEARED_THEN_DISMISSED() {
        assertEquals(
            LaunchProbe.Outcome.APPEARED_THEN_DISMISSED,
            LaunchProbe.outcome(appeared = true, isDisplayShowing = false)
        )
    }

    @Test
    fun never_appeared_is_BLOCKED() {
        assertEquals(
            LaunchProbe.Outcome.BLOCKED,
            LaunchProbe.outcome(appeared = false, isDisplayShowing = false)
        )
        // appeared 是权威信号：即使旧状态误报 isDisplayShowing=true，未出现即判定被拦
        assertEquals(
            LaunchProbe.Outcome.BLOCKED,
            LaunchProbe.outcome(appeared = false, isDisplayShowing = true)
        )
    }
}
