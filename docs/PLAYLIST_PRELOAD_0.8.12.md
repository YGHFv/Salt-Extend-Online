# Salt Extend Online 0.8.12

## Original-item artwork

Lazy playlists previously queued `saltneri://queue/...` placeholders, then replaced each
item's path with an artwork FLAC when the track opened. Salt's `updateMediaItems` enqueues
an asynchronous command, while `refreshCurrentCover` launches an independent coroutine.
Neither ensures that onLoaded sees the replacement object: the original selected object
can still point to the non-file placeholder, or an early refresh can read the previous song.
The host's metadata-refreshed callback updates JMTC, but does not reload the playback bitmap.

Each queue entry now reserves its final owned FLAC path before submission, without creating
files or fetching images for the whole playlist. The stream resolver populates that exact
path before opening the audio stream. Both the original native item and later metadata
updates address the same artwork. The racy explicit refresh is removed; Salt's normal
onLoaded reads the populated path and checks the current item ID before publishing artwork.
The same path survives URL renewal/LRU eviction. Clear leaves a metadata stub; never-opened
reservations have no disk cost. Registration is revoked and exact owned files are removed
on close, without recursive deletion. Original aspect ratio is preserved.

## Complete collection reads

Clicking play keeps the existing visible page and row capabilities while an independent
worker obtains the complete collection. Pagination waits until that operation finishes;
it cannot publish a partial playlist. A failure keeps the visible list and does not replace
the user's queue. Existing first-track permission/failure checks still run before submission.

NetEase full reads use one playlist ID response and reuse its first 20 complete song details.
Missing metadata is requested in batches of at most 100, validating response IDs and keeping
the original playlist order. A 315-track fixture with 20 embedded details uses four metadata
requests (one playlist + three song batches), plus one verification when logged in, instead
of fetching/verifying the ID list independently for every 20-track page. Bilibili full reads
keep 20-track upstream pages but share one verified HTTP/WBI session. Both remain bounded
to 10000 logical entries and retain account/permission/cancellation checks between requests.
This reduces request count; real-world latency still depends on the upstream service.

## Native playback order

The plugin reads the native PlaybackQueueState rather than imposing its own playback mode.
For Random, play-all picks a random starting index; the native queue then creates and owns
the rest of the shuffle order. Circle, CircleEnd and RepeatOne start at the first catalog
item. The displayed list/sort order and the user's native mode are not overwritten.

Prefetch reads the actual upcoming native order, including shuffled order and mode changes.
It does not guess that the next catalog row will play, wrap a single-pass queue, or prefetch
other songs during RepeatOne. At a random-queue boundary it waits for Salt to generate the
new order. Additional queue state/entry/mode class fingerprints gate these mappings.

## Three-track lookahead

Only after a stream in the submitted playlist opens, a single background worker prepares
the next three native queue items. It resolves the normal permission-checked audio URL and
artwork, then warms at most 512 KiB of each audio prefix through the private loopback range
transport. It does not instantiate an audio engine, fetch entire songs, or prefetch lyrics.
The existing cache budget applies; with caching disabled it prepares only URL and artwork.
This matches Salt 1.19.3's aligned 512 KiB first read (a 256 KiB prefix would not hit).
The range cache can serve a smaller contained read from a validated prefetched prefix,
but never from another media lease. Actual playback of a warmed song reuses its resolution.

Lookahead is fenced by playlist/account, last stream open, quality revision, current native
item and upcoming order. Changes cancel stale HTTP through RequestScope. Only one speculative
attempt per plan runs; failures are silent and real foreground playback can retry normally.
The eight-live-entry LRU pins the opened current entry so repeated mode/order changes cannot
revoke the currently playing stream. Logout/disable cancels workers and retires exact leases.
URL expiry is still checked when opening a song. Existing limits remain: restored queues
after app restart need re-adding, and a very long already-open stream is not renewed mid-read.

## Verification

- Original native objects with deliberately deferred queue updates: next/back, expiry and
  12-song LRU eviction all expose the correct synthetic cover through Salt's real tag reader.
- Actual Skia decoding verifies distinct song images and 160:90 aspect ratio. No user images
  are read, copied into tools, displayed, or transmitted as base64.
- Actual native PlaybackQueue tests cover all four modes, indexed replacement, random first
  choice and upcoming order without creating PlaybackController or an audio engine.
- Fake-CDN + real loopback and HostRangeReader tests cover three-track prefetch, actual
  first-chunk reuse without a second CDN request, smaller-range cache hits,
  repeated lookahead changes retaining current playback, RepeatOne, and pending cancellation.
- Bulk fixtures verify 315-song request batching, original ordering, one account verification,
  both platforms, and the unchanged visible list while play-all is waiting.
- Full native UI, account, range transport, lyrics, queue replay and packaging regressions run
  against the installed Salt 1.19.3 classpath. No installed mod or actual credentials are changed.

After installing, restart Salt and click play-all once again to create the new stable-path
playlist. Actual audible playback, real service latency and the running UI need user acceptance.
