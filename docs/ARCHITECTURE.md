# Architecture (ScreenFlip)

Kotlin Android app, namespace `com.liyihc.screenflipper`. Screen-mirror tool:
captures one screen frame via `MediaProjection` + `ImageReader`, flips it on the
CPU, and shows it in a full-screen opaque `Activity` (not an overlay — MIUI clamps
`TYPE_APPLICATION_OVERLAY` to `alpha=0.8`).

## Components

- **MirrorEngine**: captures ONE frame via `ImageReader` + `VirtualDisplay`, flips on
  CPU per `config.flipMode`, returns a `Bitmap`.
  - `flipMode`: `0`=rotate 180° (default), `1`=left-right mirror, `2`=mirror+rotate 180°,
    `3`=no flip (returns source unchanged).
  - Capture is low-latency by design: the `OnImageAvailableListener` continuously swaps
    a cached "latest frame" (`heldImage`, reference-only, no decode). `captureFlipped()`
    waits up to `FRESH_FRAME_WAIT_MS` (250ms) for a frame produced *after* a caller-supplied
    timestamp (manual mode passes the toolbar-hide time so the screenshot excludes the
    toolbar), then decodes the cache on a dedicated `MirrorCapture` executor thread.
    If the screen is static and no frame is produced, the cached frame is used (on a static
    screen the cache *is* the current frame). First frame waits up to `FIRST_FRAME_WAIT_MS`
    (1500ms). Decode (`FrameRepacker`) copies rows in bulk (`System.arraycopy` per row)
    instead of per-pixel `ByteBuffer.put`.
  - `MediaProjection.registerCallback(...)` MUST be called before `createVirtualDisplay()`.
  - `onSnapshotReady`/`onCaptureError` fire off the main thread; MirrorService re-posts to main.
- **FlipUtils**: pure `flipPixels(IntArray, w, h, mode)` fast paths — 180° = whole-array
  reversal, mirror = per-row reversal, mirror+180° = row-order reversal, none = identity.
  `applyFlip` on the main thread is now cheap (~single-digit ms at 1080×2400 on host JVM).
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
