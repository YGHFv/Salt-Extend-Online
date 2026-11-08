# 0.8.32 Native Headers And Alternative Sources

## Behavior

- Collection/folder/author secondary pages invoke Salt's installed BasicScreen titlebar and back button. The redundant platform header is omitted. Titles, counts, search, playback, layout and sort remain outside the scrolling list.
- `ui.hideSidebarToggle` defaults off and hides the native caption toggle without changing sidebar state. A zero-sized, noninteractive anchor retains compact shortcut placement. Disabling the switch restores the untouched native button. The switch is independent of sidebar optimization.
- `netease.autoSourceSwitch` defaults off. Only a song-level NetEase `TrackUnavailable` response enables searching a separately authorized Bilibili source; login, risk, malformed response and network failures do not trigger it.
- The reference policy uses title/artist, artist/title and title-only queries with a lossless keyword; duration filters retry without the filter only on empty results. Each query considers at most six candidates, with cross-query deduplication and scored multi-page selection.
- This port returns the first matching authorized full stream rather than eagerly resolving two additional backup streams. Work is cancellable and bounded by the original 30-second operation and a 20-second fallback budget. Cover loading has its existing separate bounded budget.
- Alternative-version gates reject unrequested live/cover/remix/instrumental/speed/pitch variants and excessive duration mismatches. Matching is heuristic, not proof of recording identity.
- The NetEase logical target, durable playlist identity, original metadata, artwork and lyrics remain unchanged. `sourceKey` records Bilibili representation identity; the actual lease still enforces Bilibili HTTPS/CDN/header/cache policy. Either source or original account replacement/revocation invalidates the lease.
- The pure matching strategy is reusable for a future QQ Music adapter. No QQ authentication, API, stream resolution or misleading usable QQ setting is included.

## Verification

All automated checks use synthetic credentials, catalogs and media, or the installed Salt ABI in isolated/offscreen fixtures. No real account, installed Salt user data or audible playback is used. Actual automatic matching, playback quality and visible in-app acceptance require local verification.

- Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.32-native-source.spmod`
- SHA256: `0B69E58FCEFECCB4DC9762B382628EC2C1C773366D657A3677B6CAFFE43A9D32`
- Final full build: 67 contracts / 92 host-and-base invocations; every log passes. Also 70 core checks, six inspector tests, ten host-free plugin contracts and 342 emitted host ABI references pass.
- Packaging: 319 entries / 769726 bytes; every packaged class/resource is byte-identical to the final build. Manifest retains the official project URL. No proprietary host implementation or tests are bundled.
- Offscreen checks exercise six width/density combinations, fixed header after scrolling, internal back navigation, filter/sort/play-all and returning to top-level tabs. Light-backdrop renders at 740px and 420px were visually inspected.
- The toggle test uses both the actual Salt UI call-site transform and method advice, verifies the native button is skipped only while hidden, confirms compact shortcut positioning, and restores its native click path. The installed advice ignores replaced argument slots, so the implementation does not depend on argument writeback.
- Synthetic source tests cover score/version/duration gates, filtered/unfiltered search, cross-query deduplication, multi-page choice, failed-candidate fallback, original metadata, both account revisions, cancellation, and durable NetEase identity reopening with a Bilibili lease.
