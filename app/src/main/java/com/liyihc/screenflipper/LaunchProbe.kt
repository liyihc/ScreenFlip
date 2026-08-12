package com.liyihc.screenflipper

// 后台启动探测的判定：DisplayActivity 在超时窗口内是否「出现」（onCreate 执行）
// 以及出现后是否仍显示中。纯函数，便于主机单测（见 ADR 0003）。
object LaunchProbe {
    enum class Outcome { APPEARED, APPEARED_THEN_DISMISSED, BLOCKED }

    // appeared = 本次启动 DisplayActivity.onCreate 已执行（per-launch 标记）。
    // isDisplayShowing = 当前 DisplayActivity 是否仍在前台显示。
    // appeared 是权威信号：未出现即判定被拦，不依赖 isDisplayShowing 的旧值。
    fun outcome(appeared: Boolean, isDisplayShowing: Boolean): Outcome = when {
        appeared && isDisplayShowing -> Outcome.APPEARED
        appeared -> Outcome.APPEARED_THEN_DISMISSED
        else -> Outcome.BLOCKED
    }
}
