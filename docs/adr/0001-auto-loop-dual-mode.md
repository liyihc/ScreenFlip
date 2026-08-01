# ADR 0001 — 自动模式改为持续循环 + 手动/自动双模式分离

## Status

Accepted — 2026-07-19

## Context

原设计里 Auto Operation 与 Manual Operation 都是**单次** Operation：点一下按钮 → 进入 operating 状态 →
（自动等 Pause Duration / 手动点通知完成）→ 抓一帧显示 Snapshot → 回到 WAITING 待命。两种模式用完即止。

用户的新诉求把自动模式从"单次定时"升级为"持续循环"：

- **手动模式**：点一下"手动"立刻抓帧、进入 Display 预览。看完退出即回到待命，不做任何倒计时。
- **自动模式**：一个开关（复选框）。开启后进入**自动循环**——每次退出 Display 预览回到 Toolbar，
  就启动 Pause Duration 倒计时，到点自动抓帧再进预览；如此往复，直到关闭开关或退出服务。
- 两者是**并行的独立模式**：手动可随时打断自动；手动进预览期间自动循环挂起，退出预览后自动从零
  重新倒计时（即"手动会重置自动倒计时"）。
- 自动开关 `autoEnabled` 为**内存状态，不持久化**——每次运行需手动打开。
- Pause Duration 由用户在 Toolbar 上以"秒"输入，实时写回配置（DataStore）。

精简模式（Compact Mode）下 Toolbar 收起为标题栏 + 自动/手动图标横排，自动图标勾选时显示蓝色边框；
长按标题栏切换精简/完整。

## Decision

1. 自动模式 = 受 `autoEnabled`（内存布尔）驱动的**持续循环**，倒计时叠加在 WAITING 状态上，不再用瞬时 operating 状态表达。
2. 自动循环的计时锚点是"退出 Display 预览（State 回到 WAITING）的那一刻"，而非"点自动按钮的时刻"。
3. 开启自动开关的瞬间若已停在 WAITING，立即开始第一次倒计时。
4. 手动 Operation 优先级高于自动：手动进预览时挂起自动；退出后自动重置倒计时、重新跑。
5. 取消勾选自动 → 仅停止自动循环、回到手动待命；不杀服务。退出按钮才杀服务。
6. `autoEnabled` 不进 DataStore，仅存于 MirrorService 内存。
7. Pause Duration 等需跨运行保留的配置全部迁移到 DataStore（Flow 驱动 UI），不再使用 SharedPreferences。

## Consequences

- MirrorService 状态机需新增对"自动循环挂起/恢复"的处理，自动不再只是一次性 postDelayed。
- ToolbarManager 需新增：自动复选框、秒数输入框、精简模式切换（长按标题）、自动图标蓝色边框。
- MirrorConfig 整体重写为 DataStore + Flow；Toolbar 在 Service 作用域内 collect Flow 刷新。
- 因未发布，不做 SP→DS 迁移，直接切换。
- 未来读者会疑惑"为什么自动是循环而非单次"——此 ADR 即解释。
