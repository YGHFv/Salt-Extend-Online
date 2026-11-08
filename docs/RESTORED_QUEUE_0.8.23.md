# 0.8.23 Restored online queue playback

## Root causes

1. Salt scans extracted plugin directories as well as `.spmod` archives. Moving
   only the old archive left the 0.8.21 extracted directory eligible for loading.
   Actual loaded-class inspection confirmed the old implementation was active.
   The old archive AND extracted directory must be backed up outside the scan root.
2. 0.8.22 persisted explicitly saved playlist tracks but ordinary single/insert/all
   playback still used session-local random IDs and temporary artwork paths. Salt's
   restored media metadata could outlive the plugin's source mappings.
3. Salt's `initFromDisk` sets and prepares the restored selection. If that occurs
   before the plugin source is available, a later Play command alone can encounter
   Idle/unloaded state rather than prepare again.

## Changes

- Single and insertion submissions persist logical targets before native enqueue.
  The resolved lease is primed into the shared saved session, avoiding a second
  network resolution on first open. No native playlist/database write occurs just
  because the user plays an online song.
- Lazy full-collection queues use the same saved identities. Only the selected
  first item is warmed before replacing a queue. Existing native random ordering,
  next-three prefetch and first-item failure protection remain. Closing the list
  does not destroy mappings needed by the retained native queue.
- Saved source bindings are restored at startup when records exist. The adapter
  continues to own no native handles and never starts playback automatically.
- A small native `PlaybackService.play` before-hook checks the actual current
  selection, state, account permission and exact saved ID/path ownership. Only an
  Idle owned track gets adapter setup plus an enqueued Prepare ahead of the
  original Play. Ready, Buffering, Ended, foreign service/path and local tracks are
  untouched. Setup is followed by a selection recheck; repeated clicks are bounded.
  The hook never suppresses/repeats the original method, replaces a queue or does
  network I/O. No monitor is held across plugin setup to avoid shutdown lock inversion.

## Compatibility boundary

Old UUID-only full-collection queue items contain no recoverable platform target.
They must be selected once again from the platform page after upgrading. Do not
guess targets from titles or silently play the first Bilibili part for an unknown
CID. Newly generated queues and existing 0.8.22 saved playlists retain the same
public metadata across restarts. Platform login/permissions and network are still
required. Saved records are bounded to 10,000 and separate from the audio cache.

## Verification

- Native persistent playback contract includes single/three insert commands,
  primed first open, fresh runtime restoration of the same native queue object,
  lazy full-list first selection, close/reopen, ownership, URL renewal and account
  revocation. Fake network/audio only.
- Native recovery hook contract checks ABI, no startup action, owned Idle-only
  prepare-before-Play order, duplicate-click bound, permission revocation, setup-time
  selection change and close. No host playback singleton is initialized in tests.
- Existing native UI, playlist repository, prefetch, lifecycle and transport suites
  remain part of the full build. No user playlist, credential or screenshot data is
  used in tests.

Full local build passed all 56 inventoried plugin contracts and required host-free
repetitions, 70 core checks, six inspector tests and 297 emitted native ABI checks.
The independent host-free script passed as well. Persistence/restart coverage is
74 checks and the unloaded-Play hook adds 14 checks.

Artifact: `plugin-io.github.yghfv.saltneri.online-0.8.23-restored-queue.spmod`,
274 entries, 652,243 bytes. Every entry matches the verified build.
SHA-256: `745311B94B2696B1F233E68576720F3402DF909B103CF880C4194FF1FF3205CC`.
