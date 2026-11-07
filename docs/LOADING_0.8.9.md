# Salt Extend Online 0.8.9

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.9-loading-cache.spmod`.

## Changes

- Ordinary catalog reads have a per-platform, account-revision-specific LRU cache of
  at most 32 pages (640 rows), with a maximum 120-second TTL. Query keys include view,
  folder, owner, page and search keyword. Reopening a recent tab, folder, search or page
  can reuse successful metadata instead of repeating API authentication and list requests.
  Visible metadata still follows the existing keep-until-refresh behavior; TTL governs
  reusing pages when navigating, not background polling of an already visible list.
- Explicit refresh clears the platform page cache and loads new data. Logout, account
  replacement, permission revocation and shutdown revoke it. Failed ordinary reads evict cached
  metadata; errors are not cached or automatically retried. Late cancelled requests cannot
  populate a cleared cache. Network work never holds the catalog UI monitor or cache lock.
- Cache hits publish new row tokens. Old row clicks remain invalid. Only metadata is cached,
  never signed audio URLs, permission decisions, queue submissions or whole-list play intent.
  Full-list search/sort/play retains its existing fresh bounded read path rather than
  assembling an inconsistent queue from separately cached pages. Audio resolution still
  checks account identity and official full-track rights independently.
- NetEase playlist first-page requests ask for up to 20 embedded song records (`n=20`).
  If the selected IDs already have usable metadata and album cover fields, those records
  are mapped directly. Missing records/fields are fetched with one batched detail request
  for only the missing IDs. Track-ID order, duplicates/foreign-ID rejection, disabled-row
  filtering and pagination totals remain intact. Later pages use `n=0` as before.
- Cover downloads use three workers instead of one, with the existing 20-entry bounded
  queue/cache, cancellation and decoder budgets. One stalled image cannot block all later
  images. No cropping, server thumbnail transform, resolution reduction or image proxy is
  introduced; playback and list image sizes are unchanged.
- HTTPS connections share one plugin-local TLS context and a bounded TLS session cache
  (64 sessions, 300 seconds). Each connection still resolves/pins public DNS, checks HTTPS
  hostname/certificates and uses a fresh cancellable socket. HTTP cookies, credentials,
  responses, JVM-global authenticators/proxies and live connections are not pooled.

## Verification

- Deterministic cache fixtures cover hit request counts, TTL, LRU eviction, refresh,
  account replacement, logout/revocation, failure, cancellation and late publication.
  Returning to page 1 after page 2 requires no third upstream catalog request.
- Embedded-song fixtures cover complete first-page metadata, batched gaps, missing cover
  fallback, original ordering, invalid/duplicate/foreign IDs, and later-page parameters.
- Cover fixtures hold the first three downloads behind a latch, verify bounded concurrency,
  then complete all 20 rows exactly once. A separately blocked first cover does not stop
  a later cover. MIME/decoder/ratio/size/cancellation tests remain in place.
- Existing native Compose, account, catalog, playback and transport regressions are run
  against the local Salt ABI with synthetic accounts, images and a recording queue.
- `CatalogTimingProbe --explicit-public-playlist` reads only the first page of a fixed
  public NetEase chart. One 2026-11-07 comparison measured 2 requests / 1314 ms before,
  1 request / 1170 ms after, with 20 rows in both. This is a single public-network sample,
  not a guaranteed latency improvement or signed-in account benchmark. No images, audio,
  private lists or account data are fetched by this probe.

## Usage and limits

Upgrade and restart Salt. Navigate to a list, switch away, then return or page backward
to benefit from the short-lived cache. Use the list's refresh icon when upstream content
changes; navigation can otherwise show metadata up to two minutes old. No background
prefetching of the entire library is added. Cold requests still depend on the service/network,
and authenticated cache misses still verify the account. Whole-list operations retain
their existing duration and size limits. Real account playback remains user acceptance.
