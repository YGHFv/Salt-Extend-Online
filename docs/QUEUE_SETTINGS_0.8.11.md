# Salt Extend Online 0.8.11

## Replay lifecycle

Single-track queue items previously lost their stream mapping and artwork as soon as the
short-lived media URL expired. A queued item's lifetime is now separate from its current
URL. On opening an expired/evicted item, its captured original target and account resolve
again through the normal permission, full-track and media identity checks. Nothing fetches
or replays in the background merely because a URL expires. The first playback reuses its
already-verified resolution; concurrent opens share one renewal. Errors remain retryable
by explicitly opening that item again, without a loop or additional queue entry.

The original Bilibili page target is retained; renewal must match both BVID and CID.
Metadata-only artwork paths remain stable across expiry/eviction. Eviction replaces image
data with a small valid FLAC metadata stub, not a deleted path; reopening restores artwork.
The same correction applies to the lazy playlist's eight-item URL/artwork cache, which
previously kept aliases to deleted files and generated another alias on each renewal.
Bindings/lyrics live until their owning session is retired, not until a signed URL expires.

Only eight live URLs/artworks per single-track session and per lazy playlist are retained;
up to 10000 logical single-track entries can exist without exhausting the 64-lease transport.
Platforms sharing an anonymous controller scope now use distinct sessions. Revoking one
platform revokes exact owned leases, not the other platform's anonymous session.
Account change, permission loss, stop and disable cancel pending renewal and remove bindings.

This is in-process queue replay, not persistent queue restoration across restarts. Already
invalid old-version items should be added from the online page once after upgrading. It
does not revive an old account's queue after logout or renew an already-open native stream
mid-read; very long continuous playback remains a separate lease-lifetime limitation.

## Settings

The experimental menu now contains eight entries: open online music, remember login,
Bilibili quality, NetEase quality, audio cache limit, clear cache, repair sidebar and
diagnostics. Removed only redundant configuration controls, not the platform-page login
and avatar account menus. Existing config keys for remembered login and quality survive.

Bilibili retains six NeriPlayer quality preferences. NetEase follows `PlayerUrlResolver.kt`
at the pinned reference revision: standard, higher, exhigh, lossless, hires, jyeffect, sky,
jymaster. Initial double-click, lazy playlist and renewal all read a settings snapshot.
Cached URLs are not reused after the quality preference changes. The new preference takes
effect on the next stream open, without forcing playback to stop. NetEase accepts a known
equal/lower server-returned tier, but does not bypass membership, paid-track or preview
checks and does not retry an upstream rejection through multiple quality requests.
Changing only the cache limit or remember-login option does not erase the visible catalog.

## Cache budget

The private range transport now caches only complete, validated ranges actually read by
Salt. The default plugin-owned disk budget is 2048 MiB (2 GiB), with 0/512/1024/4096/8192 MiB
choices. The limit is admission-controlled with LRU eviction and at most 8192 range files;
each range is at most 4 MiB. Lowering the limit trims immediately. Cache-disabled mode streams
without storing ranges. Clear-cache increments a generation so an earlier in-flight read
cannot repopulate it, and never revokes a stream or changes a queue/account.

The cache is lazy-created in a uniquely owned `saltneri-audio-cache-*` temporary directory.
Files contain audio bytes only, with random names, no URLs/cookies/catalog metadata. Cache
keys belong to a specific media lease, not merely a song ID; no reuse across account,
quality, signed-URL or expiry boundaries is assumed. Every hit still checks the live lease.
Expired/revoked leases purge their entries; disabling the runtime deletes exact owned files.
This is not persistent/offline downloading. A crash may leave that runtime's directory;
this version does not scan or delete another process's files. Filesystem errors skip new
cache admission and do not interrupt streaming; failed deletes remain counted.

The setting does not control Salt's own temporary per-stream sparse buffers or other mods.
Image/lyric metadata retains separate bounded caches; artwork stubs are kept to preserve
native queue paths. The cache UI and README state this scope explicitly.

## Windows media application identity

The 0.8.0 registry-only repair did not resolve the shell's app name: the user still saw
"Unknown app". SMTCAdapter creates a WinRT `Windows.Media.Playback.MediaPlayer`, rather than
an HWND-attached session. The observed Salt source ID is `Salt Player for Windows.exe`.
Changing the process AUMID after that session exists splits the session identity from the
new registration; the registry's DisplayName alone does not supply a Start Menu app record.

The repair now preserves the process identity. An existing explicit AUMID is respected;
otherwise the link uses the executable basename, matching Salt's implicit SMTC source.
It creates a real IShellLinkW in the current user's Programs known folder, under
`Salt Extend Online/Salt Player for Windows.lnk`. IPropertyStore persists System.AppUserModel.ID
and System.Title; the target, working directory and icon refer to the running Salt executable.
SHChangeNotify informs the shell of the new link. No host binaries, music metadata, existing
user shortcuts or machine-wide registry entries are modified. No second media session is created.

Only absent registry values are filled. A matching pre-existing link can be reused but is
never deleted; a conflicting link is left untouched and registration fails safely. Failed
registration rolls back only byte-identical owned links; directory cleanup is non-recursive.
Successful links PERSIST across stop/disable, so they exist before Salt creates SMTC on its
next launch. Deleting the link on every exit would recreate the same startup race. It is a
normal Salt launch shortcut, not an autostart entry; users removing the plugin permanently can
also delete its `Salt Extend Online` Start Menu group manually. Unchanged registry values
inserted by this run are still removed on stop. Registration failures survive other UI status
updates and appear in the existing diagnostics action.

Tests persist and reload real COM Shell Links in a temporary test folder, verifying the exact
implicit/explicit app ID, title, icon, target and ownership cleanup. They do not register Salt
in the actual Start Menu or open a media device. This proves the missing shell-link metadata,
not a live Windows flyout: fully exit Salt (including tray), reopen and play after upgrading
to check the OS card. A previously created session and the shell's cached label may need restart.

References:
- https://github.com/Selemba1000/JavaMediaTransportControls/blob/master/src/main/sources/SMTCAdapter.cpp
- https://learn.microsoft.com/en-us/windows/win32/shell/appids
- https://learn.microsoft.com/en-us/windows/win32/properties/props-system-appusermodel-id

## Verification

- Real host media objects, interceptor and range reader with fake audio handles/CDN data:
  both platforms replay after expiry, LRU eviction and 70+ single-track additions.
- Original item IDs/paths, covers and lyrics survive renewal; no duplicate queue submission.
- Same-item concurrent opens resolve once; wrong account/track/CID, cancellation and late
  responses cannot publish a playable replacement.
- Lazy playlist eviction/repeated expiry keeps paths stable without growing aliases;
  quality changes invalidate cached URLs for both single and playlist paths.
- Quota/LRU/clear/generation/disabled mode, concurrent admission, exact lease isolation,
  real loopback cache hits, cache-miss re-fetch and revocation are tested offline.
- NetEase fixtures decrypt requested EAPI quality and exercise all equal/lower tier pairs
  while retaining rejection tests for previews, unknown tiers and account mismatch.
- The artifact validator checks exactly eight settings, all quality choices, default 2 GB,
  the reduced action set and unchanged plugin ID. Full native UI regressions also run.
- Windows identity mocks cover unchanged process identity and registration rollback; real
  Shell Link COM tests cover saved/reloaded properties, persistence before next launch,
  conflicts and user changes during rollback.

No installed plugin, actual credentials or audio device is modified during these tests.
Real audible playback and upstream availability for each membership tier need user testing.
