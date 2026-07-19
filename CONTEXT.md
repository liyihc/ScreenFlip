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

**Auto Operation**:
An Operation where the Frame is captured automatically after a fixed Pause
Duration elapses.
_Avoid_: timed capture

**Manual Operation**:
An Operation where the Frame is captured only after the user signals completion
(e.g. taps the notification "完成截图").
_Avoid_: on-demand capture

**Pause Duration**:
The delay (default 5000 ms) between arming an Auto Operation and the automatic
capture of the Frame.
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
replaces the screen content while visible, and a tap dismisses it.
_Avoid_: overlay, preview (those refer to different things — see Overlay)

**Overlay**:
A hidden full-screen black backdrop window used only to blank the screen during
capture. It does NOT show the flipped image. Confusingly named in code; in the
domain it is a capture backdrop, distinct from the Display.
_Avoid_: preview, flipped view

**Toolbar**:
The draggable floating window that lets the user arm Operations, cycle Flip Mode,
and exit. Hidden while an Operation is in flight.
_Avoid_: controls, panel

### State

**State**:
The MirrorService state-machine value: IDLE, WAITING, OPERATING_AUTO,
OPERATING_MANUAL, SHOWING. It tracks where the app is in the Operation lifecycle.
_Avoid_: status (status is the human-readable label shown in the Toolbar/notification; State is the machine value)
