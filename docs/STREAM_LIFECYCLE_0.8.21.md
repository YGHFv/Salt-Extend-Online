# 0.8.21 Scoped stream lifecycle

## Observed hang (2026-11-08)

Two read-only JVM thread samples, 46 seconds apart, showed the AWT event
thread in native `BASS_ChannelFree`, with unchanged thread CPU time. There
was no reported Java monitor deadlock. Plugin catalog and prefetch workers
were idle. Windows' `Responding` flag did not detect this hang.

The host log showed one online handle opening, followed 33 seconds later by
repeated localhost HTTP 404 failures at the same 1 MiB offset. Tens of
thousands of retries were followed by connection-allocation errors. After
the plugin was hot-updated, the old localhost listener disappeared while
the same native handle continued receiving connection-refused errors.
There was no intervening successful stream close/open in that sequence.

The initial failure predates installation of 0.8.20. Its precise original
lease-expiration or revocation trigger cannot be established from the
available logs. Native lock ownership was not captured, so this is not
proof that every BASS stall has the same cause. No credentials, media URLs,
process heaps, screenshots or proprietary host sources are published here.

## Fix

`ScopedNativeStreams` decorates the host's file callbacks using its runtime
injection seam. It does not replace the audio engine, ship host classes or
patch files in the Salt installation. Capability checks run before opening
a source; incompatible hosts fail closed rather than fall back to the old
unguarded path.

- Track a scope before source creation, including opens racing with unload.
- Match the host opening stream by exact callback-structure identity, never
  by shared owner enum or a reusable native handle number.
- Close the local callback gate immediately on revocation/unload. Dispatch
  source aborts to bounded-concurrency daemon workers; never wait for a
  callback lock on the UI or transport thread. At most 128 live scopes are
  accepted. Executor threads time out and shut down after unload/retirement.
- Treat zero bytes before physical EOF as terminal after the existing
  bounded transport retry policy. Further reads return EOF locally without
  calling the host reader or producing an HTTP/log storm. Failed URI leases
  are revoked asynchronously. Queue caches check lease availability before
  reusing a URI and resolve a new lease on subsequent playback.
- Keep normal EOF seekable: decoders may probe the file tail during open and
  users can seek backward. Do not poison a valid lease at EOF.
- Notify stream scopes before removing a lease or closing its listener.
  Cover expiry, account revocation, explicit retirement and server shutdown.
- Leave all native freeing to the host, including cancellation during
  creation. Do not call global `abortActiveStreams(Unspecified)` or clear
  the user's queue. Other plugins can share that owner value safely.

The scope retains its callbacks until the host closes the stream. The fix
does not force native channel release from a callback, an AWT event, or a
plugin watchdog. An already-blocked native call from an older plugin still
requires a full application restart.

## Regression coverage

`NativeStreamLifecycleTest` uses the installed host's HTTP source and real
file read/seek/close callbacks, plus a JNA callback trampoline. Native audio
handles are synthetic: no BASS playback device, real account, platform API,
playlist modification or audible output is involved.

Cases include successful reading, flags preservation, EOF and seek-back,
persistent 502 followed by 1,000 repeated callback invocations with no new
HTTP requests, failure notification, URI-specific revocation, unrelated
Unspecified-owner stream isolation, unload, transport shutdown, cancellation
during native creation, and unload while an HTTP read is blocked. The latter
checks prompt unload return and bounded read/close completion without first
releasing the blocked synthetic upstream.

Queue replay and lazy-playlist tests also revoke an otherwise unexpired
lease and verify that replay creates a new URI. Existing tests retain the
three-attempt maximum for transient failures, no retries for permanent
responses, complete-range validation and account isolation.

## Remaining acceptance boundary

Synthetic native handles cannot prove the behavior of every real BASS
decoder, audio device or driver during teardown. The targeted failure path
is covered; real continuous playback and device changes remain manual
acceptance work. Persistent network failures now end this stream instead of
retrying indefinitely; seamless mid-track URL renewal is not implemented.
Content longer than the maximum lease lifetime still needs separate work.

Install with Salt fully stopped, keep only one package of this plugin ID in
the plugin scan directory, and restart Salt. Preserve account/configuration
storage and the host queue. Old ephemeral online queue paths may need to be
added again from the platform page after restart; the plugin does not erase
or silently replace them.

## Local verification and installation

- Full build: 52 inventoried plugin test entry points, 8 host-free plugin
  contracts, 70 core policy checks and 6 Python inspector tests passed.
- New host callback suite: 1,044 assertions, including the 1,000-call
  no-retry-storm loop. Eight additional repeated runs passed.
- Existing UI: 239 page/pagination, 359 search/account and 964 playlist/layout
  checks passed. The build verified 290 emitted native UI ABI references;
  playback lifecycle reflection is exercised separately by the new test.
- Artifact: 252 entries, 602,944 bytes. Every packaged file was compared with
  its corresponding file in the fresh verified build directory.
- SHA-256: `71379BE503F6CCA45327175DF562CBC607411C65581DD41AD878F05D9D5BD536`.
- Installed with Salt fully stopped. Normal exit did not release the hung
  JVM within six seconds, so only that verified Salt process was terminated.
  Old plugin files were backed up outside the scan directory. Credentials,
  preferences and queue storage were not edited.
- The restarted application extracted the expected version, reported a
  responsive non-safe-mode session and started plugin workers. Its AWT
  thread was back in the normal event wait instead of `BASS_ChannelFree`.
  No manual platform playback was initiated for this verification.
