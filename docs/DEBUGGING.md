# Debugging with ADB (ScreenFlip)

ADB: `C:\Users\liyih\AppData\Local\Android\Sdk\platform-tools\adb.exe`
(device already connected; `adb devices` to confirm).

The app registers a debug `BroadcastReceiver` (`MirrorService.ACTION_DEBUG`) on the
running service that drives the state machine, so most flows run WITHOUT touching the
screen.

## Commands (PowerShell)

```
$adb = "C:\Users\liyih\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n com.liyihc.screenflipper/.MainActivity        # launch / prepare UI
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd start   # request projection
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd auto    # TOGGLE Auto Mode on/off
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd manual  # manual: capture immediately
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd reset   # back to WAITING
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd flip    # cycle flip mode
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DISPLAY_CLOSE          # dismiss the preview (DisplayActivity)
& $adb logcat -d -s ScreenFlip                                          # app logs
```

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
`restoreRunnable firing captureFlipped` / `Display dismissed` -> `onDisplayDismissed`.

To dismiss the flipped-image `DisplayActivity`: tap the screen, or send the
`ACTION_DISPLAY_CLOSE` broadcast (required on devices that block `adb shell input`).

Pure-UI items NOT reachable via ADB (verify by eye on-device): the seconds input
box, long-press-title compact toggle, and the Auto icon's blue border.

## Gotchas (things that broke during dev)

- `MediaProjection.registerCallback(...)` BEFORE `createVirtualDisplay()` or it throws
  `IllegalStateException` ("Must register a callback before starting capture").
- Debug `BroadcastReceiver` must use `Context.RECEIVER_EXPORTED` (targetSdk 36 requires
  an explicit flag; `am broadcast` comes from shell uid, so `RECEIVER_NOT_EXPORTED`
  silently drops it).
- `acquireLatestImage()` on the render thread blocks forever — use
  `OnImageAvailableListener` + 1.2s fallback, and re-post `onSnapshotReady` to main
  before touching any View.
- VirtualDisplay flag `FLAG_PUBLIC` produced no frames here; `FLAG_AUTO_MIRROR` works.
- MIUI/HyperOS clamp `TYPE_APPLICATION_OVERLAY` to `alpha=0.8` regardless of
  `params.alpha` — use `DisplayActivity` (opaque) for the flipped image.
- Never render a GL overlay on top of live capture — it recurses.
