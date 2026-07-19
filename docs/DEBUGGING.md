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
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd auto    # auto capture (~5s)
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd manual  # manual mode
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd done    # trigger manual capture
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd reset   # back to WAITING
& $adb shell am broadcast -a com.liyihc.screenflipper.ACTION_DEBUG --es cmd flip    # cycle flip mode
& $adb logcat -d -s ScreenFlip                                          # app logs
```

## What REQUIRES a human tap (cannot be automated)

1. **MediaProjection consent dialog.** When `cmd start` is sent (or the toolbar
   "开始" button is tapped), Android shows "是否允许截取屏幕". A human MUST tap
   **允许/立即开始** — there is no API to grant this programmatically. This is the
   ONLY manual step. After granting, the service reaches `beginCapture` and all
   subsequent `auto`/`manual`/`done`/`reset`/`flip` commands run hands-free.
   (Each fresh app install / service restart re-arms this — the grant is per session.)

## Typical verification loop

```
launch -> cmd start -> [HUMAN taps allow] -> cmd auto -> wait ~7s -> cmd reset ->
cmd flip -> cmd auto -> ... (cycle flip modes, confirm DisplayActivity shows each)
```

To dismiss the flipped-image `DisplayActivity`, tap the screen.

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
