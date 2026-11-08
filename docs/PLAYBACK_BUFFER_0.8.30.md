# 0.8.30 Playlist rejection and bounded playback read-ahead

## Findings

The reported `PLAYLIST/PlatformRejected` message discarded NetEase's numeric
rejection code. The first selected track was resolved before queue replacement,
but every rejection aborted the entire action, including explicit track-level
unavailability. The screenshot alone cannot identify the upstream rejection.

The host's `HttpRangeSource` reads aligned 512 KiB chunks and completely buffers
each chunk before returning decoded input. The plugin previously warmed only
next-track prefixes, not the next chunks of the playing track. Each uncached
chunk therefore required a new CDN connection on the host's synchronous read
path. This is a buffering gap, not evidence of a particular user's bandwidth,
audio device, or account entitlement.

## Changes

- Distinguish explicit song gates (unavailable status/privilege, unauthorized fee,
  trial/preview response, null audio URL, per-song 404) from API, login, CDN and
  risk rejection. Preserve the safe numeric code in playlist failure messages.
  No raw response, cookie, signed URL or exception text is exposed.
- During startup only, remove a known-unavailable selected song from this new
  queue and try its successor in the original order, wrapping at the end.
  Native random mode still selects the original first candidate. Stop after
  five rejected candidates or the shared 30-second startup deadline. Keep the
  user's old queue intact until a full track resolves. Do not skip general
  platform/network rejection or silently fall back to anonymous access.
- Persistent target records remain intact when a candidate is omitted from the
  new queue. Old native playlists and user metadata are not deleted or edited.
- Explicit full NetEase catalog requests ask for 100 embedded track details
  rather than 20, within the existing bounded JSON protocol. Reuse any complete
  embedded metadata and fetch only gaps in existing bounded concurrent batches.
  Platforms can return fewer details; no partial collection is submitted.
- After an actual aligned host media read, warm only the next two 512 KiB chunks
  through the verified, lease-scoped cache. Prefix prefetch carries a local marker
  and never advances that window; no speculative whole-song crawl occurs.
- Read-ahead has one dedicated worker, a 2.5-second request deadline, no retry,
  and a fair per-lease download lock. Overlapping work is coalesced; cached host
  reads bypass the lock. Advancing the window keeps useful in-flight overlap.
  Seek/track changes cancel obsolete requests; clearing/disabling cache,
  expiry, permission/account revocation and shutdown also cancel work.
- Preserve strict complete-range validation before publishing HTTP 206. Keep
  the foreground transient retry policy, DNS/TLS pinning, target allowlists,
  native stream ownership and account isolation unchanged.

## Verification

Regression fixtures cover bounded startup skipping, indexed/native order,
all-rejected/ordinary network failure protection, persistent mapping retention,
numeric rejection diagnostics, embedded detail reuse, two-chunk/no-crawl
behavior, real host read-ahead cache hits, in-flight request coalescing,
seek/clear/revoke cancellation, cache disable and lease isolation.

These tests use synthetic API/CDN responses and real local HTTP/native host
classes, not real user credentials or audible output. Real-account startup
latency and uninterrupted audible playback are not claimed as verified.
The first uncached catalog, account check, media resolution and artwork can
still take time; high-bitrate playback still requires sufficient network speed.

Import the new `.spmod` using Salt's module manager and fully restart Salt.
Do not leave another package or extracted directory with the same plugin ID
enabled. The build does not modify the Salt installation or user data.

Final build passes all 62 inventoried plugin contracts (84 host/base invocations),
70 core checks, six inspector tests and 339 emitted native ABI references.
The separate host-free suite passes. The read-ahead concurrency/cancellation
fixture also passes five consecutive focused runs. Every packaged entry matches
the final verified build.

Artifact: `plugin-io.github.yghfv.saltneri.online-0.8.30-playback-buffer.spmod`,
296 entries, 722419 bytes.
SHA-256: `D5428D747D48253E888ADFF6C168AEEC7D4441BF0E2AE6231AA64D043A2F9559`.
