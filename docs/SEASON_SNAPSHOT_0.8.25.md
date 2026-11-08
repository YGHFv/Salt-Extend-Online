# 0.8.25 Complete seasons, restored authors and retained covers

## Confirmed causes

The screenshot's 19/31 progress is the full Bilibili metadata crawl, before local
queue persistence. 0.8.24 removed disk overhead but did not remove this network
stage. A public, anonymous video-detail response for the affected collection
contains all 607 episodes under `ugc_season.sections[].episodes`, including
authors, durations and play counts. No account cookies or media/audio URL requests
were used to verify this.

Read-only aggregate inspection of Salt's serialized normal/random queues found
607 owned items and 607 blank artist fields in each, matching 607 blank artists
in legacy saved records. The catalog UI displayed a fallback collection creator,
but playback received the original blank subtitle. This is not evidence of Salt
losing a correctly serialized artist or the artwork parser overwriting tags.

Platform-page disposal previously called `clearCovers()`, discarding the entire
URI thumbnail LRU as well as pending row requests. Returning to a platform then
downloaded the same images again. The configurable 2 GiB disk quota belongs to
audio range caching, not to this separate image cache. The earlier claim that
the thumbnail cache survived platform exit did not match the disposal lifecycle.

## Changes

- Full UGC collections try one bounded read-only `/x/web-interface/view` request
  using the canonical first-page video. Accept only matching video/season/owner,
  exact episode count, unique BVIDs, matching AIDs and canonical first-page order.
  Incomplete, absent, malformed or differently ordered snapshots fall back to the
  existing paginated loader. Account/risk failures propagate instead of causing
  extra requests. The optional request has a 3-second child deadline; parent
  cancellation is never treated as a fallback opportunity. Existing JSON size,
  HTTPS pinning, cookie isolation and 10000-item bounds apply.
- Sorting/filtering/play-all consume the same full cached metadata; unavailable
  items remain filtered. A successful snapshot eliminates remaining page requests,
  not merely the progress label. Arbitrary sorting still requires complete data.
- Playback and context-menu saves now receive the same collection-author fallback
  as the visible UI, only for Bilibili single-author collections. Per-video authors
  take precedence. Favorite-folder owners are not substituted for video authors.
- Saved identity batches may fill a missing author without changing title,
  nonblank author, duration, target, ID or path. Commit precedes in-memory update;
  existing session bindings retain their entry reference. Startup merges legacy
  and batch metadata monotonically, independent of batch enumeration order.
- Page disposal only retires pending row requests, retaining successful thumbnails
  in the shared loader. Normalized public image URIs, not ephemeral row tokens,
  key the bounded 256-entry / 48 MiB / 30-minute in-memory LRU. Known cache hits
  bypass the executor and the 20-row request capacity and seed the first rendered
  frame. The cache stores thumbnail PNGs; each mounted row still creates its own
  bounded Skia image and closes it on disposal.
- Prefetch the current page (at most 20 covers), with six bounded image workers.
  Concurrent requests for the same image share one download. Cancelling an old
  page's owner lets a still-valid follower take over; explicit clear/close prevents
  in-flight data from repopulating a cleared cache. Permission checks still apply
  to cached reads, and plugin shutdown releases the caches.
- Bilibili list requests use width-only `@480w.webp` CDN scaling, with original
  fallback if unavailable, preserving aspect ratio. Playback still downloads the
  original up to its independent 1024-pixel output budget. NetEase URLs remain
  original, with the same list-memory cache and concurrent prefetch benefits.
- Missing/broken artwork lookup results are retained by item identity, bounded
  to 256 per platform. Refresh and account changes invalidate these mappings;
  changing row tokens or moving between pages does not repeat the lookup.

The reference NeriPlayer initializes a global Coil ImageLoader in
`AppImageLoaderInitializer.kt`, using shared networking and independent memory
and disk image caches; `ImageUtil.kt` supplies stable cache keys and bounded list
sizes. This release follows its shared-loader/cache-lifetime approach without
copying Android dependencies. It does not yet implement NeriPlayer's persistent
image disk cache or transport connection pooling. The 2 GiB audio setting remains
unchanged; restarting Salt discards list-memory thumbnails. No image contents,
Base64 image data or private source URLs are included in conversation or logs.

## Verification and limitations

The actual affected public collection passed production snapshot validation:
607 items, 607 nonblank authors, canonical first page 187 ms, full snapshot 458 ms.
This is an observed anonymous metadata-only measurement, not end-to-end audible
latency or a guaranteed bound. The public comparison page was read anonymously;
the plugin's authenticated catalog verification remains unchanged. No actual
playback, account reads, native queue writes or user library writes were used.

`BiliSeasonSnapshotTest` exercises 607-row fast loading, duplicate IDs, wrong owner,
wrong seed/order/AID, partial/absent seasons, risk/cancel handling, exact full
fallback and cache reuse. `SavedArtistRestoreTest` uses the real native
`toPlayQueueSave` -> ObjectOutputStream/ObjectInputStream -> `toPlayQueue` path,
verifying queue-wrapper identity and author retention rather than reconstructing
media fields by hand. It also verifies catalog fallback propagation, legacy and
batch-only repair, and preservation of existing nonblank metadata.

`CoverLoaderContractTest` checks cross-page cache hits even with all workers and
row slots occupied, duplicate-download coalescing, cancellation handover,
clear-during-download publication barriers, bounded LRU eviction, CDN transforms,
and the unchanged original playback path. `LibraryWorkflowContractTest` checks
both platforms' missing/fallback URL reuse and refresh/account invalidation.
`NativePageRenderTest` checks actual Compose disposal retention and colored cover
pixels in the first frame after a cache hit, using synthetic image HTTP only.

An anonymous public-image probe used the production image loader on one image
per platform. Bilibili downloaded once in 510 ms and returned the cached bytes
after page-request release in 3.561 ms; NetEase downloaded once in 341 ms and
returned cached bytes in 0.580 ms. Both revisits made zero image requests.
These are individual local observations, not whole-page latency guarantees;
the Bilibili sample's source was only 120x90. Only timings, dimensions and request
counts were logged, not image bytes, image URLs or response bodies.

Final local verification passed all 59 inventoried contracts (80 host/base
invocations), 70 core checks, six inspector tests and 297 emitted host ABI
references. The host-free suite also passed. The cover contract's 151 checks
passed five additional consecutive runs. All 276 archive entries match the
tested classes/resources; archive size is 662763 bytes, SHA-256
`10F1C144EF1FA0CD732518EEFC6B05A0E85BC1F9EF72215DA32CDC23B5EA679E`.

Already-blank native queue items need one explicit play-all from their collection
after upgrading to supply the missing public metadata. Startup does not fetch
arbitrary remote tracks, guess an artist, rewrite user playlists, or start audio.
Subsequent restarts retain the repaired authors. Bilibili favorites and seasons
without a complete consistent embedded snapshot still use bounded pagination.
