# PLAN.md — Screen Flipper (镜像翻转工具)

## 可行性评估

技术栈完全成立，可在 Android 上实现：

- `MediaProjection` + `VirtualDisplay` + `SurfaceTexture`：捕获屏幕内容到纹理。
- OpenGL 片段着色器 `texture2D(sampler, vec2(1.0 - uv.x, uv.y))`：实现水平翻转。
- `TYPE_APPLICATION_OVERLAY` 悬浮窗：承载全屏覆盖层与可拖动工具栏。
- 前台服务 + 通知渠道：满足 Android 12+ 保活与权限要求。

**已识别的关键限制（实现中已规避/标注）：**

1. **覆盖层递归问题（核心概念限制）**：覆盖层显示的是"当前屏幕自身"的翻转画面，
   由于覆盖层本身就在屏幕上，会形成无限递归（镜像里套镜像）。本方案按原架构实现
   覆盖层版本（满足"在屏幕上实时看到翻转画面"的诉求），但会在 UI 提示该限制。
   若需无递归的真·镜像，应使用 `DisplayManager`/`Presentation` 投到第二块屏幕。
2. **屏幕旋转**：`VirtualDisplay` 在旋转后失效，已用 `onConfigurationChanged` 重连。
3. **GL 线程**：`SurfaceTexture` 回调通过 `queueEvent` 切到 GL 线程渲染。
4. **权限顺序**：`SYSTEM_ALERT_WINDOW` → `POST_NOTIFICATIONS` → 每次启动弹 `MediaProjection`。
5. **延迟**：约 50~100ms，不适合音游/实时游戏，已在说明中注明。

## 模块划分（单一职责）

- `MirrorEngine`：仅负责录屏授权、创建 VirtualDisplay、OpenGL 渲染（水平翻转）。
- `OverlayManager`：仅负责全屏覆盖层 Window 的增删与显隐（用 GONE 隐藏）。
- `ToolbarManager`：仅负责悬浮按钮、拖动、点击/拖动区分、倒计时 UI。
- `MirrorService`：状态机中枢（MIRRORING / PAUSED），调度上述组件与前台通知。
- `MainActivity`：权限申请、MediaProjection 授权、启动/停止服务。
- `MirrorConfig`：SharedPreferences 配置读写。

## 交互流程

1. 启动：MainActivity 申请悬浮窗/通知权限 → 申请 MediaProjection → 启动前台服务
   → attach 覆盖层 + 工具栏 → MirrorEngine 开始渲染。
2. 临时关闭：点击工具栏 → 覆盖层 GONE → 倒计时（默认 5s）→ 自动恢复。
3. 紧急恢复：倒计时中点工具栏 → 取消倒计时 → 立即恢复。

## 配置项

| 配置 | 默认 | 说明 |
|------|------|------|
| pause_duration | 5000ms | 临时关闭后自动恢复延迟 |
| toolbar_x / toolbar_y | 100 / 300 | 工具栏上次位置 |
| render_fps_cap | 0 | 0=跟随刷新率 |

## 实现清单

- [x] PLAN.md
- [x] AndroidManifest：权限、Service、Activity、通知渠道
- [x] MirrorConfig（SharedPreferences）
- [x] MirrorEngine（ImageReader 抓帧 + CPU 水平翻转，无 GL 依赖）
- [x] OverlayManager（全屏静态翻转图，GONE 隐藏）
- [x] ToolbarManager（⏱自动 / 👆手动 / 🔄重新 三态 + 拖动 + 点击区分）
- [x] MirrorService（WAITING→OPERATING_AUTO/MANUAL→SHOWING 状态机 + 前台通知手动完成）
- [x] MainActivity + 权限流程（悬浮窗→通知→MediaProjection）
- [x] 编译验证（assembleDebug 通过）
