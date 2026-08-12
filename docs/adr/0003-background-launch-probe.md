# ADR 0003 — 后台启动被拦截的运行时探测：超时观察而非 API 查询

## Status

Accepted — 2026-08-13

## Context

MIUI「后台弹出窗口」这类定制系统权限被禁时，Toolbar 的悬浮窗 `addView` 通常仍能
成功、`Settings.canDrawOverlays()` 也返回 true，但 app 在后台 `startActivity`
启动 `DisplayActivity` 会被**静默拦截**——不抛异常、Activity 就是不出现。用户视角
即「点『手动』没反应」，日志表现为 `LATENCY capture->display` 之后没有
`DisplayActivity onCreate`。

最初方案是在 `ToolbarManager.attach()` 里探测 addView 失败并匹配异常文本
（"2038"/"permission denied"）。但它只覆盖 addView 抛异常的情形，而真正被拦的是
**后台 Activity 启动**；且异常文本匹配跨 ROM 脆弱。

## Decision

1. 探测点移到 `MirrorService.onRawFrameReady` 的 `startActivity(DisplayActivity)`
   之后：启动后等待约 800ms，若 `DisplayActivity.onCreate` 未执行则判定后台启动被拦截。
   探测结果有三种：已出现且仍显示中（进 SHOWING）、已出现但在探测窗口内被用户关闭
   （回 WAITING，等同正常关闭）、未出现（被拦）。最后一种才触发被拦分支。
2. 不依赖异常文本匹配；现有 catch 分支只作次级信号。
3. 删除 `ToolbarManager.attach()` 的 addView 探测与 `verifyAttached`。
4. 判定被拦后：不回 SHOWING、该次捕获的 Frame 不作为 Snapshot 渲染（其数据仍保留在
   AppState.rawFrame 中，供决定 6 的自动重试重放）、回 WAITING、更新通知提示权限原因，
   并停止自动循环（避免每 5 秒叠弹窗）。
5. 被拦引导由「自动弹对话框」改为**高重要性告警通知 + 通知直达权限设置页**（单一路径）：
   每次被拦都更新告警通知，不按生命周期去重；自动循环已停，不会叠通知。
6. 用户从权限设置页返回后自动重试：用已捕获的 `rawFrame` 重放 `DisplayActivity`；
   若仍被拦则再弹告警通知。

> 决定 5/6 中的"自动弹对话框"部分已被 [ADR 0004](./0004-blocked-launch-alert-notification.md)
> 取代（对话框自身的后台启动同样被拦、永不出现，故改为通知单一路径）。

## Consequences

- 后台启动被拦从「点手动无反应」变成明确的权限引导提示。
- 探测依赖 onCreate 是否在超时窗口内执行；实测 onCreate 在启动后 ~30ms 内到达，
  800ms 足以避开慢冷启动误报。
- 被拦即停自动循环，避免重复弹窗；手动重试仍会被拦即弹。
- 探测窗口内（未确认出现前）状态保持 CAPTURING，用户若在 800ms 内快速关闭显示，
  由「已出现但已关闭」分支带回 WAITING，状态机不脱轨。
