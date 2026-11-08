# 0.8.36 CDN Redirects And Full-Track Prefetch

## Observed Failure

Salt 1.19.3 finished the fourth random-queue track, selected the fifth track, and
failed to create its audio stream. An independent resolution of the same Bilibili
video reproduced a 302 redirect from `upos-sz-mirrorbd.bilivideo.com` to a
32-hex-character subdomain of `v.smtcdns.com`. The old media probe rejected the
redirect. Native playback reported `Unknown`; the adapter deliberately redacted
the upstream exception.

The old lookahead attempted the next three tracks, but downloaded only the first
512 KiB. Failed speculation was ignored until the native queue plan changed.
Opening the failed track performed a fresh resolution, but no automatic retry or
skip followed a final failure.

## Changes

- Bilibili HTTPS media redirects may move between `bilivideo.com`,
  `bilivideo.cn`, and `[a-f0-9]{32}.v.smtcdns.com`. Other `smtcdns.com` hosts,
  HTTP downgrades, non-443 ports, user-info, fragments and unrelated hosts remain
  rejected. The pinned HTTPS transport still checks public IP addresses and
  validates TLS certificates and the destination hostname.
- Media range probes and actual audio reads share the redirect policy, with at
  most three redirects. Account cookies are not forwarded to a different host.
  NetEase retains its same-host-only redirect policy.
- Lookahead reads Salt's actual normal/random queue and caches all bytes of the
  next three songs as aligned 512 KiB blocks, including the final partial block.
  Repeat-one does not download unrelated songs. Saved native playlists get the
  same lookahead after restart, without requiring an online catalog page.
- The configured cache quota is a disk maximum (default 2 GiB), not a mandatory
  download size. A track larger than the quota falls back to on-demand streaming.
  LRU eviction, cache clearing, account changes and lease expiry remain enforced.
  This is a runtime cache, not a permanent offline music download.
- Speculative failures get at most three attempts per queue/quality/cache plan,
  with 3-second and 10-second retry delays. One failed track does not prevent the
  other next tracks being cached. Account/platform rejection is not retried.
- A failed owned audio-stream attempt gets up to two additional automatic
  prepare/play attempts. If it still fails, the native next command advances the
  existing queue; it never removes songs or replaces the queue. Explicit platform
  rejection skips without retry. Consecutive skips are limited to five; no-next
  and repeat-one cases stop with a native error toast.
- User pause/stop disables recovery; explicit play or track selection re-enables
  it with a new generation. Old in-flight failures cannot resume playback after
  user intervention. Permission loss and plugin shutdown cancel recovery.
- Request deadlines are distinguished from explicit cancellation so a resolution
  timeout can be recovered without treating user cancellation as a failure.

## Validation

- Fake CDN redirects: permitted Bilibili edge, unrelated host, malformed edge,
  HTTPS downgrade, port restriction, cookie isolation and redirect bounds.
- Full-track lookahead: native random order, tail-byte cache hits without another
  CDN download, persistent saved queues, temporary resolution retry, cache clear,
  oversize quota rejection, repeat-one and account cancellation.
- Recording-only recovery: two retries then skip, platform rejection, native ABI,
  pause/stop and stale tickets, permission loss, local-track isolation and queue end.
- Independent read-only live resolution of `BV1JHaB6sEeB` succeeds after the
  redirect fix; this check does not enqueue or play audio in the running Salt.

Import the new package and fully restart Salt to activate the implementation.
Building/testing does not replace the installed mod or alter the live play queue.
