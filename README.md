# ScreenFlip

Android screen-mirror tool: captures one screen frame, flips it on the CPU, and shows it full-screen.

屏幕翻转镜像工具：抓取屏幕的一帧画面，在 CPU 上翻转后全屏显示。

## Usage

1. Install and launch the app; grant overlay and notification permissions.
2. Tap "开始" in the toolbar and tap **允许** on the system screen-capture dialog (manual tap needed after every install / service restart).
3. Use the toolbar buttons:
   - **👆 Manual**: capture one frame immediately and show it full-screen.
   - **⏱ Auto**: toggle the auto-capture loop (interval is configurable).
   - **🔄 Redo**: capture again.
   - **🔁 Flip**: cycle the flip mode.
   - **Long-press the title bar**: toggle the compact/simplified layout.
4. Tap the screen to close the full-screen preview.

1. 安装并启动 App，授予悬浮窗与通知权限。
2. 点击工具栏「开始」，在系统弹窗中选择**允许**以授予屏幕录制权限（每次安装/重启服务后都需要手动点一次）。
3. 使用工具栏按钮：
   - **👆 手动**：立即抓取一帧并全屏显示。
   - **⏱ 自动**：开启/关闭自动循环抓取（间隔可调）。
   - **🔄 重做**：重新抓取。
   - **🔁 翻转**：循环切换翻转模式。
   - **长按标题栏**：切换精简/完整布局。
4. 点击屏幕关闭全屏预览。

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

No system JDK on this machine; set `JAVA_HOME` manually before building.

本机没有系统 JDK，构建前必须手动设置 `JAVA_HOME`。

## Related docs

- `docs/ARCHITECTURE.md` — components and state machine.
- `docs/DEBUGGING.md` — ADB debug commands and environment config.

- `docs/ARCHITECTURE.md` — 组件与状态机说明。
- `docs/DEBUGGING.md` — ADB 调试命令与环境配置。
