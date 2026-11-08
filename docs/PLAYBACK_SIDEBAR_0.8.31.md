# 0.8.31 Playback And Sidebar

## Behavior

- Collection preparation has no status label or progress toast. Failure uses Salt's official error toast and is deduplicated while the same error remains active.
- Play-all completion retains the currently displayed rows, row tokens, page and scroll position. A transient snapshot failure does not erase the last rendered page.
- Collection titles, search, play-all, layout, sorting and author tabs sit outside the scrolling list.
- The native initial play command remains in order with queue submission. A single start intent is also consumed at the selected player's `onLoaded` boundary, after its old control commands have been drained. It expires after 30 seconds; pause, stop, seek, track/queue changes, account revocation and shutdown cancel it. No timer resumes playback.
- Sidebar settings control ten native functional buttons plus Bilibili and NetEase, using one observable policy for expanded rows and top shortcuts. MusicLibrary visibility does not control online-platform visibility.
- Hidden-sidebar is rendered directly below sidebar optimization only when optimization is enabled, by filtering this plugin's native config group. Other plugins' settings remain native and unchanged.
- Enabling hidden-sidebar collapses the panel and blocks hover preview at the edge and toggle. Explicit native toggle clicks still expand/collapse the panel. Disabling optimization restores native hover behavior.

## Verification

Tests use synthetic catalogs/accounts, recording queue/hook boundaries and the installed 1.19.3 Compose/Skia ABI. Coverage includes row-token/page preservation, fixed toolbar after scroll, official-toast notification deduplication, sidebar visibility independence, native conditional group filtering, hover versus explicit toggle behavior, permission loss, exact hook call sites and one-shot start cancellation.

This release includes the bounded read-ahead and unavailable-start-candidate fixes described in `PLAYBACK_BUFFER_0.8.30.md`. Offline and offscreen tests cannot establish audible playback quality or real-account startup latency. Re-import the new artifact and fully restart Salt before checking repeated play-all, immediate pause, edge hover and early-song audio continuity.

Build/test tools do not modify the Salt installation or user data.

## Artifact

- Version: `0.8.31-playback-sidebar`
- Package: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.31-playback-sidebar.spmod`
- SHA256: `C590197F32ACF5C1CFCF6980E62285CF8CEF615CF58B1E35AC646F8751685B81`
- Verification: 65 contracts / 88 host-and-base invocations, 70 core checks, six inspector tests, nine host-free plugin contracts, 341 emitted host ABI references.
- The original offscreen pager fixture used the old scroll-item index; it was corrected after moving the title/actions out of the list, then the failed contract and all remaining contracts were rerun. All 88 final logs pass, and packaged classes/resources match the compiled output.
- The package was subsequently updated with the official `Plugin-Open-Source-Url: https://github.com/YGHFv/Salt-Extend-Online` metadata. Only `MANIFEST.MF` changed; every other packaged file remains byte-identical to the tested build. Metadata and artifact validation pass, including passive/experimental fixtures that reject missing or incorrect project URLs. The runtime suite was not rerun for this metadata-only update.
