# Salt Extend Online 0.8.7

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.7-search-refresh.spmod`.

## Reference alignment

Reference: NeriPlayer `fd7fdb757821fda7a62aee3a2b7cecf3a4b8bfe0`,
`NeteaseClient.searchSongs`, `shouldPreheatNeteaseWeapiSession` and `NeteaseSongMapping`.

- NetEase song search uses the official read-only `/weapi/cloudsearch/get/web` endpoint,
  WEAPI POST, type 1, 20 rows per page, keyword, offset and upstream total count.
- Search and playlist details share artist, duration, original-aspect artwork and availability
  mapping. Modern `ar/al/dt` and legacy `artists/album/duration` names are supported.
  Duplicate/invalid search identities and blank titles are discarded; disabled songs stay hidden.
- Anonymous public catalog reads allow an empty CSRF query value. The old unconditional
  homepage preheat could reject public discovery before its API request. Authenticated
  reads still independently verify the account and require CSRF preheat when absent.
  An invalid signed-in account does not silently fall back to anonymous search.
- API codes 301 / 50000005 retain their numeric diagnostics and add a login/re-login hint,
  following NeriPlayer's `HomeViewModel.buildHomeErrorText` treatment of 50000005.
  Public requests are supported but the server may require login; no anonymous availability
  guarantee is made and a rejection never automatically launches a login flow.
- Search results are candidates, not proof of full-track rights. The existing resolver
  still rejects previews, missing rights and incomplete audio. No member-only access,
  region restriction or official security challenge is bypassed.

## Native interaction

- Both platform headings have the same search-icon interaction: expand inline to the left,
  Enter submits, a second icon click collapses without clearing text or results.
- NetEase search keeps its own keyword, rows and page across page navigation. Search paging
  uses upstream totals, not the count left after unavailable rows are hidden. Single-page
  results hide pagination; multiple pages display previous / x/x / next at the bottom.
- Empty successful pages have a short empty-state message rather than looking like a failed load.
- A refresh icon sits in the list footer, above pagination. On a failed request it becomes an
  explicit retry action. The playlist title still has exactly search / play / sort icons.
- Refresh keeps the selected view, folder, page, search keyword, local filter and sort.
  It revokes old row tokens and cancels any pending playback intent. A full-list filter/sort
  is refreshed in full under the existing 500-page / 10000-entry / 180-second bounds; a normal
  catalog refresh loads its current page. If the upstream page count has shrunk below it,
  the controller relocates to and loads the new last page instead of displaying e.g. 2/1.
  A shrunken full list clamps locally to its last page.
- Refresh, search and browsing never start playback. Double-clicking a song uses the same
  explicit playback path as other rows. No automatic request retry or background account login
  is introduced. Account replacement/logout still discard the affected platform catalog.

## Verification and limits

- Protocol fixtures exercise public/signed-in search, modern/legacy song fields, hidden paid
  and unavailable candidates, duplicate/invalid identities, empty results, totals, page limits,
  malformed/oversized responses, CSRF conditions, identity mismatch, safe numeric errors,
  fixed endpoint/method policy and no automatic retry.
- Controller fixtures exercise platform-local state, search paging, failed-page recovery,
  full-list filter/sort refresh, stale row revocation and no replay of pending whole-list intent.
- Real offscreen Compose tests exercise both headings, inline search toggling, Enter, native
  double-click submission to a recording queue, NetEase pagination, failure/retry and empty
  states at 420 and 740 px widths. They do not open a visible host or audio device.
- `CatalogLiveProbe --explicit-public-probe search|discover` is an optional one-page public
  metadata probe. It never requests images, audio or accounts, and prints counts/errors only.
- Public probe on 2026-11-07: discovery returned 20 enabled playlists / 1 page; anonymous
  search was rejected with `NET_SEARCH/API=50000005`. No retry or account access followed.
  Signed-in search remains an installation acceptance item, not a claimed live success.
- Actual user-account verification/captcha completion and continuous audible Salt playback
  remain installation acceptance items. The 0.8.6 official-web-login fallback is unchanged.
  No screenshots, cookies, QR payloads or upstream response bodies enter the conversation.
