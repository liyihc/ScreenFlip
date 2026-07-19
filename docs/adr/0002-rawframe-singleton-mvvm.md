# ADR 0002 — 原始帧入单例 StateFlow，翻转计算移到 DisplayActivity

## Status

Accepted — 2026-07-19

## Context

原设计里 `MirrorEngine.captureFlipped()` 在捕获后**直接在 CPU 上算好翻转图**，
把已翻转的 Snapshot 塞进 `FlipBitmapHolder.bitmap` 静态字段，再启动
`DisplayActivity` 直接 `setImageBitmap` 显示。翻转时机被锁死在"捕获那一瞬"：
切 Flip Mode 必须等下一次捕获才生效，无法在已显示的预览上实时换模式。

用户要把架构改成真正的 MVVM：

- 新增一个进程级 `object` 单例（`AppState`），用 `StateFlow` 持有临时运行时状态，
  成为唯一真相源，替代 `FlipBitmapHolder` 的静态字段与散落在 Service /
  ToolbarManager 各一份的 `autoEnabled`。它**不持久化**，与 MirrorConfig（DataStore）
  明确分层并存。
- `MirrorEngine` 只产出**原始帧（未翻转）**，放进 `AppState.rawFrame: StateFlow<Bitmap?>`，
  单帧缓存（覆盖旧的、及时回收，避免 OOM）。
- 翻转抽成纯函数；`DisplayActivity` 订阅 `rawFrame` + `flipMode` 两个流，在 Activity 内
  **合并计算**出最终 Snapshot 再刷新 UI。这样预览期间用户切 Flip Mode，Activity 用同一
  原始帧实时重算、立刻换显示，无需重新捕获。

## Decision

1. `MirrorEngine` 只负责捕获并产出原始帧，不再在引擎内做翻转。
2. 翻转逻辑抽为无状态纯函数，由 Display 侧调用。
3. `AppState.rawFrame` 只缓存最新一帧；旧帧在覆盖时回收。
4. `DisplayActivity` 作为 MVVM 的"视图+转换层"，合并 `rawFrame` 与 `flipMode` 计算显示图。
5. `FlipBitmapHolder` 静态字段由 `AppState` 单例取代。

## Consequences

- 切 Flip Mode 在预览中即时生效，解决了旧设计必须等下次捕获的痛点。
- 原始帧跨组件传递，需关注大图内存：单帧缓存 + 及时回收是硬约束。
- 捕获与显示解耦：引擎不再依赖 Display 的存在即可产出帧。
