# Playlist Artwork 0.8.45

## Causes

- Production playlists use SavedTrackSession, not the temporary LazyPlaylistSession path. Publishing a cover only replaced the FLAC picture; it never notified Salt to reread artwork.
- A failed three-second initial image request discarded the image source. Cached audio then skipped resolution, so revisiting the song could not recover its artwork.
- Refreshing immediately after asynchronous queue metadata updates can target the previous song. Salt's actual Ready player identity, not speculative queue selection, must be checked again on the UI thread.

## Changes

- Audio retains only a CoverSource-validated public image URI and crop mode in memory. No signed audio URLs or credentials are written to saved playlist records.
- Saved playback owns a separate bounded image worker: two active requests, eight queued requests, eight-second deadlines and three attempts per selection. Cached audio remains unchanged. Explicitly revisiting a track permits another bounded attempt.
- Publication and Ready-track changes trigger guarded native refresh. Both ID and stable file path must still match on the UI thread. Closed/revoked sessions cannot publish or refresh late images.
- Atomic metadata-only FLAC replacement preserves original native queue objects and restart artwork. Existing persisted pictures are not erased on an image failure.
- QQ album covers on the old y.qq.com path can fall back to the same validated album identity on y.gtimg.cn. Original playback quality remains independent from list thumbnails.
- This package includes the native Windows login and compatible DPAPI implementation described in POWERSHELL_HELPER_0.8.44.md.

## Verification

SavedPlaylistArtworkContractTest uses all three platforms, synthetic leases and actual Salt artwork extraction, without initializing the playback singleton or accessing real accounts. It covers initial failure, a delayed image during a fast switch, cached replay/previous, original queue objects, restart, foreign paths, session close and account revocation.

The new persistent artwork regression passes 68 checks, including actual Salt tag extraction and Skia decoding; three repeat runs also pass. CoverLoader passes 181 checks including exact QQ static-CDN fallback. Existing persistent playlists pass 88 checks and lazy playlist covers pass 359 checks.

Final native-login classes pass the 19-check real hidden Edge probe and synthetic old/new DPAPI cross-decryption. The existing decoder freeze fix remains unchanged: its 1,065 callback checks pass, and a separate no-sound BASS/Media Foundation probe passes eight revoke/seek/EOF or mid-track free iterations and 16 concurrent mixer switches, leaving zero sealed files.

The final package passes all 76 inventoried contracts (103 host/base executions), the 11 host-free plugin contracts, 70 core checks and six inspector tests. Native UI verification passes 21 fingerprints and 364 emitted ABI references. All 391 archive entries are byte-identical to the fresh verified build; no tests or proprietary host classes are bundled.

Package: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.45-native-login-cover.spmod`

Size: 967,075 bytes.

SHA-256: `AC0E8E92B2737F6167FBBE5229C459EDFAD8858E8D8108054B4AD63B58497EE1`

No installed mod, Salt process or saved credentials are modified by these tests. Synthetic/offscreen validation cannot establish successful official authentication or prolonged real-account playback on a remote user's machine.
