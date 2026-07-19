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
  on the CPU according to `config.flipMode`, and returns a `Bitmap`. Intentionally
  NOT a real-time GL pipeline — avoids GL/SurfaceTexture deps and the self-capture
  recursion problem.
  - `flipMode`: 0 = rotate 180° (default), 1 = left-right mirror, 2 = mirror + rotate 180°.
  - Capture uses `OnImageAvailableListener` + a 1.2s fallback `acquireLatestImage()`;
    do NOT call `acquireLatestImage()` synchronously on a thread — it blocks forever.
  - `MediaProjection.registerCallback(...)` MUST be called before `createVirtualDisplay()`
    or it throws `IllegalStateException` on newer Android.
  - `onSnapshotReady` is delivered on the render thread; MirrorService re-posts to main
    before touching any View.
- `DisplayActivity`: full-screen, opaque (`translucent=false`) Activity that shows the
  flipped `Bitmap`. Chosen over `TYPE_APPLICATION_OVERLAY` because MIUI/HyperOS force-clamps
  overlay windows to `alpha=0.8` (system limit, can't be overridden). Tap to dismiss.
  Bitmap is passed in-process via `FlipBitmapHolder` (no Intent parceling of large bitmaps).
- `OverlayManager`: still owns the black backdrop `ImageView`, but is now only used as a
  hidden backdrop during capture (and re-hidden in `onSnapshotReady`). The actual flipped
  image is shown by `DisplayActivity`.
- `ToolbarManager`: draggable floating window with state buttons
  (⏱ auto / 👆 manual / 🔄 redo / 🔁 flip). Toolbar is `GONE` during capture so it never
  enters the captured frame. The flip button cycles `config.flipMode` and updates its label.
- `MirrorService`: state machine `WAITING -> OPERATING_AUTO|OPERATING_MANUAL -> SHOWING`.
  Auto mode captures after `config.pauseDuration` (ms). Manual mode captures when the
  foreground notification "完成截图" action (`ACTION_MANUAL_DONE`) is tapped.
  Foreground service type is `mediaProjection`; notification channel required.
  Also registers a debug `BroadcastReceiver` for `ACTION_DEBUG` (see below).
- `MainActivity`: permission order is SYSTEM_ALERT_WINDOW -> POST_NOTIFICATIONS (API 33+)
  -> MediaProjection prompt. Extends `AppCompatActivity` (needs `registerForActivityResult`,
  not framework `Activity`).

## Config

`MirrorConfig` wraps `SharedPreferences`: `pause_duration` (default 5000ms),
`toolbar_x/y` (last toolbar position), `render_fps_cap` (unused by snapshot engine).
`flip_mode` (0/1/2, see `flipMode` above).

## Debugging with ADB (what needs human taps vs. what is automated)

The app has a debug `BroadcastReceiver` (`MirrorService.ACTION_DEBUG`) on the running
service that drives the state machine, so most flows can be exercised WITHOUT touching
the screen. ADB path: `C:\Users\liyih\AppData\Local\Android\Sdk\platform-tools\adb.exe`
(device already connected; use `adb devices` to confirm).

Commands (run from PowerShell):

```
$adb = "C:\Users\liyih\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n com.liyihc.screenflipper/.MainActivity        # launch / prepare UI
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd start   # request projection
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd auto    # auto capture (5s)
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd manual  # manual mode
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd done    # trigger manual capture
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd reset   # back to WAITING
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd flip    # cycle flip mode
& $adb logcat -d -s ScreenFlip                                          # app logs
```

### What REQUIRES a human tap (cannot be automated)

1. **The MediaProjection consent dialog.** When `cmd start` is sent (or the toolbar
   "开始" button is tapped), Android shows "是否允许截取屏幕". A human MUST tap
   **允许/立即开始** — there is no API to grant this programmatically. This is the only
   manual step. After granting, the service reaches `beginCapture` and all subsequent
   `auto`/`manual`/`done`/`reset`/`flip` commands run hands-free.

### Typical verification loop

```
launch -> cmd start -> [HUMAN taps allow] -> cmd auto -> wait ~7s -> cmd reset ->
cmd flip -> cmd auto -> ... (cycle flip modes, confirm DisplayActivity shows each)
```

To dismiss the flipped-image `DisplayActivity`, tap the screen (it `finish()`es).

## Gotchas

- Never render via a GL overlay on top of the live capture — it recurses. Capture is
  instantaneous and the toolbar is hidden before `captureFlipped()`.
- `getParcelableExtra` / `Notification.addAction` / `stopForeground(bool)` show
  deprecation warnings but compile; leave as-is unless migrating to new APIs.
- MIUI/HyperOS clamp `TYPE_APPLICATION_OVERLAY` windows to `alpha=0.8` regardless of
  `params.alpha` — use `DisplayActivity` (opaque) for the flipped image instead.
- Register debug `BroadcastReceiver` with `Context.RECEIVER_EXPORTED` (targetSdk 36
  requires an explicit exported flag; `am broadcast` is sent from shell uid, so
  `RECEIVER_NOT_EXPORTED` silently drops it).
- `acquireLatestImage()` on the render thread blocks forever on this device — always use
  `OnImageAvailableListener` + the 1.2s fallback, and re-post `onSnapshotReady` to main
  before touching any View.
