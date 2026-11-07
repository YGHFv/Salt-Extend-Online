# Salt Extend Online 0.8.10

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.10-bili-discovery.spmod`.

## Automatic discovery

- Both account menus now show a swap-arrow icon for Switch Account and an exit-door icon
  for Logout, using 20 dp native-theme vectors in the existing leading-icon slot. Text and
  full-row click behavior are preserved. No image download is needed for these icons.
- Sort menu items also have distinct native-theme vectors: order, reverse, title, artist,
  duration and (Bilibili only) play count. The existing header sort icon remains unchanged.
- An actually composed, permitted Bilibili or NetEase page starts public discovery when
  that platform has no current catalog. The discovery tab is selected for both platforms.
- Existing folders, favorites, subscriptions, searches and pages are preserved on re-entry.
  Recomposition/polling do not restart an in-flight or failed request. A failed discovery
  remains retryable with the existing refresh button, not an automatic request loop.
- If an account change clears a visible catalog, the blank page defaults to public discovery.
  Merely creating the plugin, inspecting a snapshot or rendering the sidebar does not fetch
  a catalog. No auto-login, QR creation, audio resolution or queue mutation is added.

## Bilibili requests

- Bilibili discovery APIs already return bounded whole charts. A platform-local cache now
  keeps each mapped chart for 120 seconds, so unseen subsequent pages are sliced locally
  instead of repeating the same full-chart network calls. There are at most three entries:
  music-region popular videos (100 rows), music HOT and ORIGINAL (up to 1000 rows each).
- Charts retain original order, disabled/duplicate filtering, total counts and 20-row pages.
  The ordinary page cache cannot extend chart expiry. Refresh, account changes, permission
  revocation and shutdown clear the shared cache. Late requests cannot repopulate a cleared
  cache. Errors are not cached or retried. No eager image or audio requests are introduced.
- Account verification already returns Bilibili WBI metadata. Catalog sessions reuse its
  optional validated mixin instead of fetching `/x/web-interface/nav` a second time. The
  mixin is local to that request session, not a persistent credential or permission cache.
  Missing/invalid optional keys retain the existing independent WBI-request fallback.
- A normal collection/search/collection-cover operation therefore uses one verified NAV
  request plus one content request (previously two NAV requests plus content). Identity
  mismatch still stops before content requests. Normal favorite-folder requests were already
  two requests and remain so; the existing 0.8.9 page cache also continues to cover them.

## Verification

- Catalog fixtures count one NAV call, validate signed query parameters, and prove that
  identity mismatch cannot publish collection/search results. Existing QR/login contracts
  still exercise account verification independently of optional WBI fields.
- Discovery fixtures verify no extra page request, independent chart keys, 120-second
  expiry, explicit clear, cancellation, late response invalidation and anonymous requests.
- Controller fixtures verify automatic discovery on each platform, no fetching on passive
  snapshots, no restart on repeated entry, retained existing catalogs, no failed-request
  loop, account-change behavior and permission gates.
- Native offscreen Compose fixtures verify discovery selection after entry on both platforms
  at wide and narrow widths, without clicking a discovery tab or using actual accounts.
- Account-menu pixel and layout checks verify both icons are actually drawn beside their
  labels, vertically aligned and not overlapping, for both platform pages at 420/740 px.
- A public Bilibili discovery probe on 2026-11-07 returned 20 rows on each of the first two
  pages: one request for page one and zero additional requests for page two. Total elapsed
  time in that single run was 938 ms; this is not a universal latency guarantee.
  No image data, private lists, credentials or audio were accessed by this probe.

Install the new artifact and restart Salt. Click either platform to load discovery directly.
For current upstream chart changes, use refresh rather than waiting for cached data to expire.
Real authenticated playback remains a user acceptance item.

## NetEase playback lyrics

- The previous build never supplied online lyrics to Salt. The public
  `PlaybackExtensionPoint.onBeforeLoadLyrics` now resolves lyrics for exact owned native
  item IDs and paths. Local music, other mods and Bilibili items are not intercepted.
- NetEase `/eapi/song/lyric/v1` parameters follow the pinned NeriPlayer `getLyricNew`.
  Only Salt requesting an owned item's lyrics starts HTTP: no catalog prefetch, no new
  delay in audio resolution, and no fetching all lyrics when replacing a queue.
- Raw YRC is not understood by this Salt build. Convert upstream absolute word timestamps
  and durations to Enhanced LRC, with explicit end markers to preserve silent gaps. An
  invalid/missing YRC falls back to timed LRC. Matching translation timestamps become Salt
  subtitle lines; unmatched translations are not guessed or assigned to a different line.
  This does not add external lyric search, romanization or speech transcription.
- Bindings exist before both single-track and lazy-playlist submission, including tracks
  whose audio has not been resolved yet. Cover-path updates preserve the same item ID.
  Account/permission revocation, retiring a track or closing a queue cancels lyric reads.
  The plugin never replaces a local lyric file or writes lyrics to the audio installation.
- HTTP uses the isolated pinned TLS transport, scoped plugin cookies, a fixed POST endpoint,
  bounded JSON/text, no redirects/retries and a six-second cancellation deadline. Upstream
  unavailable/no-lyric responses leave Salt's own fallback intact and do not stop audio.
  At most 32 results of 256 Ki characters each are cached in memory; late cancelled results
  cannot repopulate closed bindings. No lyric text or account data is logged.
- Synthetic tests exercise the actual Salt lyric parser, verifying original text, word
  starts/ends, gaps and translated subtitles; they also exercise lazy queue ownership,
  exact ID/path matching, bounded cache and concurrent revocation. The fixed public
  NetEase test song returned nonempty timed lyrics on 2026-11-07; content was not printed.
  Actual signed-in audible playback and its on-screen progression still need user validation.
