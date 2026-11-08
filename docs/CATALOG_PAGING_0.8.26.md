# 0.8.26 Configurable catalog windows and incremental scrolling

## Behavior

- `catalog.pageLimit` is shared by both platforms and defaults to `30`.
  Accepted values are `20`, `30`, `50`, `60`, `100`, and `0` (continuous view).
  Missing/invalid configuration falls back to 30; the native settings menu stores
  the selection through Salt's existing configuration manager.
- Fixed-size views show the existing footer pager with translated page counters.
  Platform responses still use their established 20-item protocol. Presentation
  windows combine only the intersecting source pages, reusing the existing
  account-scoped cache, without changing API size limits or whole-playlist loading.
- Continuous view initially requests a 30-slot window, then appends a further
  30-slot window after downward scrolling near the end. It does not prefetch the
  entire catalog or keep draining after one scroll. A footer action supports
  manual loading and explicit retry, including sparse/empty source windows.
  Unavailable items remain hidden, so a window can contain fewer visible items.
- Append keeps existing row tokens/capabilities and their order. It does not
  clear the list, change its displayed page identity, or reset the scroll offset.
  Late callbacks use a per-state token; duplicate requests are single-flight,
  and navigation/account changes invalidate pending operations. Errors leave
  previously loaded rows usable and never cause an automatic retry loop.
  A failed full search/sort restores the preserved append cursor, so subsequent
  scrolling does not append duplicate windows or skip already-unseen rows.
- Search and non-native sorting still use the full collection snapshot/loader.
  Remote search and uploader ordering keep their original query and order on
  every source-page request. Play-all uses the full unfiltered ordered collection,
  not the currently rendered prefix. Native random-play handling is unchanged.
- Changing the setting returns each active location to its first window without
  discarding its search/filter/order. Already-loaded complete collection metadata
  is reused. Continuous-view history retains the accumulated window when opening
  and returning from a child location. Platform switching keeps separate state.
- There is no new page-size restriction in continuous mode, but the existing
  10,000-item/500-source-page safety boundary remains. Audio cache quotas and
  quality settings are unchanged.

## Cover Loading

The image request registry previously assumed that no more than 20 row tokens
could remain valid. Continuous lists retain earlier rows, so completed request
entries are now recycled when the bounded registry fills. The shared thumbnail
LRU remains intact; later rows load without invalidating earlier row capabilities
or retaining an unbounded queue of image tasks.

## Native Button Outlines

Toolbar buttons already use Salt's installed native pill component, and search
and sort use its actual icon renderers. The remaining custom icons used mixed
1.5/1.6/2 dp outlines, which made them visibly heavier. The installed native
48px search bitmap has roughly 3px of straight-stroke alpha coverage, equivalent
to 1.25 dp in the 20 dp toolbar slot. Custom outlines now share that 1.25 dp
weight, including navigation, favorites, subscriptions, refresh, layouts and
account/sort menu glyphs. Virtual 20/24-unit paths scale the stroke once with
display density. Native controls and existing hit targets are unchanged; filled
play triangles remain filled. Existing 1 dp fallback input/popup borders remain.

Only local numeric stroke metrics were inspected; the user's screenshot was not
opened, encoded or copied into tool output. No native bitmap is bundled in the
plugin. `HeaderSearchRenderTest` measures rendered native/custom straight-outline
coverage to catch a thicker custom stroke while retaining its existing button,
search, account and menu interaction checks.

## Verification

`CatalogPagingContractTest` exercises every limit, exact and unknown totals,
non-divisible source/display boundaries, short filtered source pages, both
platforms, stable incremental rows, search beyond the initial prefix, full-order
play-all, settings changes, child navigation, explicit error retry and account
revocation during an append. It uses synthetic metadata only.

`CatalogPagingRenderTest` uses real Salt Compose lazy lists at single-column,
double-column and card layouts, for both platforms. It checks initial 30-row
loading, actual scroll-triggered append, no automatic drain, no continuous-mode
pager, and footer counters/navigation for all fixed limits. No windows, real
accounts, image URLs or playback are used.

Legacy 20-row protocol/pager fixtures explicitly select the still-supported
20-row option. Separate new coverage verifies the production default of 30.
`CoverLoaderContractTest` also retains 100 simultaneously valid synthetic row
tokens and checks that each distinct cover loads exactly once without starvation.

Final local validation passed all 61 inventoried contracts (83 host/base runs),
70 core checks, six inspector tests and 319 emitted host ABI references. The
host-free suite also passed. Paging contributed 16026 fixture checks and 324
real lazy-list rendering/interaction checks; the header/search suite passed 367
checks including native/custom stroke comparison. All 281 packaged entries match
the tested classes/resources. The 676468-byte archive has SHA-256
`E1A5AB123E8C3F80C97938393BF4B7F5A86A209D92A62F32B165F7E2BA205226`.

These are synthetic catalog/queue tests and local offscreen renders, not a claim
that every live platform endpoint or actual audio device was exercised. No user
playlists, account credentials or real playback were used during validation.
