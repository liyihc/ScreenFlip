# Debugging with ADB (ScreenFlip)

## Environment paths (use these every session — don't rediscover)

```
$adb  = "C:\Users\liyih\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

- ADB binary lives under the Android SDK platform-tools.
- The system has **NO system JDK**, so `JAVA_HOME` must be set manually to Android
  Studio's bundled JBR before any `gradlew` invocation:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  ```
  (The old AGENTS.md note pointed at `C:\Program Files\Android\Studio\jbr` which does
  NOT exist — the real path has `Android Studio` in it.)

(device already connected; `& $adb devices` to confirm).

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

NOTE: all control commands go through the `ACTION_DEBUG` broadcast with a `cmd`
extra — including **stop** (`--es cmd stop`), which stops the service and cancels
all timers. Do NOT send `ACTION_STOP` via `am broadcast`; the service is not a
registered receiver for it and the broadcast is dropped (and `am start-service`
is blocked by the non-exported service permission).

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
   **允许/立即开始** — there is no API to grant this programmatically. This is the
   ONLY manual step. After granting, the service reaches `beginCapture` and all
   subsequent `auto`/`manual`/`reset`/`flip` commands + `ACTION_DISPLAY_CLOSE`
   run hands-free.
   (Each fresh app install / service restart re-arms this — the grant is per session.)

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

To dismiss the flipped-image `DisplayActivity`: tap the screen, or send the
`ACTION_DISPLAY_CLOSE` broadcast (required on devices that block `adb shell input`).

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

Procedure: `cmd start` -> [human taps allow] -> run `cmd manual` a few times with a
static screen and with an animated screen (e.g. a timer app running) -> compare the two
`LATENCY` markers. Expected: manual ≈ 1-2 vsyncs (resume frame production) + resize
recompose (~1-2 vsyncs) + decode, well under the old ~250ms fresh-frame cap. Auto-loop
total should stay ~5s countdown + ~small overhead.

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
  synchronously on a thread) is gone. The listener drains frames only while a capture is
  in progress and stays idle otherwise (backpressure-freeze: the ImageReader pool fills
  up, SurfaceFlinger stops producing, ~zero idle cost). The only non-listener
  `acquireLatestImage()` is the backlog drain at the start of `doCapture()` — it runs
  while the listener is idle and a frame is already queued, so it returns immediately
  (verify on device).
- Android 14+ forbids creating more than one `VirtualDisplay` per `MediaProjection`
  ("Don't take multiple captures by invoking MediaProjection#createVirtualDisplay
  multiple times on the same instance") — do NOT create/release a VD per capture.
  MirrorEngine keeps ONE VD for the whole projection session and freezes the mirror
  via listener backpressure when idle (do NOT `setSurface(null)` — detaching breaks
  frame production on this device).
- VirtualDisplay flag `FLAG_PUBLIC` produced no frames here; `FLAG_AUTO_MIRROR` works.
- MIUI/HyperOS clamp `TYPE_APPLICATION_OVERLAY` to `alpha=0.8` regardless of
  `params.alpha` — use `DisplayActivity` (opaque) for the flipped image.
- Never render a GL overlay on top of live capture — it recurses.
