# 0.8.24 Bulk playback startup

## Cause and changes

The restored-queue fix persisted each logical track separately before resolving
the first song. Every new row created a `.track` record AND an empty artwork FLAC,
including directory checks, temporary writes and atomic moves. A synthetic local
1000-track run of 0.8.23 took 7045 ms and produced 2000 files before audio resolution.

- `SavedTrackStore.saveAll` commits only new public metadata in one immutable,
  checksummed `.tracks` batch, with the same stable target hashes/paths. Repeated
  play-all writes nothing. Validation, capacity and cancellation precede commit;
  interrupted/corrupt batches are not partially published. Legacy `.track` records
  remain readable and are not migrated/deleted. No signed URLs or credentials are
  persisted. The 10000 distinct-track bound remains.
- Persistent artwork paths are reserved without filesystem work per new row.
  A real cover (or empty fallback) is materialized for the opened/warmed song
  before handing its stream to Salt. Existing artwork is not erased. Reservation
  ownership and real-path checks still reject unowned/replaced paths. Startup can
  bind an unplayed track even when its artwork file does not yet exist.
  Explicit native playlist insertion materializes that one file before the host
  repository reads its size; it does not reintroduce eager files for play-all.
- The runtime uses the batch identities in the original complete list order.
  It still resolves the native-selected first track successfully BEFORE replacing
  the queue. Random selection, failed-first-track protection, next-three prefetch,
  account revocation, covers, lyrics and restored queue identities remain intact.
- NetEase missing song details previously used serial batches of 100. At most
  three now run concurrently with independent cookie jars from one verified
  session, cancellation and bounded waves. Results are checked against requested
  IDs, then assembled in upstream order; no partial playlist is submitted.

## Verification and limitations

`BulkSavedQueueContractTest` covers 1000-row batch/write counts, repeat identity,
mixed legacy/batch restart, lazy covers, checksums, mid-write cancellation,
invalid metadata, native random first index, first-track failure and replay after
restart. The focused run measured 79 ms for 1000 fresh identities, 5 ms repeated,
and one metadata file without eager covers. Timing is observational; assertions
use operation counts and behavior, not machine-dependent latency thresholds.

`CatalogBulkContractTest` verifies three simultaneous detail requests, 715-song
bounded waves and order, invalid worker response rejection, cancellation, one
account verification and preservation of the visible list during full loading.
Existing real native adapter, artwork/tag reader, saved-playlist and recovery-hook
contracts also run with fake network/handles, never real user playlists/accounts.

This removes measured local startup overhead, not all possible network delay.
Full uncached collections still require complete metadata before sorting/queue
replacement, and the selected song still needs valid audio and artwork resolution.
No partial-page playback, permission bypass, or background whole-song download is
introduced. Real-account/end-to-end audible startup timing is not measured here.

New batch files require 0.8.24 or later. Downgrading preserves older single-track
records but older plugin builds cannot restore identities stored only in batches.

Final full build passed all 57 inventoried plugin contracts (77 host/base
invocations), 70 core checks, six inspector tests and 297 emitted native ABI
references. The separate host-free build also passed. The isolated native
repository contract explicitly checks absent artwork before save and on-demand
materialization during insertion. A supplemental synthetic 10000-track run checked
the capacity boundary, rejection without partial writes, restart and distinct
Bilibili part identities.

Artifact: `plugin-io.github.yghfv.saltneri.online-0.8.24-bulk-start.spmod`,
275 entries, 656763 bytes. Every entry matches the final verified build.
SHA-256: `6BD1E03D96AC8E5E6B515BC8FAD6251E503F02FB1C030624F6C385FE036B7D6E`.
