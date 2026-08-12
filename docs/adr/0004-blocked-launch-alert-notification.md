# ADR 0004 — 后台启动被拦的引导：高重要性告警通知 + 通知直达权限设置页

## Status

Accepted — 2026-08-13

## Context

ADR 0003 决定 5/6 的引导链路是「更新通知 + 自动弹引导对话框（MainActivity →
「去开启」→ 权限设置页）」，返回后自动重试。实测发现对话框链路无效：
`showBackgroundPopupGuide` 里的 `startActivity(MainActivity)` 同样发生在后台，
被同一条 vendor 权限（后台弹出窗口）静默吞掉——对话框永远不会出现，用户实际只
看到通知。且现有通知通道是 `IMPORTANCE_LOW`（静默、无横幅、常被折叠），被拦通知
"不明显"；通知也没有 `setContentIntent`，点通知本体无反应。引导的唯一可靠出口
只剩通知。

## Decision

1. 被拦引导改为**单一路径**：删除 `showBackgroundPopupGuide` +
   `ACTION_SHOW_OVERLAY_GUIDE` 对话框链路（本机必然被拦、永不出现，属死代码）。
2. 新建独立高重要性通道 `screen_flip_alerts`（`IMPORTANCE_HIGH`，横幅+声音），
   只用于被拦通知；日常待命/工作通知仍走原 `IMPORTANCE_LOW` 通道。Android 通道
   重要性创建后不可改，故用第二通道而非升级原通道——删除重建会重置用户设置，且
   把日常通知也变成高优先级，影响面过大。
3. 被拦通知设置 `contentIntent` → MainActivity 新 action
   `ACTION_OPEN_PERMISSION_SETTINGS` → 直达 MIUI「后台弹出窗口」权限编辑页
   （复用 `openOverlayPermissionSettings`：候选 Intent 失败退回系统应用详情页）。
   通知点击是用户主动操作，不受后台启动限制——这是它能跳设置页的根本前提。
4. 返回自动重试（沿用 ADR 0003 决定 6 的 `ACTION_RETRY_DISPLAY` + rawFrame
   重放）：MainActivity 以该 action 启动时置 `settingsReturnRetry` 标记，从设置页
   返回的 `onResume` 发 `ACTION_RETRY_DISPLAY` 重放被拦的 Display；仍被拦则探测
   回调再次弹告警通知。
5. 被拦文案改为明示可点击："被系统拦截：点击开启「后台弹出窗口」权限"。
6. 悬浮窗副标题同时提示（"需后台启动权限，可点通知"），常驻可见、指向通知这个
   操作入口；副标题本身**不可点击**——工具栏点击发起的 `startActivity` 属于后台
   启动，会被同一条权限静默拦截，重演"点手动没反应"的旧坑。副标题保持到状态
   解决为止，无独立计时器。

## Consequences

- 被拦时引导唯一入口是 heads-up 通知，用户不再被永不出现的对话框误导。
- 通知权限被关（Android 13+ 拒绝 `POST_NOTIFICATIONS`）时无任何引导——app 启动
  时会请求该权限，接受此边角风险。
- 旧 `overlay_guide_*` 字符串与 `ACTION_SHOW_OVERLAY_GUIDE` 变为死资源，已删除。
- ADR 0003 决定 5/6 中「自动弹对话框」部分被本 ADR 取代。
