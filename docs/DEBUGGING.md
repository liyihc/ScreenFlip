# Debugging with ADB (ScreenFlip)

## Environment paths (use these every session — don't rediscover)

```
$adb  = "C:\Users\liyih\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

No system JDK — `JAVA_HOME` must be Android Studio's bundled JBR (note the space in
`Android Studio`). Just install directly — no need to check device connection.

## Known hang (unsolved)

The first `gradlew` call in a session can hang: the spawned daemon inherits the
redirected stdout/stderr pipe, so the command never finishes and gets killed by the tool
timeout. A `Start-Job` warm-up did NOT reliably fix it — the job's daemon ended up stopped
and not reusable ("2 stopped Daemons could not be reused"), and the next call started a
fresh daemon that hung again. Workaround for now: just retry; the second call usually
reuses a daemon and completes. (Revisit with `gradlew --status` / proper daemon handling.)

The app registers a debug `BroadcastReceiver` (`MirrorService.ACTION_DEBUG`) on the
running service that drives the state machine, so most flows run WITHOUT touching the
screen.

## Commands (PowerShell)

```
& $adb shell am start -n com.liyihc.screenflipper/.MainActivity        # launch / prepare UI
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd start   # request projection
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd auto    # TOGGLE Auto Mode on/off
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd manual  # manual: capture immediately
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd reset   # back to WAITING
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd flip    # cycle flip mode
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd stop    # stop service + cancel everything
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DISPLAY_CLOSE          # dismiss the preview (DisplayActivity)
& $adb logcat -d -s ScreenFlip                                          # app logs
```

NOTE: all control commands go through the `ACTION_DEBUG` broadcast with a `cmd` extra —
including **stop** (`--es cmd stop`). Do NOT send `ACTION_STOP` via `am broadcast`: the
service is not a registered receiver for it (dropped), and `am start-service` is blocked
by the non-exported service permission.

Behavior notes (post auto-loop redesign):
- `cmd manual` captures a Frame **immediately** and shows the Display (no
  notification "完成截图" step anymore).
- `cmd auto` is a **toggle**. Turning it on while at WAITING starts the first
  Pause Duration countdown right away. While on, each time the Display is
  dismissed the countdown restarts (the Auto Loop). Turning it off stops the loop.
- The Auto switch is in-memory only; it resets to off on every service restart.

Note: on devices that block `adb shell input` (e.g. HyperOS: "Injecting input
events requires INJECT_EVENTS permission"), use the `ACTION_DISPLAY_CLOSE`
broadcast above to close the flipped-image preview instead of tapping the screen.

## What REQUIRES a human tap (cannot be automated)

1. **MediaProjection consent dialog.** When `cmd start` is sent (or the toolbar
   "开始" button is tapped), Android shows "是否允许截取屏幕". A human MUST tap
   **允许/立即开始** — no API can grant this. This is the ONLY manual step; everything
   after runs hands-free. The grant re-arms on every fresh install / service restart.

## Typical verification loop

```
launch -> cmd start -> [HUMAN taps allow]
  # manual immediate capture:
  -> cmd manual -> (Display shows) -> ACTION_DISPLAY_CLOSE
  # auto loop:
  -> cmd auto (on) -> wait ~7s -> (Display auto-shows) -> ACTION_DISPLAY_CLOSE
     -> wait ~7s -> (Display auto-shows again) -> ... -> cmd auto (off)
  # flip modes:
  -> cmd flip -> cmd manual -> ACTION_DISPLAY_CLOSE (repeat, confirm each mode)
```

Expected log markers: `onManualClicked` / `auto scheduled capture in Nms` /
`restoreRunnable firing captureFlipped` / `LATENCY capture decode Nms` /
`LATENCY capture->display Nms` / `Display dismissed` -> `onDisplayDismissed`.

Pure-UI items NOT reachable via ADB (verify by eye on-device): the seconds input
box, long-press-title compact toggle, and the Auto icon's blue border.

## Measuring capture latency

The code logs one-shot latency markers (tag `ScreenFlip`) — grep them to compare before/after:

```
& $adb logcat -d -s ScreenFlip | Select-String "LATENCY"
```

- `LATENCY capture decode Nms` — time from `captureFlipped()` until the raw bitmap is
  ready (backlog drain + toolbar-hide settle + resize recompose + decode).
- `LATENCY capture->display Nms` — time from `captureNow()`/`restoreRunnable` until
  `DisplayActivity` is launched (includes the on-demand capture + decode +
  activity cold start).

Procedure: `cmd start` -> [human taps allow] -> `cmd manual` a few times on a static
screen and on an animated one -> compare the two `LATENCY` markers. Expected: manual
≈ 1-2 vsyncs (resume production) + resize recompose (~1-2 vsyncs) + decode, well under
the old ~250ms fresh-frame cap; auto-loop ≈ 5s countdown + small overhead.

Pure-CPU regressions are covered by host unit tests (`:app:testDebugUnitTest`):
`FrameRepackerTest` (row-padding handling + `perf_1080x2400_withPadding`). The flip itself
is now a GPU view transform (`rotation`/`scaleX`/`scaleY` on the `ImageView`), so there is
no pure-CPU flip logic to test — verify the transform mapping on device.

## Gotchas (things that broke during dev)

- `MediaProjection.registerCallback(...)` BEFORE `createVirtualDisplay()` or it throws
  `IllegalStateException` ("Must register a callback before starting capture").
- Debug `BroadcastReceiver` must use `Context.RECEIVER_EXPORTED` (targetSdk 36 requires
  an explicit flag; `am broadcast` comes from shell uid, so `RECEIVER_NOT_EXPORTED`
  silently drops it).
- The old `acquireLatestImage()` fallback (blocked forever on this device when called
  synchronously on a thread) is gone. The listener drains frames only during a capture
  and stays idle otherwise: the ImageReader pool fills, SurfaceFlinger stops producing —
  ~zero idle cost. The only non-listener `acquireLatestImage()` is the backlog drain at
  the start of `doCapture()`; it runs while the listener is idle with a frame already
  queued, so it returns immediately (verify on device).
- Android 14+ forbids calling `createVirtualDisplay` more than once per
  `MediaProjection` ("Don't take multiple captures...") — do NOT create/release a VD
  per capture. MirrorEngine keeps ONE VD for the whole session and freezes the mirror
  via listener backpressure when idle (do NOT `setSurface(null)` — detaching breaks
  frame production on this device).
- VirtualDisplay flag `FLAG_PUBLIC` produced no frames here; `FLAG_AUTO_MIRROR` works.
- MIUI/HyperOS clamp `TYPE_APPLICATION_OVERLAY` to `alpha=0.8` regardless of
  `params.alpha` — use `DisplayActivity` (opaque) for the flipped image.
- Never render a GL overlay on top of live capture — it recurses.
