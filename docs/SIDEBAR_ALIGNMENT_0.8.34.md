# Sidebar alignment 0.8.34

The native titlebar vertically centers its caption container. Hiding the toggle
with a zero-width, zero-height modifier moved its container and the shortcut
anchor down by half the caption height. The previous render fixture used a
top-aligned Box, so it missed that native layout behavior.

Changes:

- Keep the native caption height while hiding its width, content and interactions.
- Anchor shortcuts to the caption's outer coordinates instead of the zero-height
  marker or inner coordinates. Skip preceding plugin marker siblings when locating
  the original caption.
- Reproduce the native vertically centered row in the transformer render fixture.
  Assert equal shortcut y positions before hiding, while hidden, and after restore
  at 100%, 125%, 150% and 200% display scaling.
- Preserve sidebar recovery, optional-hook isolation, platform entries and native
  toggle restoration from 0.8.33.

Targeted tests: 192 sidebar render checks, 40 settings checks and 66 native UI
checks passed against installed Salt 1.19.3 classes with an isolated bridge.
No installed files, real credentials, queues or native audio output are touched.
Import 0.8.34 and fully exit/restart Salt for in-app verification.
