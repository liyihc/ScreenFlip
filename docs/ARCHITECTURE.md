# Architecture (ScreenFlip)

Kotlin Android app, namespace `com.liyihc.screenflipper`. Screen-mirror tool:
captures one screen frame via `MediaProjection` + `ImageReader`, flips it on the
CPU, and shows it in a full-screen opaque `Activity` (not an overlay — MIUI clamps
`TYPE_APPLICATION_OVERLAY` to `alpha=0.8`).

## Components

- **MirrorEngine**: captures ONE frame via `ImageReader` + `VirtualDisplay`, returns the
  raw `Bitmap` (flip is applied later by `DisplayActivity` as a GPU view transform).
  - **Paused mirror when idle (battery)**: the `VirtualDisplay` is created ONCE per
    projection session — Android 14+ forbids calling `createVirtualDisplay` more than
    once on the same `MediaProjection` ("Don't take multiple captures..."). So the VD
    stays alive, but the `OnImageAvailableListener` does NOT `acquireLatestImage()`
    when idle: the ImageReader buffer pool fills up, SurfaceFlinger is blocked by
    backpressure and stops producing frames — the mirror freezes at ~zero cost.
    (Do NOT `virtualDisplay.setSurface(null)` to detach: on this device detaching
    breaks frame production permanently, so the surface stays attached for the whole
    session.)
    `captureFlipped()` (called right after the toolbar is hidden) first drains the
    backlog (frees a buffer slot, unfreezing the producer), switches the listener into
    capture mode, waits `HIDE_SETTLE_MS` (33ms) for the toolbar GONE to reach the
    compositor, then `resize`s the VD (shrink 1px then restore) to force SurfaceFlinger
    to recompose — producing a frame that structurally cannot contain the toolbar — takes
    the latest post-resize frame (`RESIZE_FRAME_WAIT_MS` 200ms), and switches the
    listener back to idle (mirror freezes again). Typical capture is a few vsyncs +
    decode; idle cost is ~zero.
  - Decode (`FrameRepacker`) copies rows in bulk (`System.arraycopy` per row) instead of
    per-pixel `ByteBuffer.put`.
  - `MediaProjection.registerCallback(...)` MUST be called before `createVirtualDisplay()`.
  - `onRawFrameReady`/`onCaptureError` fire off the main thread; MirrorService re-posts to main.
- **DisplayActivity flip = GPU view transform**: no CPU pixel work. The raw frame is set on
  the `ImageView` and the flip is done by hardware rendering (HWUI → GPU) via View properties:
  180° = `rotation(180)`, left-right mirror = `scaleX(-1)`, mirror+180° = vertical flip =
  `scaleY(-1)`, none = identity. Pivot defaults to the view center, so ±scale mirrors exactly.
- **DisplayActivity**: full-screen opaque Activity showing the raw `Bitmap` with the flip
  transform above (`translucent=false`, black bg). Tap to `finish()`. Bitmap passed
  in-process via `FlipBitmapHolder` (no Intent parceling of large bitmaps). Bitmap ownership
  stays with `AppState` (`setRawFrame` recycles); the Activity only displays it.
- **OverlayManager**: black backdrop `ImageView`, only used hidden during capture.
  The flipped image is shown by `DisplayActivity`, not the overlay.
- **ToolbarManager**: draggable floating window. Buttons: ⏱ auto / 👆 manual /
  🔄 redo / 🔁 flip. Hidden (`GONE`) during capture. Flip button cycles `flipMode`.
- **MirrorService**: state machine `WAITING -> CAPTURING -> SHOWING`. The Auto Loop's
  countdown is a layer on WAITING (`autoEnabled` + a scheduled capture). Auto captures
  after `pauseDuration` ms; manual captures immediately on toolbar tap; both enter
  `CAPTURING` (Toolbar hidden, Frame in flight). Foreground `mediaProjection` service.
  Also hosts the `ACTION_DEBUG` BroadcastReceiver (see DEBUGGING.md).
- **MirrorConfig**: `SharedPreferences` wrapper — `pause_duration` (5000ms),
  `toolbar_x/y`, `render_fps_cap` (unused), `flip_mode` (0/1/2).
- **MainActivity**: permission order SYSTEM_ALERT_WINDOW -> POST_NOTIFICATIONS (API33+)
  -> MediaProjection prompt. `AppCompatActivity` (needs `registerForActivityResult`).
