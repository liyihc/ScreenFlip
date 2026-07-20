# ScreenFlip — Domain Context

ScreenFlip is an Android tool that captures a single frame of the device screen,
transforms it on the CPU, and shows the transformed frame in a full-screen opaque
Activity so the user sees a flipped view of their own screen.

## Language

### Core domain

**Frame**:
A single captured snapshot of the device screen, as raw pixel data from the
display pipeline. The app captures exactly one frame per operation — it is not a
video stream.
_Avoid_: screenshot, image, capture (use "Frame" as the noun; "capture" is the verb)

**Flip**:
The CPU-side geometric transformation applied to a captured Frame to produce the
visible output. The kind of Flip is selected by the Flip Mode.
_Avoid_: transform, rotate, mirror (these name specific Flip Modes, not the general act)

**Flip Mode**:
An enumerable selector (0/1/2) determining which geometric transformation a Flip
applies. `0` = rotate 180°, `1` = left-right mirror, `2` = left-right mirror then
rotate 180°.
_Avoid_: flip type, orientation

**Snapshot**:
The finished, flipped Bitmap ready to be displayed — the product of capturing a
Frame and applying a Flip.
_Avoid_: output image, result bitmap

### Operation lifecycle

**Operation**:
One capture-and-display cycle: an armed capture of a single Frame followed by a
Flip and the showing of the resulting Snapshot. Auto and Manual are the two kinds
of Operation.
_Avoid_: capture session, run

**Auto Mode**:
A persistent (while-running) mode driven by an in-memory `autoEnabled` switch.
When on, the app runs an **Auto Loop**: each time the user dismisses the Display
and returns to the Toolbar, a Pause Duration countdown starts and, on elapse,
automatically captures a Frame and re-enters the Display — repeating until the
switch is turned off or the service exits. Auto Mode is one of two parallel,
independent modes alongside Manual Mode.
_Avoid_: timed capture, auto operation (singular — it is a loop, not a one-shot)

**Auto Loop**:
The repeating cycle within Auto Mode: dismiss Display → wait Pause Duration →
auto-capture Frame → show Display → dismiss → … The countdown anchor is the
moment the Display is dismissed, not the moment the Auto switch is flipped.
_Avoid_: timer, cycle

**Manual Mode**:
The other mode alongside Auto Mode. A single Manual Operation: the user taps
"手动" and the Frame is captured immediately, entering the Display. Manual has
priority over Auto: while a Manual-captured Display is shown, the Auto Loop is
suspended; on dismiss, the Auto Loop resets its countdown from zero.
_Avoid_: on-demand capture

**autoEnabled**:
In-memory boolean switch for Auto Mode. NOT persisted (not in DataStore) — must
be turned on manually each run.
_Avoid_: auto state, auto flag

**Pause Duration**:
The interval (user-set in seconds, default 5s = 5000 ms) between dismissing the
Display and the next automatic Frame capture in the Auto Loop.
_Avoid_: countdown, delay, timer

**Arming**:
The act of entering the operating state for an Operation — the app is committed to
capturing a Frame but has not yet done so.
_Avoid_: starting capture

### Engine & pipeline

**Projection**:
The OS-granted permission/session (Android `MediaProjection`) that allows the app
to read the screen. It must be granted by a human tap on every fresh install or
service restart and cannot be obtained programmatically.
_Avoid_: capture permission, screen permission (too vague — it is specifically a MediaProjection grant)

**Display**:
The full-screen opaque Activity that shows the Snapshot. It is not an overlay; it
replaces the screen content while visible, and a tap dismisses it. Historically the
flipped image was also shown via a separate Overlay window, but that was removed —
the Display is now the only full-screen window and the sole renderer of the Snapshot.
_Avoid_: overlay, preview

**Toolbar**:
The draggable floating window (悬浮窗) that lets the user arm Operations, cycle
Flip Mode, and exit. Kept visible during the Auto Loop countdown; hidden only for
the instant a Frame is captured.
_Avoid_: controls, panel, preview window (the preview is the Display, a different thing)

**Toolbar Manipulation gesture**:
The touch interaction on the Toolbar's title bar, resolving into *Reposition*
(movement >10px) or *Compact Toggle* (400ms elapse with no >10px movement),
whichever fires first. The toggle fires on timer elapse; the gesture locks once
resolved.
_Avoid_: drag, long-click (these name one outcome, not the gesture as a whole)

**Preview Window**:
The full-screen Display Activity showing the Snapshot. "预览窗" = Display,
"悬浮窗" = Toolbar; never conflate.
_Avoid_: floating window, overlay

**Compact Mode**:
A Toolbar presentation showing only the Auto and Manual controls as icons (`⏱` /
`👆`) on a single row, with other buttons and the pause-input hidden. The Auto
control indicates its on/off state via background highlight. Full Mode shows the
same controls with text, Auto and Manual each on their own row, the Auto row
carrying the pause-input + `S` unit.
_Avoid_: mini mode, collapsed

### State

**State**:
The MirrorService state-machine value: IDLE, WAITING, OPERATING_AUTO,
OPERATING_MANUAL, SHOWING. It tracks where the app is in the Operation lifecycle.
Auto Mode's looping is layered on top of these States via `autoEnabled` + a
suspended/resumed countdown, rather than living entirely inside OPERATING_AUTO.
_Avoid_: status (status is the human-readable label shown in the Toolbar/notification; State is the machine value)

### Runtime state & MVVM

**AppState (singleton)**:
A process-wide `object` holding the in-memory, ephemeral runtime state as
`StateFlow`s. Holds: `autoEnabled`, `rawFrame`, `isDisplayShowing`, `flipMode`,
`compactMode`, `showText`. NOT persisted. Distinct from MirrorConfig.
_Avoid_: ViewModel (it is not an Android ViewModel), config (config is the persisted layer)

**rawFrame**:
The latest captured but not-yet-flipped Frame, in `AppState.rawFrame` as
`StateFlow<Bitmap?>`. The Display subscribes to `rawFrame` + `flipMode` and computes
the Snapshot itself.
_Avoid_: snapshot (snapshot is the flipped, display-ready product), image

**persisted config vs runtime state**:
Two separate layers: MirrorConfig (DataStore) owns durable user settings;
AppState (singleton StateFlows) owns volatile runtime facts. They coexist.
_Avoid_: treating them as one store
