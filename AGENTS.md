# AGENTS.md

Android app (Kotlin, namespace `com.liyihc.screenflipper`). Screen-mirror tool that
captures the screen via `MediaProjection` + `ImageReader` and shows a horizontally
flipped static snapshot, avoiding the recursion of overlaying the capture on itself.

## Build environment (no system JDK — must set manually)

- JDK: Android Studio bundled JBR (no standalone JDK installed):
  `C:\Program Files\Android\Android Studio\jbr`
- Android SDK: `C:\Users\liyih\AppData\Local\Android\Sdk` (from `local.properties`)
- Gradle wrapper: `gradlew.bat` (Windows). Build fails without `JAVA_HOME` set.

Build command (PowerShell, from repo root):

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

## Architecture notes (non-obvious)

- `MirrorEngine`: captures ONE frame via `ImageReader` + `VirtualDisplay`, flips it
  horizontally on the CPU (`flipHorizontally`), and returns a `Bitmap`. Intentionally
  NOT a real-time GL pipeline — avoids GL/SurfaceTexture deps and the self-capture
  recursion problem.
- `OverlayManager`: full-screen `ImageView` overlay (`TYPE_APPLICATION_OVERLAY`) showing
  the flipped `Bitmap`; hidden with `View.GONE` (not detach) so capture stays clean.
- `ToolbarManager`: draggable floating window with three state buttons
  (⏱ auto / 👆 manual / 🔄 redo). Toolbar is `GONE` during capture so it never enters
  the captured frame.
- `MirrorService`: state machine `WAITING -> OPERATING_AUTO|OPERATING_MANUAL -> SHOWING`.
  Auto mode captures after `config.pauseDuration` (ms). Manual mode captures when the
  foreground notification "完成截图" action (`ACTION_MANUAL_DONE`) is tapped.
  Foreground service type is `mediaProjection`; notification channel required.
- `MainActivity`: permission order is SYSTEM_ALERT_WINDOW -> POST_NOTIFICATIONS (API 33+)
  -> MediaProjection prompt. Extends `AppCompatActivity` (needs `registerForActivityResult`,
  not framework `Activity`).

## Config

`MirrorConfig` wraps `SharedPreferences`: `pause_duration` (default 5000ms),
`toolbar_x/y` (last toolbar position), `render_fps_cap` (unused by snapshot engine).

## Gotchas

- Never render via a GL overlay on top of the live capture — it recurses. Capture is
  instantaneous and the toolbar is hidden before `captureFlipped()`.
- `getParcelableExtra` / `Notification.addAction` / `stopForeground(bool)` show
  deprecation warnings but compile; leave as-is unless migrating to new APIs.
