# Sidebar recovery 0.8.33

## Cause

Salt's actual method resolver rejects methods containing a Compose `Composer`
parameter with `IllegalArgumentException: Use a Compose hook`. The 0.8.32
sidebar-toggle hook targeted such a method. Its registration failure propagated
out of UI construction and closed the platform entries and page binding.

## Fix

- Use the supported `afterComposable` caption call site. Nonzero native default
  masks are retained; no direct Compose method advice or argument replacement.
- A zero-size positioned marker identifies its preceding native caption sibling.
  Toggle hiding clears only that caption subtree's modifiers and prevents placement.
  Original modifiers are restored when the switch is disabled or content is disposed.
- Capability-check the node accessors. Optional sidebar-settings registration
  failure no longer rolls back platform entries or page content.
- The repair action retries missing sidebar capabilities without tearing down
  the working UI. Diagnostics name the sidebar-settings registration stage.

## Verification

Targeted offline checks cover the actual host resolver rejection, legal
registrations, optional failure/retry, all three sidebar switches enabled,
toggle restoration, platform navigation and permission loss. Tests use the
installed Salt 1.19.3 classes with an isolated bridge, not a running user session.
No credentials, installed files or user playlists are modified.

Final build: 67 contracts / 92 host and base-module executions passed,
including 180 sidebar rendering checks and 40 sidebar settings checks.
The independent 10-contract host-free suite and 70 core checks passed.
All 320 packaged files match the compiled build byte for byte.

Artifact SHA-256:
`30E1B9481CEB6527F4DCA2BFF9B658744ED876A2CF0CC994171C3516C06A5F97`

Import the 0.8.33 package and fully exit/restart Salt. In-app verification remains
necessary; offline rendering is not an audible playback test.
