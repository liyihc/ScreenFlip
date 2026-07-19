# Architecture (ScreenFlip)

Kotlin Android app, namespace `com.liyihc.screenflipper`. Screen-mirror tool:
captures one screen frame via `MediaProjection` + `ImageReader`, flips it on the
CPU, and shows it in a full-screen opaque `Activity` (not an overlay — MIUI clamps
`TYPE_APPLICATION_OVERLAY` to `alpha=0.8`).

## Components

- **MirrorEngine**: captures ONE frame via `ImageReader` + `VirtualDisplay`, flips on
  CPU per `config.flipMode`, returns a `Bitmap`.
  - `flipMode`: `0`=rotate 180° (default), `1`=left-right mirror, `2`=mirror+rotate 180°.
  - Capture uses `OnImageAvailableListener` + 1.2s fallback `acquireLatestImage()`
    (synchronous `acquireLatestImage()` on a thread blocks forever on this device).
  - `MediaProjection.registerCallback(...)` MUST be called before `createVirtualDisplay()`.
  - `onSnapshotReady` fires on the render thread; MirrorService re-posts to main.
- **DisplayActivity**: full-screen opaque Activity showing the flipped `Bitmap`
  (`translucent=false`, black bg). Tap to `finish()`. Bitmap passed in-process via
  `FlipBitmapHolder` (no Intent parceling of large bitmaps).
- **OverlayManager**: black backdrop `ImageView`, only used hidden during capture.
  The flipped image is shown by `DisplayActivity`, not the overlay.
- **ToolbarManager**: draggable floating window. Buttons: ⏱ auto / 👆 manual /
  🔄 redo / 🔁 flip. Hidden (`GONE`) during capture. Flip button cycles `flipMode`.
- **MirrorService**: state machine `WAITING -> OPERATING_AUTO|OPERATING_MANUAL ->
  SHOWING`. Auto captures after `pauseDuration` ms; manual captures on notification
  "完成截图" (`ACTION_MANUAL_DONE`). Foreground `mediaProjection` service. Also hosts
  the `ACTION_DEBUG` BroadcastReceiver (see DEBUGGING.md).
- **MirrorConfig**: `SharedPreferences` wrapper — `pause_duration` (5000ms),
  `toolbar_x/y`, `render_fps_cap` (unused), `flip_mode` (0/1/2).
- **MainActivity**: permission order SYSTEM_ALERT_WINDOW -> POST_NOTIFICATIONS (API33+)
  -> MediaProjection prompt. `AppCompatActivity` (needs `registerForActivityResult`).
