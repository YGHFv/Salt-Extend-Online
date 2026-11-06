# Salt Extend Online 0.8.3

Artifact: `plugin/build/libs/plugin-io.github.yghfv.saltneri.online-0.8.3-icons-playcount.spmod`.

## Changes

- Playlist actions are icon-only, ordered search, play-all, sort. Each uses a 40 dp button target, 22 dp native vector drawing, theme color and an accessible label. No text-button border or fill remains.
- Inline search stays immediately left of its icon. Clicking the icon toggles the field; Enter submits, and hiding preserves the query/results.
- Bilibili play-count sorting reads favorite videos' `cnt_info.play` and collection archives' `stat.view` without extra video-detail requests.
- Counts use a dedicated 64-bit field, separate from duration and playlist item counts. Missing, negative, malformed or overflowing statistics become unknown (-1), not zero, and do not hide playable videos.
- Sorting covers the complete collection, descending by play count. Unknown values follow zero; ties retain upstream order. Search, pagination and replacement playback use the same order; play-all still includes the full collection regardless of filtering.
- NetEase's song endpoint does not supply per-song play counts, so that platform does not offer this sort mode. No inferred counts or extra scraping are used.

## Verification

- Real offscreen Compose tests cover 420, 580 and 740 dp widths at 100% and 150% density, including expanded/collapsed search. They assert icon-only semantics, nonzero drawn pixels, uniform hit targets and search/play/sort ordering.
- UI tests exercise the play-count menu and full-collection playback while filtering. Workflow tests cover three-page sorting, large counts, stable ties, zeroes, unknowns and restoring the original order.
- API fixtures cover both Bilibili statistics formats, numeric strings, absent/malformed fields, overflow and unchanged request counts.
- All rendered images are synthetic and stay local. The build does not modify installed Salt files, access user accounts or start audio.
