# 0.8.22 Native playlists and track menus

## Behavior

- Refresh is in the fixed top-right toolbar, not below the list.
- Secondary click on an enabled video/song opens Salt's installed BlurPopupMenu.
  Folder rows do not expose track commands. The native Add to Queue submenu uses
  AfterCurrent, AfterLastAdded and AtQueueEnd without play/prepare/replace calls.
- Add to Playlist loads Salt's native playlists on a worker. Selecting a playlist
  stores a durable logical target and inserts its native Track. New Playlist
  navigates to Salt's own creation page; return and select the new playlist to add.
- Saved tracks play directly from the native playlist, including after restart.
  Registration does not fetch audio or mutate the queue. Actual native opens resolve
  a fresh URL using the current plugin account and quality settings.

## Persistence and ownership

`saved-online-tracks` under the plugin configuration directory contains bounded,
versioned public metadata and metadata-only FLAC artwork files. IDs are deterministic
SHA-256 values of canonical platform targets. Cookies, account IDs and signed audio
URLs are not stored there. Corrupt records are isolated; paths reject symlinks and
traversal. At most 10,000 records are accepted. Files survive plugin shutdown and
logout so Salt's saved Track paths remain stable.

Only plugin-owned paths have deferred stream bindings. The session retains at most
eight live leases, never evicts the currently opened stream for another saved item,
and revokes in-flight requests and leases on account/permission changes. Mapping
identity survives account changes; future opens use the new account. NetEase lyric
bindings are refreshed on account changes. Native audio handles remain host-owned.

Startup only arms the adapter automatically when saved records already exist. It
retries host initialization at one-second intervals for at most 60 attempts, with
no playback or platform requests. No saved records means no startup audio binding.

Native repository access uses TrackDao's verified `@Insert(onConflict=IGNORE)`
List/Continuation overload, not TrackRepo's similarly obfuscated Track[] overload
(which deletes). Playlist membership uses the same PlaylistRepo insertion API as
Salt. Existing Track metadata is preserved. Removed playlists are rechecked before
write. A failed membership write can leave an unreferenced saved Track/metadata
record; this is retained rather than risking deletion of a concurrently used item.

This is local Salt playlist integration, not an upload to a platform playlist or a
portable cross-device mapping. Keep the plugin data directory when migrating Salt.
Removing a playlist entry does not delete its public saved metadata automatically.
Persistent cover metadata is separate from the expiring audio-segment cache.

## Verification

- Actual Salt Compose widgets and secondary mouse events on both platforms, all
  three layouts and narrow/wide fixtures; three insertion choices, menu dismissal,
  playlist selection, folder exclusion and toolbar refresh.
- Isolated native Room database and actual repositories: list, insert, duplicate
  membership, preservation of user-edited title, native playlist readback and
  conversion to the original stable media ID/path. Never the user's database.
- Native Track conversion and stream interceptor with fake audio handles: persisted
  targets, restart, URL expiration/renewal, account change, covers, lyrics and
  permission revocation. No real platform playback or credential reads.
- Existing stream-lifecycle, replay, queue, catalog, account and UI suites retained.

These tests do not prove uninterrupted real sound-card playback or availability of
every platform track. Manual acceptance: right-click a public song, add it to a
test Salt playlist, play from that playlist, restart Salt and repeat after login.

## Verified build

Full local build: 55 inventoried plugin contracts, all host-free repetitions,
70 core checks, six inspector tests and 297 emitted native ABI references passed.
The independent host-free script also passed. New coverage includes 121 native
menu/toolbar checks, 36 persistent playback checks and eight isolated native
repository checks. The existing footer test now accounts for removal of the old
bottom refresh item.

Artifact: `plugin-io.github.yghfv.saltneri.online-0.8.22-native-playlists.spmod`,
270 entries, 646,221 bytes. Every entry matches the tested classes/resources.
SHA-256: `4E85E0BD51C1462D0C39BCF53324A1973EF272C600340A894FF7B0160569D0AE`.
