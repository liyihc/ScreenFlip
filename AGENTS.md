# AGENTS.md

Android app (Kotlin, namespace `com.liyihc.screenflipper`). Screen-mirror tool: captures
one frame via `MediaProjection` + `ImageReader`, flips it on CPU, shows it full-screen
in an opaque `Activity`.

## Build (no system JDK — set manually)

```
$env:JAVA_HOME = "C:\Program Files\Android\Studio\jbr"
.\gradlew.bat assembleDebug
```

## Details live in docs (read only when needed)

- `docs/ARCHITECTURE.md` — components, flipMode, state machine.
- `docs/DEBUGGING.md` — ADB debug commands, verification loop, gotchas.

## Key facts for every session

- **Screen recording permission is ALWAYS a manual tap.** After `cmd start` (or the
  toolbar "开始" button), Android shows "是否允许截取屏幕" and a human MUST tap
  允许/立即开始. There is no API to grant this. It re-arms on every fresh app
  install / service restart. Everything after that grant is hands-free via ADB.
- Debug `BroadcastReceiver` (`MirrorService.ACTION_DEBUG`) drives the state machine;
  see `docs/DEBUGGING.md` for the full command table and verification loop.
- MIUI/HyperOS clamp `TYPE_APPLICATION_OVERLAY` to `alpha=0.8` (system limit) — the
  flipped image is shown by `DisplayActivity` (opaque), not an overlay.
